// 发版脚本：node tools/release.mjs [--preview]
//   --preview  只打印将要发布的版本与更新日志（不发版），发版前给用户确认用
//   不带参数   同步 changelog.txt → 构建 APK → 暂存到部署目录（不部署，部署由 WorkBuddy 站点工具做）
//
// 流程（详见 RELEASE.md）：
//   1) 改代码 → 2) 改 AndroidManifest.xml 版本号 → 3) 在 CHANGELOG.md 顶部写本次更新日志
//   4) node tools/release.mjs --preview → 把日志给用户确认/修改 → 5) node tools/release.mjs
//   6) 部署到线上
//
// 关键：CHANGELOG.md 是更新日志的唯一来源，changelog.txt 由本脚本生成（App 弹窗与网页端读它）。
// 版本号只在 AndroidManifest.xml 里写一处，本脚本同步到 version.txt 与 APK 文件名。

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const HERE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DEPLOY_DIR = 'D:/Program Files/WorkBuddyCache/UIPlus-Web卡片/output/UiPlus-3DCar-APK/car3d';
const PREVIEW = process.argv.includes('--preview');
/** changelog.txt 里保留最近几个版本 */
const KEEP_VERSIONS = 6;

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
  console.log(changelogText.trim());
  console.log('────────────────────────────────');
  console.log(`将同步：changelog.txt（保留最近 ${KEEP_VERSIONS} 个版本）`);
  console.log(`将构建：out/3D车况 v${VER}.apk`);
  console.log(`将暂存：${DEPLOY_DIR}`);
  console.log('\n确认无误后运行：node tools/release.mjs');
  process.exit(0);
}

// ---------- 正式发版：同步日志 → 构建 → 暂存 ----------
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

execFileSync('node', ['build.mjs'], { cwd: HERE, stdio: 'inherit', env });

const apk = path.join(HERE, 'out', `3D车况 v${VER}.apk`);
if (!fs.existsSync(apk)) { console.error(`✗ 没找到构建产物：${apk}`); process.exit(1); }

fs.mkdirSync(DEPLOY_DIR, { recursive: true });
fs.copyFileSync(apk, path.join(DEPLOY_DIR, `3D车况 v${VER}.apk`));
fs.copyFileSync(apk, path.join(DEPLOY_DIR, 'latest.apk'));
fs.writeFileSync(path.join(DEPLOY_DIR, 'version.txt'), `${VER}\n${CODE}\n`, 'utf8');
fs.copyFileSync(path.join(HERE, 'changelog.txt'), path.join(DEPLOY_DIR, 'changelog.txt'));
// 清掉旧版本包，只留这次的（latest.apk 除外）
for (const f of fs.readdirSync(DEPLOY_DIR)) {
  if (/^3D车况 v.*\.apk$/.test(f) && f !== `3D车况 v${VER}.apk`) fs.unlinkSync(path.join(DEPLOY_DIR, f));
}
console.log('\n✓ 暂存完成，部署目录内容：');
for (const f of fs.readdirSync(DEPLOY_DIR).sort()) console.log('   ' + f);
console.log('\n下一步：部署到线上（站点部署工具），完成后线上 version.txt 应为 ' + VER);
