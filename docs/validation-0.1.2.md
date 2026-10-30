# 0.1.2-preview 验证记录

日期：2026-10-29。设备：一加 15 / `PLK110_17.0.0.105(CN01)`，Android 17，API 37。米家 `11.8.707`，SystemUI `17.99.02`，LSPosed Manager `2.1.1-it`。

## 本次结果

| 检查 | 结果 |
| --- | --- |
| 宿主 APK 的定义及方法签名 | 15 项通过，见 `baseline-20261029.json` |
| JVM 测试 | 11 项，0 失败 / 0 错误 |
| Debug Lint | 0 错误，13 条警告 |
| Release 构建 | AGP 9.4.1 / Gradle 9.7.1 / JDK 21 / SDK 37，通过 |
| APK 签名 | 本项目专用 RSA 3072 密钥；v2 验证通过，证书与前版相同 |
| 真机安装 | 已覆盖安装 `0.1.2-preview` / code 3 |
| 两项作用域 | LSPosed 日志确认 SystemUI 与米家主进程、widgetControl 进程加载成功 |
| 米家服务 | 已出现在 `ControlsProviderService` 系统查询结果中 |
| 原生提供商列表 | 实机列表可见米家 |
| 用户设备选择 | 用户保存 3 台设备；原生控制器的已选结构中已读到这 3 项 |
| 控制中心入口 | 原生管线已生成 `OplusDeviceControlsTile`，状态 `state=2`、未被策略禁用 |
| 配置保持 | 标准及分离式两套列表均保留 `controls`；标准列表其他条目与添加前一致 |

证书 SHA-256：`e1631c0dcb618209c1e81f74c4b03a198a457314bb2141c3931d903d1cab84c0`。APK 与源码包散列见 `dist/SHA256SUMS.txt`。

本机证据位于 `.local/verification/`：`post-reboot-settings.png`、`native-pipeline-add.png`、`native-pipeline-settings.json`、`acceptance-0.1.2.json`。截图及设备名称不打入源码包。

## 实机修复

`0.1.0` 的后台广播被 ColorOS 以 `DEFER_BY_OPLUS / FROZEN` 延后，造成设置页超时。现在米家服务就绪时直接请求 SystemUI 添加入口；其余指令设置为前台广播并关闭延后投递。

直接修改 `sysui_qs_tiles` 会被 ColorOS 的快捷开关管线覆盖。现在调用原生 `CurrentTilesInteractorImpl.addTile/removeTiles`，读取当前实际列表并等待修改生效。恢复只删除本模块新增的入口，不回写整份旧列表。

原生页面的动画观察器会先把内容设为透明；缺少 `extra_animate=true` 时出现黑色空页。现在启动时传入与系统原生快捷开关相同的参数。

本次更新后已重载 SystemUI，使宿主加载新版代码。手机反向 ADB 经现有 Tailscale 通道恢复；过期的 Windows 接收端 PID 记录已另存后重新启动，没有终止占用旧 PID 的其他进程。

## 验证边界

真实家电开关、场景执行、锁屏操作与完整恢复尚未做实机验收。米家原应用的 PVE 插座详情显示设备离线，不能将该设备无法控制归因于桥接模块。原生面板在不同主题下的观感仍待验证。

本版本为测试版，未发布到 GitHub 或 LSPosed 商店。
