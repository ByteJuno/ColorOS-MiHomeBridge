# 0.2.2-preview 验证记录

验证日期：2026-10-29 至 2026-10-30。一加 15 / `PLK110_17.0.0.105(CN01)`，Android 17 / API 37；SystemUI `17.99.02`，当前米家 `11.9.625`。

## 最终交付构建

- 版本 `0.2.2-preview`，versionCode `6`；最终 APK 为 2,188,163 字节。
- SHA-256：`521b0943dccf2d8ab66f08f0216b76fbdd84f28c2dffd3601e8608acd28e0671`。已直接校验手机安装的 `base.apk` 与交付包一致。
- 23 项 JVM 测试通过，0 失败 / 错误。新增设置版本和请求有效时间回归，覆盖旧状态不得覆盖新开关、过期恢复请求拒绝等情况。
- Debug Lint 0 错误、28 警告；Release 构建、APK v2 签名验证通过。
- 签名证书 SHA-256：`e1631c0dcb618209c1e81f74c4b03a198a457314bb2141c3931d903d1cab84c0`，与旧版本一致。
- 使用当前米家 APK 和实际 SystemUI APK 完成 46 项宿主方法签名检查，见 `native-baseline-20261030.json`；原生回调标识与 Intent 字段类型另在 Hook 安装前检查。
- 最后一次包调整仅增加设置说明；14 个宿主业务类的编译散列与切换测试时一致。最终包安装后又完成恢复、重新选择和双面板加载复测。

精确机器记录见 `build-verification-0.2.2.json`。APK、源码包及两者散列见 `dist/`。

## 已完成的实机检查

1. 原生 4×1 设备空间显示原来选择的三台米家设备，使用米家提供的图标；不可用插座置灰，点击显示离线 / 不可用提示。
2. 长按设备卡片后自动收起控制中心，进入米家对应设备详情。原 Android 17 `BAL_BLOCK` 已复测修复。
3. 完整面板读取路由器的连接数、插座的不可用状态和门铃的时间状态。
4. 在完整面板上展开原生设备空间，再收起，两个界面均保持设备状态和图标。关闭完整面板后再打开设备空间也正常。
5. 关闭桥接时恢复系统原有手表 / 耳机卡片；最终包执行完整恢复后，米家控制服务回到原来的禁用状态，模块添加的独立入口移除，两份恢复记录均为空。
6. 恢复后重新添加入口，按原顺序重新选择三台设备，完整面板和原生设备空间再次加载成功。测试结束保留启用状态和这三台设备。
7. 最终验证期间未在崩溃缓冲中发现新增 SystemUI 崩溃；未发现新增桥接绑定、图标或回调标识错误。

私有证据位于 `.local/verification/`，例如 `home-final-full-before.jpg`、`home-final-native-over-full.jpg`、`home-final-full-after.jpg`、`home-final-offline-after-full.jpg`、`home-delivered-restored.jpg`、`home-delivered-original-native.jpg`、`home-delivered-full-ready.jpg` 和 `home-delivered-native-ready.jpg`。含个人设备信息，不放入源码包。

## 本轮修复与设备设置

米家进程被系统 Hans 冻结时，完整面板停在加载中，恢复广播也会延迟。本机读取到米家主进程及控制服务的 `cgroup.freeze=1`。将米家耗电管理从「智能优化后台运行」改为「完全允许后台行为」并确认后，进程解除冻结，恢复请求与设备订阅重新响应。该项设置为正常使用保留，会允许米家后台运行，可能增加耗电。

旧进程还曾在设置页已关闭时读到旧的已启用值。新请求使用原签名权限，增加设置版本及本机时间有效期；旧版本与超时请求不再执行修改。冻结后延迟到达的恢复请求已观察到被拒绝。实现参考了 [LSPosed 远程设置实现](https://raw.githubusercontent.com/LSPosed/LSPosed/master/core/src/main/java/org/lsposed/lspd/impl/LSPosedRemotePreferences.java) 的缓存 / 回调机制。

米家服务的缓存绑定回调标识会影响独立连接。模块为同一用户、同一米家服务统一标识，订阅和操作回调仍由各自连接管理；其他提供者和其他用户保持分离。依据为 [Android ControlsProviderService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/service/controls/ControlsProviderService.java) 与本机 SystemUI 方法形状，最终以两种面板的实机切换结果验证。

测试期间仅重载 SystemUI 和米家进程，未重启手机。原自动息屏时间 `1800000` 毫秒已恢复并核对。没有修改全局冻结策略。

## 验证边界与回退

未向真实家电发送开关或场景命令，PVE 插座没有做通断电测试。目标状态确认、场景执行、超时操作反馈、锁屏点击拦截、系统用户切换、经典控制中心及其他固件仍未完成实机验收。不能把界面检查和单元测试等同于这些场景通过。

完整恢复会让系统禁用原本被关闭的米家控制服务；系统可能因此清除米家的已选设备。设置页已补充提醒，本次测试已重新选回原来的三台设备。米家后台活动设置和设备空间尺寸由系统单独保存。

较早测试从系统编辑页把设备空间由 2×1 改为 4×1，系统调整过响铃卡片占位。本次不宣称所有控制中心布局未变化；模块通过系统接口只增删 `controls` 入口，不写回整份布局。

保留 `dist/ColorOS-MiHomeBridge-0.1.2-preview.apk`、`0.2.0-preview`、`0.2.1-preview` 及 `.local/rollback-0.1.2/`、`.local/rollback-0.2.1/`。旧验证记录单独保留。未发布到 GitHub 或 LSPosed 商店。
