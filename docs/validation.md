# 0.3.0-preview 验证记录

验证日期：2026-10-30。一加 15 / PLK110_17.0.0.105(CN01)，Android 17 / API 37；SystemUI 17.99.02、设备空间 17.25.10、米家 11.9.625。

## 构建

- 版本 0.3.0-preview，versionCode 7；APK 2,219,003 字节。
- SHA-256：03488bb5ba6471aad3acf2511bc36e9a76ea4da65d1e4ec6136b71d1596fe83b；已核对手机安装的 base.apk 与交付包一致。
- 29 项 JVM 测试通过，0 失败、错误或跳过；覆盖跨用户/伪造身份/延迟请求拒绝、列表去重与关闭恢复、页面租约过期及持久化排序过滤。
- Debug Lint 0 错误、28 警告，Release 构建通过。签名证书 SHA-256 延续旧版 e1631c0dcb618209c1e81f74c4b03a198a457314bb2141c3931d903d1cab84c0。
- 60 项宿主方法签名检查通过，含 MyDevices 列表、27 参数 DTO 构造函数、卡片/图片绑定及排序回调。见 native-center-baseline-20261030.json。

## 实机检查

1. LSPosed 补选设备空间作用域，MyDevices 主进程与 :cards 进程的钩子加载成功。
2. 原生 DeviceCardHomeActivity 显示 7 个设备：原有平板、两副耳机、电脑共 4 张卡片保留，追加已选的 3 张米家卡片。
3. 米家卡片显示官方图标、名称、原生状态灯和状态文字；网关连接数、插座不可用、智能门锁时间均能读到。两台在线为绿灯，不可用插座为灰灯。
4. 点击及长按网关大卡片均进入对应米家 PluginRNActivity 详情；返回后重新订阅，无重复卡片。
5. 关闭桥接后主列表回到原有 4 台，下拉设备空间回到原有手表/耳机；重新开启后 3 台米家设备恢复。
6. 模块内「打开设备空间」经签名命令交给 SystemUI 打开成功，解决普通应用直接启动受保护页面被拒绝的问题。
7. 完整设备控制面板正常读取三台设备状态；其上展开下拉卡片再收起，完整面板状态保留。由完整面板切回设备空间，主列表再次加载三台米家设备和图标。
8. 最后一次回调传输改动后再次覆盖安装，设置状态响应、主列表快照、图标及详情回调复测通过。结束时保留启用状态和原来的三台选择。

私有截图位于 .local/verification/，如 home-center-mihome-cards.jpg、home-center-gateway-detail.jpg、home-center-longpress-detail.jpg、home-center-disabled-originals.jpg、home-center-disabled-shade.jpg、home-center-final-full-panel.jpg、home-center-final-native-over-full.jpg、home-center-final-full-after-native.jpg 和 home-center-final-transport-cards.jpg，不进入源码包。

## 本轮问题与实现范围

原生列表使用 QuickCardDeviceData 相等性判断是否重新绑定。首批状态先于图片到达时，相同图片地址会保留通用图标。本版把图片内容指纹放入临时图标地址，图片到达后重新绑定。图片只保存在内存，不写入设备数据库或公共目录。

首次覆盖安装、SystemUI 仍加载旧 APK 时，11:09 日志出现一次读取新版匿名 ResultReceiver 类失败。最终通过 Parcel 转成系统基类后发送，接收方无需加载发送方匿名类，并把回调读取放入异常处理。新设置页、MyDevices 与已经运行的 SystemUI 接收端再次通信通过。

MyDevices 没有直接绑定 Controls 的权限，状态由现有 SystemUI 会话提供。独立通道校验 Android 提供的发送 UID、包名、当前用户和 2.5 秒有效期；只支持列表快照、详情和关闭。详情 PendingIntent 从当前可见的 MyDevices 页面发起，保留解锁与用户校验。页面暂停释放租约，异常退出最多约 6 秒到期。每张图不超过 16 KiB，总图像不超过 192 KiB，最多追加 32 行。

保留 0.2.2 的订阅切换与回调标识修复。米家「完全允许后台行为」设置继续保留，以避免 Hans 冻结；未修改全局冻结策略。原屏幕超时为 1800000 毫秒。

## 验证边界与回退

大卡片提供原生列表展示和进入米家详情，未内嵌完整属性编辑器。未向家电发送开关或场景命令，PVE 插座未做通断电测试。锁屏交互、系统用户切换、真实拖拽排序、经典控制中心及其他固件未完成实机验收。

本轮验证了开关关闭/重开，未重复执行会清除选择的完整恢复。0.2.2 完整恢复、记录清理与重新选择证据见 validation-0.2.2.md。上一版 APK/源码另备份于 .local/rollback-0.2.2/。未发布到 GitHub 或应用商店。
