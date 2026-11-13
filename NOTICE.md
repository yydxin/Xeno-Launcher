# NOTICE

XenoLauncher（包名 `io.github.yydxin.xenolauncher`）是基于 **ZalithLauncher2** 修改的非官方版本。

## 上游归属

- 上游项目：https://github.com/ZalithLauncher/ZalithLauncher2
- 上游作者：MovTery 及 ZalithLauncher2 贡献者
- 上游许可证：GNU General Public License v3.0；保留GPL原文与[上游随附副本](licenses/UPSTREAM-GPL-3.0.txt)，附加条款和第三方归属见[上游许可说明](docs/UPSTREAM_NOTICES.md)
- 启动核心来源于 PojavLauncher（GPL-3.0）

本项目保留上游版权声明、LICENSE 与第三方许可文本；未移除、未替换上游署名。

## 本项目修改概述

- 品牌：应用名、包名、图标、更新服务改为 XenoLauncher 独立维护；Kotlin 命名空间保留 `com.movtery.zalithlauncher` 以尊重上游结构。
- 新增：AI 助手（服务商配置、Keystore 加密、流式对话、思考过程、工具调用、日志诊断、模组翻译）。
- 新增：MobileGlues 渲染器与设置页。
- 新增：整合包来源记录、增量更新、备份/回滚与更新记录。
- 新增：自有 GitHub Releases 更新检查、SHA-256/签名校验。
- 调整：移除启动器上游更新接口调用；保留上游署名链接。
- 移除：debug 测试版提示卡（不影响“非官方修改版”标注）。

## 非官方声明

本项目与 ZalithLauncher / ZalithLauncher2 官方、MovTery 无隶属或授权关系。应用内保留显著“非官方修改版”标注。

## 第三方组件

- MobileGlues：LGPL-2.1，动态库 `lib/arm64-v8a/libmobileglues.so`，许可证全文见 `ZalithLauncher/src/main/res/raw/mobileglues_license.txt`。
- 其余组件见应用内「设置 → 关于 → 开源许可」。
