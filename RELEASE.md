# 发版流程（SOP）

车机 3D 车况（`com.ljone.car3d`）每次发版都按这个流程走。

## 硬性规矩

**更新日志必须先给用户确认，才能发版。**

具体做法：
1. 改完代码 → 升版本号 → 在 `CHANGELOG.md` 顶部写好本次的更新日志；
2. 运行 `node tools/release.mjs --preview`，它会打印**本次将发布的版本号和完整更新日志**；
3. **用弹窗（AskUserQuestion）问用户**："本次更新日志是否需要修改？"，把日志内容原样列在问题里，选项给「不用改，直接发版」/「需要修改」；
4. 用户说改哪条就改哪条 → 改完 `CHANGELOG.md` → 再跑一次 `--preview` 复核 → 再弹窗确认；
5. 用户确认 → `node tools/release.mjs`（同步日志 + 构建 + 暂存）→ 部署到线上。

**超时规则（用户 2026-10-29 定，2026-11-04 改为 5 分钟）**：弹窗发出后**若 5 分钟内无回应，默认不需要修改，直接继续执行后续操作**（构建 → 部署）。不要因为等确认而卡住流程。

**不要跳过确认直接发版。** 用户明确要求过：更新日志发版前要过他确认，并且要能直接改。

### 更新日志的写法

- 口吻要和前面的历史日志**一致**：说"用户能看到什么变化"，**不列技术细节**（不要写"小球尺寸""渐变角度""行高 1.15→1.5"这类实现细节）；
- **正式、简洁**（用户 2026-11-03 要求）：书面语短句，不用「去掉了」「这一样式」等口语，不列样式清单、不写括号补充说明，一条一句话；
- 一条一件事，通常 2～4 条；
- 格式：`- 说明文字`（CHANGELOG.md 里用 `-`，脚本会转成 `changelog.txt` 里的 `·`）。

反例（v0.5.45 初稿，被用户指出"和前面的日志不一样"）：

```
- 车漆小球尺寸与选中光晕对齐，说明文字行高、开关和滑块阴影、卡片渐变角度同步
```

正例（改写后）：

```
- 车漆色板尺寸与选中光晕同步网页版
```

## 版本号规则

- 版本号**只在 `AndroidManifest.xml` 写一处**（`versionCode` + `versionName`），其余全部由 `tools/release.mjs` 同步；
- SemVer：PATCH = 修复/微调，MINOR = 新功能，MAJOR = 大改；
- `versionCode` 每次 +1；
- `tools/release.mjs` 会校验 `AndroidManifest.xml` 的版本号与 `CHANGELOG.md` 顶部段落一致，不一致直接报错退出（防呆）。

## 更新日志

- **`CHANGELOG.md` 是唯一来源**，格式：

  ```markdown
  ## v0.5.45
  - 第一条改动
  - 第二条改动
  ```

- `changelog.txt` 由脚本自动生成（首行版本号 + `· 条目`，保留最近 6 个版本），**不要手改它**；
- App 的更新弹窗和网页下载页读的都是 `changelog.txt`。

## 完整命令

```bash
cd /d/car3d_baseline

# 1) 改代码后升版本号（改 AndroidManifest.xml 的 versionCode / versionName）

# 2) 在 CHANGELOG.md 顶部写本次日志

# 3) 预览（给用户确认）
node tools/release.mjs --preview

# 4) 确认无误后：同步日志 + 构建 + 暂存到部署目录
node tools/release.mjs

# 5) 部署到线上（WorkBuddy 站点部署工具，目录见脚本里的 DEPLOY_DIR）

# 6) 验证：线上 version.txt / latest.apk / changelog.txt 都能拉到
```

## 部署目录

`D:/Program Files/WorkBuddyCache/UIPlus-Web卡片/output/UiPlus-3DCar-APK/car3d`

暂存后应含：`3D车况 v<版本>.apk`、`latest.apk`、`version.txt`（第1行版本名、第2行 versionCode）、`changelog.txt`、`index.html`（下载页）。

## 线上链接

`https://car3d-apk.app.workbuddy.host/?sharecode=AY7S4GPRRP`

- 站点**启用了分享码保护**：直连任何资源都是 403，**必须带 `?sharecode=AY7S4GPRRP`**（App 内 `Updater` 的 URL 已带）；
- 部署用 WorkBuddy 的站点部署工具，`userAskedToPublish: true`；
- 部署偶尔会报「端口 3000 未在 60 秒内就绪」，是平台瞬时故障，**直接重试**即可。
