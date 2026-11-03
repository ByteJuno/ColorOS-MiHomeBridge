# 宿主契约（由字节码取证，非猜测）

目标固件：`PLK110_17.0.0.105(CN01)`，SystemUI `17.99.02`。
取证方式：`scripts/dump_host_dex.ps1` 拉取宿主 APK 后 `dexdump -d` 反汇编，原始输出在 `docs/host-dex/`
（该目录被 `.gitignore` 排除，不含在发布源码里；按下面命令自行复现）。
本文只记录**已验证**的形状；没有证据的字段一律标为未知。

## PanelDevice：21 个构造参数的真实含义

`scripts/check_firmware.py` 之类的签名检查只能证明方法存在。`<init>` 的 `iput` 顺序才能确定参数语义：

| # | 类型 | 字段 | 说明 |
|---|------|------|------|
| 1 | String | `deviceId` | 我们注入 `coloros_mihome:<controlId>` |
| 2 | DeviceType | `deviceType` | OPPO 设备枚举，见下 |
| 3 | Integer | `secondaryDeviceType` | 图标细分，可空 |
| 4 | List | `batteryList` | **元素是 `BatteryInfo`**，驱动电量环 |
| 5 | Map | `extraMap` | 只在 `equals`/`hashCode`/`toString` 内被读，无渲染作用 |
| 6 | String | `name` | 卡片标题 |
| 7 | DeviceStatus | `deviceStatus` | 主状态 / 副状态 / 事件图标类型 |
| 8 | String | `iconUri` | 非空时优先走远程图标，跳过内置 res id |
| 9 | int | `deviceState` | 1 正常 / 2 inactive / 3 不可用，其它值宿主只打 warning 日志 |
| 10 | List | `clickEvents` | **元素是 `ClickEvent`**，宿主自己的点击通道 |
| 11 | DeviceItemType | `deviceItemType` | 只有 `PACKER` / `SINGLE` / `TEST` |
| 12 | ConnectState | `connectState` | `ON_LINE` / `OFF_LINE`，卡片右上圆点 |
| 13 | SynergyCoordinationState | `coordinationType` | 互联状态 |
| 14 | int | `squareIconInUseResId` | 方形卡片选中态 |
| 15 | int | `squareIconNotInUseResId` | 方形卡片未选中态 |
| 16 | int | `rectangleIconInUseResId` | 长方形卡片选中态 |
| 17 | int | `rectangleIconNotInUseResId` | 长方形卡片未选中态 |
| 18 | int | `classicDefaultIconResId` | 经典样式兜底 |
| 19 | int | `squareIconResId` | 方形卡片默认 |
| 20 | int | `rectangleIconResId` | 长方形卡片默认 |
| 21 | Boolean | `isMultiBattery` | 双耳 / 多电池图标分支 |

`QuickCardDeviceData` 才是 27 参数那个类，被主列表（MyDevices）使用，与 `PanelDevice` 不是同一个契约，别把参数顺序混用。

## deviceState 的语义（SquareDeviceCardView 反编译确认）

`SquareDeviceCardView` 的 `bindDeviceState` 按值分支：

- `1` → 正常背景。
- `2` → 走 `status_square_card_bg_inactive`，即「已关闭」。
- `3` → `setUnusableDeviceItemBgAndFontColor`，即「不可用」——原生灰底 + 灰字。
- 其它 → `PluginLog.w("don't define this state:" + state)`。

结论：本模块 `ready && on → 1`、`ready && !on → 2`、`!ready → 3` 的映射与宿主一致，离线米家卡片本来就在吃原生灰化样式。

## DeviceStatus 与原生警告图标

`DeviceStatus(String mainStatus, String subStatus, Integer eventMessageType)`，第三个参数不是电量也不是颜色：

- `RectangleDeviceCardView` 与 `ui.view.d`：`eventMessageType != null && != 1` 时，在状态文字旁显示
  `drawable/icon_warning`（资源表实测 `0x7f080fe4 = drawable/icon_warning`）；`null` 或 `1` 时 `GONE`。
- `SquareDeviceCardView` **不读**这个字段，所以控制中心的小卡片不会出现警告图标。

本模块只对「真的有问题」的状态置 `2`（超时 / 失败 / 已移除 / 不可用），执行中与「已发送」保持 `null`，
见 `DeviceSpacePolicy.eventType`。

## batteryList / BatteryInfo

`BatteryInfo(BatteryType, int value, boolean charge)`，`BatteryType` 枚举为 `SINGLE` / `LEFT` / `RIGHT` / `BOX` / `BINAURAL`。
`ViewExtKt.bindDeviceIcon` 先调 `BatteryInfoKt.convertSeparateBatteryInfo(list, boolean)`，再按左/右/盒分支选图标；
卡片本体直接读 `BatteryInfo.getValue()` 画环与数字。

米家侧唯一可用的电量来源是 `Control.getStatusText()`。实测本机三台设备的状态文字分别是
`连接设备数 3`、`设备不可用`、`09:49`，**没有电量**。因此 `DeviceSpacePolicy.batteryPercent`
只在出现「电量 / 电池 + 数字 + %」时才填 `batteryList`，命中即出原生电量环，未命中就留空，不留假数据。

注意：亮度、音量也用百分比，所以必须带「电量 / 电池」关键词才认，否则会把「亮度 30%」当成电量 30%。

## clickEvents：宿主自带的点击通道（本模块未采用）

`ClickEvent(String type, String packageName, String action, int flags, String dataUri, String uri, String method, List<ClickParam> params)`。

`ui.adapter.g$$ExternalSyntheticLambda0.run()` 逐条消费：

- `type == "activity"`：`Intent(action).setPackage(packageName).setFlags(flags)`，再按 `params` 的
  `INT` / `Boolean` / `String` 格式 `putExtra`，然后 `startActivity`。
- 其它 `type`：交给 `ItemClickProcessor`，构造 `Bundle` 后走 `com.oplus.deviceplugin.sdk.port.a`
  的 `(Uri, method, Bundle)`——那是 OPPO 自己的设备 provider，外部模块注册不进去。

结论：走 `activity` 型可以拿到宿主原生的按下动画，但只能「打开某个界面」，
拿不到「点击即开关」。当前实现用自己的 `setOnClickListener` 做开关 + 长按详情，交互更贴近原生卡片，
所以这个通道留空不填。**不要**同时填 `clickEvents` 又挂自己的 listener，否则一次点击会触发两个动作。

## 原生图标资源

SystemUI 资源表里有 35 个 `drawable/device_card_<deviceType 小写>`（`device_card_lamp`、`device_card_camera`、
`device_card_smart_electric_fan` …）加 `device_card_place_holder`。

`ViewExtKt.bindDeviceIcon` 先调 `RemoteDeviceIconLoaderKt.tryBindRemoteDeviceIcon`，
**只有 `iconUri` 为空**时才回落到 `classicDefaultIconResId` / `squareIconResId` / `rectangleIconResId` 这些参数。
本模块现在传空 `iconUri`、用米家真实图标覆盖 drawable；如果要 100% 原生图标，改传 res id 且不再覆盖即可。

## 做不到的事（有证据）

| 需求 | 结论 | 证据 |
|------|------|------|
| 小卡片内嵌原生开关 / 滑块 | 不可能 | `SquareDeviceCardView` 里没有 Switch/SeekBar，也不读 `secondaryDeviceType`、`DeviceItemType` |
| SDK 专用场景卡片类型 | SDK 中没有专用枚举；模块现通过 Stateless 模板提供场景按钮 | `DeviceItemType` 只有 `PACKER` / `SINGLE` / `TEST` |
| 控制中心小卡片的房间分组 | SDK 面板结构不支持；MyDevices 主列表可用原生适配器标题分组 | `PanelInfo(PanelState, List<PanelDevice>)` 只有单一列表 |
| 让宿主代发米家命令 | 不可能 | provider 端口 `port.a` 只连 OPPO 自己的设备服务 |
| 类型化图标（灯 / 空调 / 风扇） | 本机样本未回传具体类型 | 三台样本的 `Control.getDeviceType()` 均为 `21`；其他设备以实际回传为准 |

这些不是实现难度问题，是宿主数据面与 SDK 的边界。写进 `docs/roadmap.md` 免得反复猜。

## 复现命令

```powershell
# 默认：设备空间那 8 个类，输出到 docs\host-dex\（已被 .gitignore 排除）
.\scripts\dump_host_dex.ps1 -Package com.android.systemui

# 加主列表侧的 QuickCardDeviceData / HomeCardView / MyDevices 类
.\scripts\dump_host_dex.ps1 -Package com.android.systemui -All

# 全量 SDK：先从 dex 字符串池收集类描述符，再逐类反汇编
.\scripts\dump_host_dex.ps1 -Package com.android.systemui `
    -ClassesFile .local\classes-plugin-all.txt -OutputDir .local\host-dex-all
```

脚本用 root 从机器上把 APK 拉到 `.local\apk\`（第二次起走缓存，SystemUI 有 98 MB），
只解出 `classes*.dex`，再用 `dexdump -d` 流式过滤出目标类的字节码。
注意 `powershell.exe -File` 不能传数组，所以多类场景用 `-ClassesFile`（一行一个类描述符）。
