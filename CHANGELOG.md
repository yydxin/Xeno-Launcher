# Changelog

## 1.0.2

液态玻璃覆盖修复（versionCode 10002）。

- 统一玻璃入口：GlassSurface/GlassCard、全套按钮与图标按钮、TopAppBar/侧栏/Snackbar/FAB、Chip/SegmentedButton、Dialog/AlertDialog/DropdownMenu/ExposedDropdownMenu/ModalBottomSheet/Tooltip、Toast。
- 首页 CardGrid 注入统一 GlassCard；拖动/缩放位置变化时只重绘玻璃消费者，不重建 backdrop。
- 独立窗口改用纯壁纸快照与窗口本地 backdrop，不再整页 PixelCopy；文件管理补壁纸层。
- 玻璃范围新增“按钮”开关，五项默认全开；栏内项目避免二次折射。
- 版本卡信息去重：名称 + 版本/加载器两级展示。
- 构建：arm64 release、R8、原签名；未做真机视觉验收。

## 1.0.0 (2026-10-28)

首个 XenoLauncher 正式版，基于 ZalithLauncher2 2.7.2 的非官方修改版。

### 新增 / 完善

- AI 助手：服务商单一数据源、保存后回读校验、Keystore 加密失败可见、测试草稿/已保存两条路径、模型列表拉取、思考过程折叠、工具调用与日志诊断。
- AI 协议：Gson 空值安全解析、SSE 注释/`[DONE]`/空 choices/`usage: null`/工具分片、HTTP 错误体摘要、URL 拼接去重。
- DeepSeek：按官方文档使用 `deepseek-flash` / `deepseek-v4-pro`（`deepseek-v4-flash` 仅为旧别名）；思考模式默认开启。
- 自有更新：GitHub Releases API、302 回退、语义化版本比较、预览版开关、SHA-256 与包名/版本/签名校验、镜像前缀。
- 品牌：XenoLauncher 1.0.0（versionCode 10000）、独立包名 `io.github.yydxin.xenolauncher`、新自适应/单色/通知/旧版图标。
- 签名：独立 release keystore 与 GitHub Actions 发布流程；缺少签名配置时 release 构建明确失败。
- 移除 debug 测试版提示卡；“非官方修改版”标注保留。

### 已知限制

- 真机升级安装、AI 联网、MobileGlues 渲染、整合包更新需在设备上回归。
- GitHub Actions 构建使用通用 APK，未在本次环境中实际运行。
