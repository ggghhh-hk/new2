# TabX 多开浏览器 (GM99 斗罗大陆H5 专属版)

> 🎮 专为 **GM99 斗罗大陆H5** 与各类 Web / H5 游戏打造的高性能、多账号隔离、同屏群控与自动化脚本浏览器。
> 🚀 支持 **同屏 25开 (5x5 矩阵)、36开 (6x6 阵列) 乃至百开监控墙**，配备多标签隔离、动作同步群控、防封挂机录制引擎与一键静音/清理功能。

---

## ✨ 核心功能亮点

### 1. 🔥 同屏 25 开及以上超高密度多开矩阵
- **一键 25 开同屏 (5x5)**：点击顶栏或账号库中的【🚀 一键25开同屏】，自动批量拉起 25 个独立账号窗口。
- **36 开同屏阵列 (6x6) 与百开监视墙**：支持 1开(全屏)、2开(上下/左右分屏)、4开(2x2)、9开(3x3)、16开(4x4)、25开(5x5)、36开(6x6) 及全部同屏矩阵模式。
- **单号放大与一秒切回**：在 25 开大盘模式下，点击任意子账号的“全屏放大”即可专注操控该角色，左下角提供【返回 25开矩阵同屏】悬浮快捷键，随时一键还原全局视窗。

### 2. ⚡ 毫秒级多标签动作同步群控
- **全屏同步广播**：开启【同步群控】后，在主控窗口上的点击、滑动与触控事件会实时精准广播至所有 25+ 后台或同屏分屏标签，实现一键打本、一键领取、全号同进同退。

### 3. 🛡️ 账号 Session / Cookie 强效隔离
- **独立 CookieJar & LocalStorage**：每个标签页和窗口拥有完全隔离的会话容器，多账号同时在线互不干扰、互不顶号。
- **斗罗号库管理**：支持一键入库角色名称、所在服务器分区、登录入口与账号阵容备注，支持批量生成 25 队阵容。

### 4. 🤖 可视化挂机宏录制与防封执行引擎
- **手势录制悬浮 HUD**：在游戏界面中点击即可捕获相对坐标点，支持插入延时等待。
- **智能循环与倍速**：支持 1x / 1.5x / 2x / 3x 倍速调节，支持无限循环与指定轮次。
- **±6px 防封抖动**：每次点击自动叠加随机坐标偏移与微小时间抖动，模拟真人手势。

### 5. 🛠️ 游戏专属实用工具
- **🔇 全体一键静音**：一键关闭所有 25+ 标签页中的 H5 游戏音频，避免多账号混音刺耳。
- **🔄 全量刷新**：一键对所有分屏窗口执行安全重载。
- **深度缓存清理**：可一键彻底清理 Web 缓存、Cookies、IndexedDB/LocalStorage、表单与历史记录。
- **User-Agent 模拟**：内置 Android、Desktop Chrome、iOS Safari、iPad Safari 与 GM99 专属微端 UA。

---

## 🔐 多账号会话隔离说明（重要）

本项目通过 **WebView 多 Profile 能力**（`androidx.webkit` 的 `ProfileStore` + `WebViewCompat.setProfile`）为每个窗口分配独立的会话容器：

- 每个标签页/窗口拥有**自己的 Cookie、LocalStorage、IndexedDB 与网页缓存**，多账号同时在线不会互相顶号。
- 每个新窗口分配持久化 profile（`tabx_slot_N`），**重启 App 后登录态保留**；斗罗号库中的账号使用 `tabx_acct_<id>`，便于固定某个账号的登录态。
- 「深度清理缓存」默认**只登出当前窗口的账号**，其他账号保持登录；需要重置全部账号时，在清理对话框中打开「同时登出全部账号」。

**设备要求**：该能力依赖系统 WebView 支持多 Profile（Android System WebView 119+，近两年更新过的设备基本都支持）。App 启动时会输出一行日志：

```
SessionProfiles: WebView multi-profile session isolation supported = true
```

若显示 `false`，说明该设备 WebView 过旧，此时所有窗口会回退到共用的默认会话（会出现顶号）。在 Android Studio 的 Logcat 中过滤 `SessionProfiles` / `WebViewPool`，即可看到每个窗口实际绑定的 profile：

```
WebViewPool: tab=<id> -> session profile 'tabx_slot_1' isolated=true
```

---

## 📱 如何下载与安装运行 APK

### 方式一：GitHub Actions CI/CD 自动构建与下载 APK（最省心，全自动）
本项目已内置 GitHub Actions CI/CD 工作流（`.github/workflows/android-build-apk.yml`）：
1. **自动构建**：每次向 `main` 分支提交代码或发起 Pull Request 时，GitHub 都会自动在云端编译生成最新的 Debug APK。
2. **下载 APK 制件 (Artifacts)**：
   - 打开你的 GitHub 仓库，点击顶部的 **Actions** 标签页。
   - 点击最新一次成功的运行记录（如 `Build Debug APK`）。
   - 在页面底部的 **Artifacts** 区域，直接点击 **`TabX-DLDL-Debug-APK`** 即可下载生成的 APK。
   - ⚠️ 产物**只保留 30 天**（`retention-days: 30`），过期后需要重新跑一次工作流；工作流支持手动触发：Actions → 选中本工作流 → **Run workflow**。
3. **自动发布 Release**：
   - 推送版本 Tag（例如 `git tag v1.0.0 && git push origin v1.0.0`）后，CI/CD 会自动创建 Release 并挂载 APK。
   - 未配置签名密钥时发布的是 **debug** APK，文件名会明确标注 `-debug.apk`；配置以下仓库 Secrets 后会改为发布正式签名的 release APK（`-release.apk`）：
     `KEYSTORE_BASE64`（上传密钥 `.jks` 的 base64）、`STORE_PASSWORD`、`KEY_PASSWORD`、`KEY_ALIAS`（可选，默认 `upload`）。
   - 若构建失败或没有产出 APK，工作流会**直接报错并标红**，不会再出现"绿勾却下载不到 APK"的情况。

---

### 方式二：在 Google AI Studio 平台直接导出 / 下载 APK（无需配置）
1. 在浏览器右上角找到 **平台设置菜单（齿轮或更多操作图标）**。
2. 点击 **“Export as ZIP”**（导出源码压缩包）或 **“Generate APK / Download APK”**。
3. 将生成的 `.apk` 安装文件传输至安卓手机或安卓模拟器（如雷电模拟器、MuMu模拟器、夜神模拟器等）直接安装运行。

---

### 方式三：克隆源码并在 Android Studio 中本地编译安装

#### 1. 克隆仓库
```bash
git clone <你的GitHub仓库链接>
cd <项目目录>
```

#### 2. 打开项目
- 下载并打开最新版 **[Android Studio](https://developer.android.com/studio)** (推荐 Iguana / Jellyfish / Ladybug 或更高版本)。
- 选择 `File` -> `Open...`，选择本项目根目录。
- 等待 Gradle 自动完成依赖同步（Sync Project with Gradle Files）。

#### 3. 编译与运行 APK
- **连接真机或启动模拟器**（启用 USB 调试）。
- 点击 Android Studio 顶部工具栏的绿色 ▶ **Run 'app'** 按钮即可自动构建并安装到设备中。
- 或在终端执行 Gradle 命令生成 APK（仓库已内置 Gradle Wrapper，无需另外安装 Gradle）：
```bash
# Linux / macOS:
./gradlew assembleDebug

# Windows:
gradlew.bat assembleDebug
```
- 生成的 APK 路径位于：`app/build/outputs/apk/debug/app-debug.apk`。
- **无需手工准备签名文件**：debug 构建使用 AGP 自动生成的默认调试密钥；首次构建时 AGP 会自动下载所需的 Android SDK 平台（`android-36.1`）。

---

## 🚀 如何将项目推送到 GitHub (Push to GitHub)

### 方式一：在 AI Studio 界面一键推送到 GitHub（最便捷）
1. 在 AI Studio 界面顶部或右侧菜单中，找到 **“Push to GitHub”**（或 GitHub 图标）。
2. 授权连接你的 GitHub 账号，选择或输入要推送的仓库名称（Repository Name）。
3. 点击确认，系统会自动将当前所有代码推送到你的 GitHub 仓库。

### 方式二：通过 Git 命令行推送

如果在本地终端或已配置 Git 环境，按以下步骤推送到你的 GitHub 仓库：

```bash
# 1. 初始化本地仓库（若未初始化）
git init

# 2. 添加所有项目文件
git add .

# 3. 提交更改
git commit -m "feat: 支持GM99斗罗大陆H5同屏25开/36开矩阵群控、账号隔离与自动化挂机"

# 4. 关联你的 GitHub 远程仓库 (将 USERNAME 与 REPO 替换为你的 GitHub 账号和仓库名)
git remote add origin https://github.com/USERNAME/REPO.git

# 5. 切换到 main 分支并推送到 GitHub
git branch -M main
git push -u origin main
```

---

## 📋 架构说明

- **UI 框架**：100% Jetpack Compose + Material Design 3 (动态配色、高密度多开网格、悬浮 HUD)
- **多开与渲染**：`WebViewPool` 自适应 Chromium 渲染管线 + 动态 Compose 生命周期回收与分屏解绑
- **JavaScript 桥接**：`TabXBridge` 动作监听与事件分发注入
- **数据持久化**：Room 数据库（本地安全存储账号信息、宏脚本与配置）
- **后台挂机**：Kotlin 协程 + StateFlow 状态驱动架构
