# 0.2.1-preview 验证记录

日期：2026-10-29。一加 15 / `PLK110_17.0.0.105(CN01)`，Android 17 / API 37；SystemUI `17.99.02`，米家 `11.8.707`。

## 构建与交付

- 版本 `0.2.1-preview`，versionCode `5`，签名证书与 0.1.2 / 0.2.0 相同。
- 17 项 JVM 测试通过；Debug Lint 0 错误、24 警告；Release 构建和 APK v2 签名验证通过。
- 原生接口仍使用同一固件的 44 项已通过检查，见 `native-baseline-20261029.json`。
- APK 散列及检查状态见 `build-verification-0.2.1.json`。
- 已通过家庭网络反向 ADB 覆盖安装 0.2.1，手机包管理器确认 versionCode 5；仅重载 SystemUI，未重启手机。

## 本轮实机发现

21:17 安装了散列以 `81ea358c` 开头的最终 0.2.0 APK。原生 4×1 设备空间显示三张米家卡片与系统入口，三台设备均使用米家提供的实际图标；不可用插座置灰，点击显示“设备离线或不可用，可长按打开米家详情”。未向插座发送通断电指令。

长按网关时，系统日志明确记录 Android 17 的 `Background activity launch blocked` / `BAL_BLOCK`。0.2.1 使用 `ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`，仅在既有的解锁、当前用户、面板可见及米家 PendingIntent 来源检查通过后发起详情；随后收起控制中心。该选择参考 [Android 活动启动安全文档](https://developer.android.com/guide/components/activities/secure-bal)。

0.2.1 已安装并确认 SystemUI Hook 加载，但手机自动锁屏后暂未完成详情跳转与恢复复测。不能把修复实现、构建通过或安装成功等同于交互验收。

截图与日志仅保存在 `.local/verification/`；它们含个人设备信息，不放入源码包。

## 未完成的实机验证

- 0.2.1 长按详情、重新展开、完整面板和关闭 / 完整恢复。
- 真实家电通断电与场景执行、目标状态确认、超时反馈。
- 锁屏点击拦截、系统用户切换、其他主题及经典控制中心模式。

## 布局与回退

较早测试通过系统编辑页把设备空间从 2×1 改为 4×1；系统因此调整了原响铃卡片占位。模块未写入该布局设置，不宣称所有控制中心布局保持不变。

保留 `dist/ColorOS-MiHomeBridge-0.1.2-preview.apk`、`dist/ColorOS-MiHomeBridge-0.2.0-preview.apk` 与 `.local/rollback-0.1.2/`。0.2.0 原交付时的验证记录保存在 `validation-0.2.0.md`；其后新增的测试状态见 `build-verification-0.2.0.json`。

未发布到 GitHub 或 LSPosed 商店。
