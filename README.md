# XenoLauncher

基于 [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2) 修改的**非官方 Android Minecraft: Java Edition 启动器**，围绕 AI 辅助、渲染器配置和界面体验进行改进。

> 本项目由社区独立维护，与 ZalithLauncher / ZalithLauncher2 官方及 MovTery 没有隶属、授权或背书关系。保留上游版权、GPL-3.0 及第三方许可声明。

[下载 APK](https://github.com/yydxin/Xeno-Launcher/releases) · [反馈问题](https://github.com/yydxin/Xeno-Launcher/issues) · [上游项目](https://github.com/ZalithLauncher/ZalithLauncher2)

## 当前版本

- **1.0.1 / versionCode 10001**
- 应用包名：`io.github.yydxin.xenolauncher`
- 本次 APK：**arm64-v8a**，Android 8.0（API 26）及以上
- 安装包和 SHA-256 校验文件放在 [Releases](https://github.com/yydxin/Xeno-Launcher/releases)，不混入源码提交。

## 主要功能

- **AI 助手**：支持 OpenAI 兼容及 Anthropic 接口，包含流式对话、启动日志分析、模组翻译等入口；使用者自行配置服务商，API Key 不随仓库分发。
- **MobileGlues 渲染器**：内置 ARM64 渲染库，提供 ANGLE、GL 版本、FSR、GLSL 缓存等设置与诊断入口。
- **液态玻璃界面**：提供主题、主界面顶栏、设置侧栏和通用对话框的玻璃效果及调节入口。
- **整合包管理**：包含来源识别、更新计划、备份和回滚逻辑，保留存档等受保护文件。
- **独立更新检查**：查询本仓库的 GitHub Releases，包含包信息、SHA-256 和签名检查逻辑。

以上为代码中提供的功能，不代表所有设备、驱动、模型或整合包组合都已验证。

## 1.0.1：GLSL 缓存设置修复

- 滑条位置、数值标签和箭头按钮由同一个 ViewModel / StateFlow 驱动，不再另存一份滑条值。
- 固定 **0–1024 MB**，步长 **1 MB**；`0` 表示关闭缓存，`512 MB` 对应半格。
- 配置加载完成前不显示默认控件；正常加载不会触发保存或“已保存”提示。
- 拖动只预览，松手、箭头点击和手输确认才提交缓存值，不顺便提交其它未保存选项。
- 同目录临时文件、同步落盘、原子替换、立即回读校验；保留未知配置键。
- 提供配置调试日志开关、查看和导出入口，并记录实际 `MG_DIR_PATH`。

详细说明和验收步骤见 [1.0.1 发布说明](docs/releases/1.0.1.md)。

**验证边界：** 已完成 APK 构建、签名/版本/清单资源和必需 JNI 的静态检查。按维护者要求，本轮**未安装应用、未启动游戏、未运行单元测试或真机测试**。新增测试只提供源码，仍需验收滚动、旋转、分栏、重进、杀进程和游戏往返。

## 从源码构建

### 环境

- JDK 17
- Gradle 9.5.0（使用仓库提供的 wrapper）
- Android SDK Platform 37.0 / 37.2、Build Tools 36.0.0
- AGP 9.3.0、Kotlin 2.4.20（版本由工程统一指定）

工程保留对应 ARM64 原生库。只打包 ARM64 且不修改原生代码时，可显式使用预编译模式，避免要求宿主运行 NDK / Rust 编译器：

```sh
bash gradlew --no-daemon -Darch=arm64 \
  -Pzl2UsePrebuiltJni=true \
  :ZalithLauncher:assembleDebug
```

正式包必须使用维护者自己的签名；不要生成新密钥冒充既有版本的覆盖更新：

```sh
export XENO_KEYSTORE_PROPERTIES=/private/path/keystore.properties
bash gradlew --no-daemon -Darch=arm64 \
  -Pzl2UsePrebuiltJni=true \
  :ZalithLauncher:assembleRelease
```

签名配置格式见 [keystore.properties.example](keystore.properties.example)。真实 JKS、口令、Token、本机配置和下载缓存**不属于公开源码**。

如需修改 C/C++ 或 Rust 原生代码，请使用正常 NDK / Rust 构建流程，不要继续复用旧二进制。手机 ARM64 Linux / proot 还需要兼容的宿主资源编译工具；不能直接运行官方 SDK 中的 x86_64 可执行文件。不同宿主的完整重建仍需自行验证。

## 限制与注意事项

- 本次 APK 仅面向 ARM64，不是全 ABI 安装包。
- 当前发布构建未配置 `OAUTH_CLIENT_ID` 和 `CURSEFORGE_API_KEY`，相关登录或下载能力可能受限；不得把私人凭据写进代码。
- 光线追踪页面目前是能力检测与设置入口，不包含光追 Mod 或光影包，也不保证设备支持或实际画面改变。
- 调试时以设置页显示的实际配置目录为准，不要默认检查公共 `/sdcard/MG` 目录。
- 提交反馈前请移除日志中的账号、令牌、聊天内容和其它个人信息。

## 许可证与致谢

- 上游 [ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2)、MovTery 及贡献者；启动核心同时承袭 PojavLauncher。
- 本项目遵循 [GPL-3.0](LICENSE)，上游归属与修改说明见 [NOTICE.md](NOTICE.md)。
- [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) 使用 LGPL-2.1，相关许可随工程保留。
- 其余第三方许可见源码和应用内“开源许可”。
