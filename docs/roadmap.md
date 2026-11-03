# 后续路线

宿主契约已经用字节码取证确认，结论见 [host-panel-contract.md](host-panel-contract.md)。
下面只留**还有可能做成**的事，被证据否掉的直接写进「已排除」，免得反复猜。

## 已做（0.5.0，包含 0.4.1）

- 状态文字旁的原生警告图标：`DeviceStatus.eventMessageType` 置 `2` 时宿主画 `drawable/icon_warning`。
  只对超时 / 失败 / 已移除 / 不可用生效，长方形卡片与主列表可见，控制中心小卡片不支持（宿主不读该字段）。
- 电量环入口：`PanelDevice.batteryList` 填 `BatteryInfo(SINGLE, n, false)`。
  米家目前不上报电量，所以解析器挂在 `DeviceSpacePolicy.batteryPercent`，状态文字出现「电量 / 电池 + %」时自动生效。
- `deviceState` 1/2/3 与宿主语义对齐并写进测试，离线卡片吃宿主原生灰化样式。
- 独立设备订阅、自动重连与过期回调隔离，页面暂离及连接异常时保留卡片和图标。
- MyDevices 原生主列表通过适配器原生标题按房间分组，支持隐藏、顺序与前后位置设置；控制中心小卡片仍使用单一列表。
- 原生大卡片电源按钮与快捷控制弹窗，按服务模板提供开关、数值调节或场景执行；真实家电效果尚未验收。
- 状态推送、图像指纹确认、有界图像后台队列、慢速心跳，以及三个宿主的独立诊断。

## 1. 原生图标通道（可直接做，缺一个决策）

宿主在 `iconUri` 为空时才用 `PanelDevice` 里的 7 个 icon res id，而 SystemUI 里就有
`drawable/device_card_<deviceType 小写>` 共 35 个（`device_card_lamp`、`device_card_camera`、
`device_card_smart_electric_fan` …）加 `device_card_place_holder`。

现状：本模块传空 `iconUri`，再用米家真实图标覆盖 `ImageView`，所以卡片显示的是米家实拍图。

要做的是二选一（或做成设置项）：继续用米家实拍图，还是改传 res id 让宿主画自己的图标。
后者的拦路虎是类型：本机三台样本的 `Control.getDeviceType()` 均为 `21`，`nativeType()` 落到 `OTHER`，
只能拿到 `device_card_place_holder`。要按类型出图标，必须先拿到米家的设备模型串（`urn:miot-spec-v2:...`），
也就是得在米家进程里取数据，超出当前 Controls 桥的范围。

## 2. 主列表的状态环与副状态

`QuickCardDeviceData` 有 4 个 `BatteryInfo`、`StatusRing`、`DeviceAbnormalState`，当前传 `null` / `OFFLINE` / `NONE`。
和 `PanelDevice.batteryList` 是同一类数据，同样卡在「米家不给电量」。等上面那条解析器真命中过再动这里，
不要为了好看造数据。

## 3. 在米家进程里取真实设备数据

这是唯一能突破当前天花板的路线，也是最重的：LSPosed 作用域加 `com.xiaomi.smarthome`，
hook 它的设备列表模型，把电量、温度、模式等真实属性喂给原生卡片。
风险是米家混淆严重、版本一变就废，而且要把现在的「标准 Controls 桥」扩成「米家内部桥」。
在拿到至少一份米家设备模型的稳定结构前不动手。

## 4. 宿主侧文案资源化

宿主进程里可见的状态文字、Toast、无障碍描述仍是硬编码中文（`NativeDeviceSpace.statusText`、`MiHomeSession` 的提示等）。
这些字符串在 SystemUI / MyDevices 进程里执行，`context.getString()` 取不到本模块的资源，
需要先用 `createPackageContext(模块包名)` 建一个模块资源句柄，再统一外置到 `strings.xml` 并补英文。
当前只在中文环境使用，优先级低于上面三项。

## 5. 版本适配面

当前按 `API >= 37` 加载，接口形状只对 `PLK110_17.0.0.105` 的一组版本验证过。
ColorOS 17.x 小版本升级时先跑 `scripts/check_firmware.py` 重跑基线，再用
`scripts/dump_host_dex.ps1` 复核 `PanelDevice` / `DeviceStatus` 的构造形状，最后决定是否加兼容分支。
三个注入域已经独立容错，最坏情况是某个功能降级而不是整体失效。

## 已排除（有证据，不要再试）

| 想法 | 为什么不行 |
|------|-----------|
| 小卡片内嵌原生开关 / 滑块 | `SquareDeviceCardView` 没有 Switch/SeekBar，不读 `secondaryDeviceType`、`DeviceItemType` |
| SDK 专用场景卡片类型 | `DeviceItemType` 只有 `PACKER` / `SINGLE` / `TEST`；场景操作已通过 Stateless 模板和模块快捷控件接入 |
| 控制中心小卡片的房间分组 | `PanelInfo(PanelState, List)` 只有单一列表；MyDevices 主列表已通过原生适配器标题实现 |
| 让宿主代发米家命令 | `ClickEvent` 非 `activity` 型走 `port.a`，只连 OPPO 自己的设备 provider |
| 用 `ClickEvent(type="activity")` 替代点击 | 只能打开界面，拿不到「点击即开关」，交互反而更差 |
| 填 `extraMap` 改善显示 | 该字段只在 `equals`/`hashCode`/`toString` 里被读 |
