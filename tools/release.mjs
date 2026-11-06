// 发版脚本：node tools/release.mjs [--preview] [--test]
//   --preview  只打印将要发布的版本与更新日志（不发版），发版前给用户确认用
//   --changelog-only  只按 CHANGELOG.md 重新同步 changelog.txt（不构建 APK、不动部署目录）
//   --test     发到测试通道（version_test.txt / latest_test.apk / changelog_test.txt）：
//              构建时把 Settings.testBuild 默认翻成 true（测试版 APK 装完才走测试通道），
//              且不碰正式通道文件（version.txt / latest.apk / changelog.txt），正式用户不受影响。
//   不带参数   同步 changelog.txt → 构建 APK → 暂存到部署目录（正式通道）
//
// 流程（详见 RELEASE.md）：
//   1) 改代码 → 2) 改 AndroidManifest.xml 版本号 → 3) 在 CHANGELOG.md 顶部写本次更新日志
//   4) node tools/release.mjs --preview → 把日志给用户确认/修改 → 5) node tools/release.mjs [--test]
//   6) 部署到线上
//
// 关键：CHANGELOG.md 是更新日志的唯一来源，changelog.txt 由本脚本生成（App 弹窗与网页端读它）。
// 版本号只在 AndroidManifest.xml 里写一处，本脚本同步到 version.txt / version_test.txt 与 APK 文件名。

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const HERE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DEPLOY_DIR = 'D:/Program Files/WorkBuddyCache/UIPlus-Web卡片/output/UiPlus-3DCar-APK/car3d';
const PREVIEW = process.argv.includes('--preview');
/** changelog.txt 保留全部历史版本：下载页「历史更新日志」要能查到每一个版本，不再只留最近几个 */
const KEEP_VERSIONS = 999;

const read = (p) => fs.readFileSync(path.join(HERE, p), 'utf8');
const write = (p, s) => fs.writeFileSync(path.join(HERE, p), s, 'utf8');

// ---------- 版本号：只在 AndroidManifest.xml 里写一处 ----------
const manifest = read('AndroidManifest.xml');
const mv = manifest.match(/android:versionCode="(\d+)"/);
const mn = manifest.match(/android:versionName="([^"]+)"/);
if (!mv || !mn) { console.error('✗ AndroidManifest.xml 里读不到版本号'); process.exit(1); }
const CODE = mv[1], VER = mn[1];

// ---------- 更新日志：CHANGELOG.md → changelog.txt ----------
/** 解析 CHANGELOG.md：返回 [{ver, items:[...]}, ...]，按文件里从上到下（即新→旧） */
function parseChangelog(text) {
  const out = [];
  const lines = text.split(/\r?\n/);
  let cur = null;
  for (const ln of lines) {
    const h = ln.match(/^##\s+v?(\S+)\s*$/);
    if (h) { cur = { ver: h[1], items: [] }; out.push(cur); continue; }
    if (!cur) continue;
    const b = ln.match(/^\s*(?:[-*·]|\d+[.)])\s+(.*\S)\s*$/);
    if (b) cur.items.push(b[1].replace(/^\d+[.)]\s+/, '')); // 兼容无序与有序写法，序号统一由输出时生成
  }
  return out;
}
const blocks = parseChangelog(read('CHANGELOG.md'));
if (!blocks.length) { console.error('✗ CHANGELOG.md 里没有版本段落'); process.exit(1); }

// 一致性检查：CHANGELOG.md 顶部那段的版本号必须等于 manifest 里的版本号
if (blocks[0].ver !== VER) {
  console.error(`✗ 版本号对不上：manifest 是 v${VER}，CHANGELOG.md 顶部是 v${blocks[0].ver}`);
  console.error('  改一处就行：把 AndroidManifest.xml 的 versionName/versionCode 与 CHANGELOG.md 顶部段落对齐。');
  process.exit(1);
}
if (!blocks[0].items.length) { console.error('✗ CHANGELOG.md 顶部这段还没有条目'); process.exit(1); }

/** 生成 changelog.txt 的内容：取最近 KEEP_VERSIONS 个版本，首行版本号 + 带序号的条目（用户要求日志有序号） */
function buildChangelogText(bs) {
  return bs.slice(0, KEEP_VERSIONS).map(b => 'v' + b.ver + '\n' + b.items.map((i, n) => (n + 1) + '. ' + i).join('\n')).join('\n\n') + '\n';
}
const changelogText = buildChangelogText(blocks);

// ---------- --preview：给用户确认用 ----------
if (PREVIEW) {
  console.log('════ 本次将发布 ════');
  console.log(`版本：v${VER}（versionCode ${CODE}）`);
  console.log('────────────────────────────────');
  const pv = changelogText.trim().split(/\n\s*\n/);
  console.log(pv.slice(0, 2).join('\n\n'));
  if (pv.length > 2) console.log(`\n… 另有 ${pv.length - 2} 个历史版本，完整内容见 changelog.txt`);
  console.log('────────────────────────────────');
  console.log(`将同步：changelog.txt（保留全部 ${blocks.length} 个版本）`);
  console.log(`将构建：out/3D车况 v${VER}.apk`);
  console.log(`将暂存：${DEPLOY_DIR}`);
  console.log('\n确认无误后运行：node tools/release.mjs');
  process.exit(0);
}

// ---------- --changelog-only：只同步日志，不构建 APK、不动部署目录 ----------
if (process.argv.includes('--changelog-only')) {
  write('changelog.txt', changelogText);
  console.log(`✓ changelog.txt 已同步（${Math.min(blocks.length, KEEP_VERSIONS)} 个版本，全量历史）`);
  process.exit(0);
}

// ---------- 发版：同步日志 → 构建 → 暂存 ----------
const TEST = process.argv.includes('--test');
const SETTINGS_FILE = 'src/com/x3card/car3d/Settings.java';
const MANIFEST_FILE = 'AndroidManifest.xml';
const TEST_OFF = 'd.put("testBuild", false);';
const TEST_ON = 'd.put("testBuild", true);';

// 测试通道：构建前把 testBuild 默认翻成 true（否则测试版 APK 覆盖安装后仍走正式通道），
// 并把全局版本名直接带上「测试版」字样（设置页、系统应用信息、更新弹窗全都自动跟着显示），构建完都还原
let flipped = false, nameFlipped = false;
if (TEST) {
  let s = read(SETTINGS_FILE);
  if (s.includes(TEST_OFF)) { s = s.replace(TEST_OFF, TEST_ON); write(SETTINGS_FILE, s); flipped = true; }
  else if (!s.includes(TEST_ON)) { console.error('✗ 找不到 Settings.java 里的 testBuild 默认值行'); process.exit(1); }
  const vnOff = `android:versionName="${VER}"`, vnOn = `android:versionName="${VER} 测试版"`;
  let m = read(MANIFEST_FILE);
  if (m.includes(vnOff)) { m = m.replace(vnOff, vnOn); write(MANIFEST_FILE, m); nameFlipped = true; }
  else if (!m.includes(vnOn)) { console.error('✗ 找不到 AndroidManifest.xml 里的 versionName（' + vnOff + '）'); process.exit(1); }
  console.log('· 测试通道：构建期已把 testBuild 默认翻为 true、版本名改为「' + VER + ' 测试版」');
}

write('changelog.txt', changelogText);
console.log(`✓ changelog.txt 已同步（${Math.min(KEEP_VERSIONS, blocks.length)} 个版本）`);

// 构建要 Android 工具链：本机固定位置兜底（也认环境变量）
const TOOLCHAIN = 'D:/Program Files/WorkBuddyCache/apk_work';
const env = { ...process.env };
if (!env.ANDROID_BUILD_TOOLS) env.ANDROID_BUILD_TOOLS = `${TOOLCHAIN}/build-tools/android-14`;
if (!env.ANDROID_JAR) env.ANDROID_JAR = `${TOOLCHAIN}/sdk/platforms/android-34/android.jar`;
if (!env.JAVA_HOME) env.JAVA_HOME = `${TOOLCHAIN}/jdk/jdk-17.0.20.1+1`;
env.PATH = `${env.JAVA_HOME}/bin:${env.PATH}`;
for (const k of ['ANDROID_BUILD_TOOLS', 'ANDROID_JAR', 'JAVA_HOME']) {
  if (!fs.existsSync(env[k])) { console.error(`✗ 找不到 ${k}：${env[k]}`); process.exit(1); }
}

try {
  execFileSync('node', ['build.mjs'], { cwd: HERE, stdio: 'inherit', env });
} finally {
  if (flipped) { // 还原 testBuild 默认值，避免污染下一次正式构建
    let s = read(SETTINGS_FILE); s = s.replace(TEST_ON, TEST_OFF); write(SETTINGS_FILE, s); flipped = false;
    console.log('· 已还原 testBuild 默认为 false');
  }
  if (nameFlipped) { // 还原全局版本名（去掉「测试版」字样，避免污染下一次正式构建）
    let m = read(MANIFEST_FILE);
    m = m.replace(`android:versionName="${VER} 测试版"`, `android:versionName="${VER}"`);
    write(MANIFEST_FILE, m); nameFlipped = false;
    console.log('· 已还原版本名为 ' + VER);
  }
}

// 构建产物的文件名来自清单里的 versionName：测试构建时版本名带「测试版」，产物名也跟着带
const apk = path.join(HERE, 'out', TEST ? `3D车况 v${VER} 测试版.apk` : `3D车况 v${VER}.apk`);
if (!fs.existsSync(apk)) { console.error(`✗ 没找到构建产物：${apk}`); process.exit(1); }

fs.mkdirSync(DEPLOY_DIR, { recursive: true });
// 命名包：测试版带「测试版(版本号)」后缀，避免和正式版命名包混在根目录
const namedApk = `3D车况 v${VER}${TEST ? ' 测试版(' + CODE + ')' : ''}.apk`;
fs.copyFileSync(apk, path.join(DEPLOY_DIR, namedApk));

if (TEST) {
  // 测试通道：双写「根目录 + test/ 子目录」
  //   - 根目录 version_test.txt / latest_test.apk：给 App 内 Updater 读（它读的是站点根）
  //   - test/ 子目录：给下载页 index.html 的测试版卡片读（设计上测试版隔离在 test/）
  //   version_test.txt 第一行带「测试版」字样，下载页版本号直接显示「v0.7.31 测试版」
  const TEST_DIR = path.join(DEPLOY_DIR, 'test');
  fs.mkdirSync(TEST_DIR, { recursive: true });
  for (const d of [DEPLOY_DIR, TEST_DIR]) {
    fs.copyFileSync(apk, path.join(d, 'latest_test.apk'));
    fs.writeFileSync(path.join(d, 'version_test.txt'), `${VER} 测试版\n${CODE}\n`, 'utf8');
    fs.copyFileSync(path.join(HERE, 'changelog.txt'), path.join(d, 'changelog_test.txt'));
  }
  fs.copyFileSync(apk, path.join(TEST_DIR, namedApk));
  // 清掉根目录里上次误留的测试命名包（测试版命名包只该在 test/）
  for (const f of fs.readdirSync(DEPLOY_DIR)) {
    if (/^3D车况 v.*测试版.*\.apk$/.test(f)) fs.unlinkSync(path.join(DEPLOY_DIR, f));
  }
  console.log('\n✓ 测试通道暂存完成（根目录 + test/ 双写）：');
  console.log('   version_test.txt = ' + VER + ' 测试版 / ' + CODE);
  console.log('   latest_test.apk、changelog_test.txt 已更新（两处）');
  console.log('   ' + namedApk + ' 已放入 test/');
  console.log('   （正式通道 version.txt / latest.apk / changelog.txt 未改动）');
} else {
  fs.copyFileSync(apk, path.join(DEPLOY_DIR, 'latest.apk'));
  fs.writeFileSync(path.join(DEPLOY_DIR, 'version.txt'), `${VER}\n${CODE}\n`, 'utf8');
  fs.copyFileSync(path.join(HERE, 'changelog.txt'), path.join(DEPLOY_DIR, 'changelog.txt'));
  // ★正式发版只动正式通道：测试通道（version_test.txt / latest_test.apk / changelog_test.txt）保持
  //   上一轮测试版不动。装测试版的车机把「测试版更新」关掉就走正式通道，正常收到正式版更新。
  // 清掉旧版本包，只留这次的（latest.apk 除外）
  for (const f of fs.readdirSync(DEPLOY_DIR)) {
    if (/^3D车况 v.*\.apk$/.test(f) && f !== `3D车况 v${VER}.apk`) fs.unlinkSync(path.join(DEPLOY_DIR, f));
  }
  console.log('\n✓ 正式通道暂存完成：');
  console.log('   version.txt = ' + VER + ' / ' + CODE);
  console.log('   latest.apk、changelog.txt 已更新');
}
console.log('部署目录内容：');
for (const f of fs.readdirSync(DEPLOY_DIR).sort()) console.log('   ' + f);
console.log(`\n下一步：部署到线上（站点部署工具），完成后线上 ${TEST ? 'version_test.txt' : 'version.txt'} 应为 ` + VER);
