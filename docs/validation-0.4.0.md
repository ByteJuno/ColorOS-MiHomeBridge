# 0.4.0-preview 验证记录

验证日期：2026-10-30。设备：一加 15 / PLK110，Android 17 / API 37；SystemUI 17.99.02、设备空间 17.25.10、米家 11.9.625。0.3.0 的完整实机记录见 `validation.md`。

## 构建

| 项 | 值 |
| --- | --- |
| JDK / Gradle / AGP | Temurin 25.0.4+7（语言级别 21）/ 9.7.1 / 9.4.1 |
| Android SDK | platform `android-37.0`，build-tools 37.0.0 |
| 版本 | `0.4.0-preview`，versionCode 8 |
| 交付包 | `dist/ColorOS-MiHomeBridge-0.4.0-preview.apk`，2,229,739 字节 |
| 签名 | 与 0.3.0 同一证书 `e1631c0dcb618209c1e81f74c4b03a198a457314bb2141c3931d903d1cab84c0`，覆盖安装无需卸载 |

- `:app:testDebugUnitTest`：43 项通过，0 失败（0.3.0 为 29 项）。
- `:app:lintDebug`：0 错误、27 警告（0.3.0 为 0 错误、28 警告）。
- Release 签名构建通过，`apksigner verify` 的 V2 签名证书与手机已装版本一致。

## 实机结果

覆盖安装 0.3.0 → 0.4.0-preview 成功，设置与设备选择保留。为加载新代码，本轮用 root 重载 `com.android.systemui`、`com.heytap.mydevices`、`com.xiaomi.smarthome`（等效于重启手机）。

1. **设置页**：分组卡片、设置行、分隔线、右侧开关与箭头、状态栏遮罩均正常；状态行实时读到「框架 LSPosed · 作用域已配置」「米家 11.9.625 · 设备服务已开启」「控制中心 入口已添加」「设备空间 原生卡片已加载，已选 3 台设备」。新版设置页与仍在运行的旧宿主代码互通正常（命令协议未变）。
2. **主列表**：设备空间显示 4 台原生 + 3 台米家 = 7 台，顺序为原生在前、米家在后（新版默认排序）。
3. **图标**：米家卡片显示官方图标（峻景阁网关、PVE 插座），非通用占位图。
4. **事件驱动**：打开页面后 400 毫秒内完成 3 次快照（首屏 1 次、图标到达 2 次），随后静默；下一次刷新间隔 15 秒，对应心跳。旧版同一时长内有约 13 次 1.2 秒轮询。
5. **控制中心那一条**：卡位容量实测为 3（`colspan=4, rowspan=1` → `limit=3`），合并后列表 6 台，注入卡片按「米家优先 + 可操作优先」排在前面；绑定日志确认卡片真的挂上了（`SquareDeviceCardView`，217×217，`vis=VISIBLE`，父容器 `LinearLayout`）。
6. 未出现崩溃；日志中无 `Cannot notify the device center`、无 `Device center push ignored` 于最终版本。

私有截图：`.local/verification/settings-0.4.0-*.png`、`device-space-0.4.0-icons.png`、`cc-final.png`，不进入源码包。

## 本轮实机抓到的三个问题（均为本版自引入，已修复并复测）

1. **图标不再自动到达**。去掉 1.2 秒轮询后，图标是异步解码的：首次快照必然不带图片，解码完成后没有任何人通知列表，米家卡片只剩通用占位图，直到 15 秒心跳。修复：`DeviceCenterHost` 在图片编码完成后调用 `NativeDeviceSpace.notifyCenter()`，走同一条推送通道。
2. **推送被自己的身份校验挡掉**。SystemUI 侧最初用 `sendBroadcast(intent)` 直发，接收侧 `getSentFromUid()` 读到 `-1`，`NativeDeviceCenter.fromSystemUi()` 判定不可信并丢弃（日志 `center push ignored: from=-1`）。修复：发送侧改为 `BroadcastOptions.setShareIdentityEnabled(true)`，与 MyDevices → SystemUI 方向原本的写法一致。
3. **控制中心里米家卡片消失**。本版一度把「米家在前 / 在后」做成全局统一规则且默认「在后」；主列表可以滚所以看不出问题，但控制中心那一条只有 1～3 个卡位并按容量裁剪，4 台 OPPO 设备先占满卡位，米家 3 台被整批裁掉。修复：控制中心固定「米家优先」，另加「可操作优先」，把离线设备沉到末尾；设置项只作用于主列表。

这三条都是静态检查和单测抓不到的，只有真机日志能暴露。

## 仍未验收

- 真实家电通断电、米家手动场景执行。
- 控制中心小卡片与完整面板在 0.4.0 下的操作（本轮只验证主列表与设置页）。
- 锁屏交互、系统用户切换、拖拽排序。
- 推送在长时间后台、米家被 Hans 冻结后的恢复表现。
- 15 秒心跳在真实使用中的耗电影响（仅确认了间隔，未做功耗测量）。

## 回退

- 首选：设置内关闭「启用米家桥接」，注入的卡片立即消失。
- 完整回退：设置内「关闭并恢复本模块的改动」，再从 LSPosed 禁用或卸载。
- `placement`、`tap` 是新增键，缺省即旧行为；回退到 0.3.0 不会残留异常状态。
