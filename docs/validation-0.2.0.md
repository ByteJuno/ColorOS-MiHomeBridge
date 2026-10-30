# 0.2.0-preview 验证记录

日期：2026-10-29。一加 15 / `PLK110_17.0.0.105(CN01)`，Android 17 / API 37；SystemUI `17.99.02`，米家 `11.8.707`。

## 最终交付构建

- 版本：`0.2.0-preview`，versionCode `4`。
- 17 项 JVM 测试，覆盖入口添加与恢复、枚举策略、卡片排序 / 去重 / 清理、锁屏与用户条件、旧状态不得确认新操作、布局容量。
- 44 项宿主方法签名检查，见 `native-baseline-20261029.json`。
- Release 构建及 Debug Lint 通过；精确数量、APK 散列见 `build-verification-0.2.0.json`。
- 签名证书与 0.1.2 一致：SHA-256 `e1631c0dcb618209c1e81f74c4b03a198a457314bb2141c3931d903d1cab84c0`。

## 已完成的实机检查

18:08 安装本轮中间构建并重载 SystemUI，确认宿主加载成功。设备空间收到米家保存的三台设备：两项为米家报告的可用状态，一项为不可用状态。原生 `FixedSeparateModelPanel` / `SquareDeviceCardView` 已接收米家 `PanelDevice`，实际控制中心显示相应三张图标卡片；原 OPPO 设备数据仍在合并结果中。

系统原先使用 2×1 设备空间，本次从系统编辑页切换到宽布局，实机显示三张设备卡片和原生入口。系统在切换布局时调整了原响铃卡片占位；模块代码未写入该布局设置。布局仍由系统编辑页管理。

本机截图在 `.local/verification/native-first.png`、`native-after-settings.png`、`native-wide.png`；设置页为 `native-settings.png`。这些图片含个人设备信息，不放入源码包。

## 未完成的实机验证

用户回家后选择先交付 APK，USB 及原反向调试通道已断开。因此最终包新增的米家原图标、布局容量限制、异步目标状态确认修复以及冷启动初始化调整，均只完成本地构建 / 测试 / 签名检查，尚未装入手机复测。手机上保留的是本轮较早的 `0.2.0-preview` 构建；交付包仍可用相同签名覆盖安装。

真实家电开关、场景执行、长按跳转、完整恢复、锁屏、系统用户切换、其他主题及经典控制中心模式均尚未完成实机验收。源码中有相应处理不能等同于实机通过。

调试期间未向真实家电发送开关或场景命令。原 PVE 插座由米家服务返回不可用，桥接不会伪造为在线。

## 回退材料

- 旧 APK：`dist/ColorOS-MiHomeBridge-0.1.2-preview.apk`。
- 改动前源码：`.local/rollback-0.1.2/source-before-native.zip`。
- 旧版验证记录：`validation-0.1.2.md`。
- 本版源码及散列：`dist/ColorOS-MiHomeBridge-0.2.0-preview-source.zip`、`dist/SHA256SUMS.txt`。

未发布到 GitHub 或 LSPosed 商店。
