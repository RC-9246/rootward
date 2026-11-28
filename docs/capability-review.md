# 归根与同类电池工具的能力对照

公开资料核对日期为 2026 年 11 月 27 日，本轮适配于 11 月 28 日验证。以公开的产品功能页、项目源代码、系统 API 和实验论文为依据。下面分别讨论普通 Android 应用、ADB 模式的 Shizuku、已授权的 ACC、原厂固件和外接设备能做到什么。厂商公布了某项功能，只说明对应型号和系统有这条路径。归根各版本的具体测试范围见[版本验证记录](android-compatibility.md)，已有策略的实现细节见[功能说明](capabilities.md)。

## 从实际问题反推功能

| 问题 | 对照产品和证据 | 归根的取舍 |
| --- | --- | --- |
| 达到 75% 后，拔线再插会不会又开始充？ | AlDente 的示例中，80% 停充、70% 复充，拔线后在 74% 接回仍然等待。它也明确区分巡航与主动放电。[AlDente 的 Sailing Mode](https://apphousekitchen.com/aldente-overview/features/#sailing) | ACC 插件把本轮阶段持久化。无 root 版也记忆阶段，具体动作取决于可写的驱动或已连接的充电插座。纯提醒模式由人拔线；插座切断外部电源会令手机使用电池。手机若能由适配器直接供电，维持在上限附近也是合理结果。 |
| 到达复充线时，电池已经报告过冷或过压怎么办？ | Android 单独提供电池健康状态，能报告过冷、过热、过压、失效和其他故障。温度读数和可否充电来自不同信号。[BatteryManager](https://developer.android.com/reference/android/os/BatteryManager) | 这是本轮发现的执行缺口。旧版可显示故障名，但硬件巡航、外部插座和自动化的放行依据主要是温度和电量。1.4 的三个出口遇到明确故障会暂停。随后系统暂报未知仍保持暂停，重新报告正常后才按原电量与温控决定是否放行。 |
| 哪些停充、限速和旁路开关真的有效？ | ACC 提供电量、温度、部分设备上的电流、电压和 idle 控制；Battery Charge Limiter 同样依赖 root。Linux 驱动允许厂商仅实现部分属性，电流和电压需核对单位及测点。[ACC](https://github.com/VR-25/acc) · [Battery Charge Limiter](https://github.com/MuntashirAkon/BatteryChargeLimiter) · [Linux 电源属性](https://docs.kernel.org/power/power_supply_class.html) | ACC 版保留后端已有的设备适配。Shizuku 版只对已识别的内置电池和可用标准接口执行受限操作。增加某款手机的控制适配，需有该版本固件的写入、读回、恢复及电流实测。成功读回开关不能证明能量绕过了电池。 |
| 原厂手机无需 root，能否直接用硬件保护？ | Pixel 有固定上限和自适应充电，部分机型还会随老化调整电压和充电速度。Galaxy 有基础、自适应和最大保护。OxygenOS 的智能充电可根据夜间规律暂缓充满。Amply 公开列出了可由 ADB 授权或 Shizuku 操作的部分 Pixel、One UI 和 ColorOS/OxygenOS 版本。[Pixel](https://support.google.com/pixelphone/answer/6090612) · [Pixel 老化管理](https://support.google.com/pixelphone/answer/15738128) · [Galaxy](https://www.samsung.com/us/support/answer/ANS10002534/) · [OxygenOS 手册](https://service.oneplus.com/content/dam/support/user-manuals/common/OxygenOS_14.0_User_Manual.pdf) · [Amply](https://github.com/d4rken-org/amply) | 可通过原厂设置或可调用的 LineageOS 页面使用固件能力。Amply 表明还存在按 ROM 开发适配器的空间。开启原厂固定保护和支持任意 50%–75% 的上下限有不同语义，只有拿到对应 ROM 验证设置生效、升级和撤销，才能发布该机型的直接控制。 |
| 游戏时能否减少边充边玩的发热？ | Sony 的 HS 电源控制支持特定游戏场景。三星的游戏供电暂停有机型和充电器要求。部分 ASUS 手机提供缓充、限充和计划充电。[Sony HS](https://www.sony.com/electronics/support/articles/00264201) · [三星游戏供电](https://www.samsung.com/au/support/apps-services/pause-usb-pd-power-delivery-charging-when-gaming-feature-on-your-galaxy-device/) · [ASUS 电池养护](https://rog.asus.com/support/faq/1043101/) | 在支持的硬件上是真正有价值的降热手段。归根可比较游戏前后的电池端功率和温度；旁路开关仍须由该机的游戏模式或已验证驱动执行。USB 功率和电池端功率不能单独分解出 CPU、屏幕和其他外设各用了多少瓦。 |
| 手机失联、关机之后还能控制充电吗？ | Chargie 的硬件保护可依据功率在 App 离线时切断供电，另有分日计划、温控和电量分段充电曲线。Home Assistant 可以读取实体状态、调用开关服务；手机 Companion 也可发布电池传感器。[Chargie Android 指南](https://chargie.org/chargie-android-app-guide/) · [Home Assistant REST](https://developers.home-assistant.io/docs/api/rest/) · [Companion 传感器](https://companion.home-assistant.io/docs/core/sensors/) | 无 root 版已有 Home Assistant 开关和定向自动化接口。它读回状态，只自动恢复由归根确认切断的供电。若要求手机关机后自行恢复，超时规则必须驻在有电的插座或家庭控制器上。直接连接 Chargie 蓝牙硬件还需受支持的设备协议和实物验证。 |
| 出发前多充一点，还是每周准点充满？ | AlDente 能按天或星期重复执行动作，Top Up 可临时充到 100%。Chargie 利用所学充电曲线预测开始时刻。Amply 提供临时解除原厂保护及快捷设置入口。[AlDente 计划与 Top Up](https://apphousekitchen.com/aldente-overview/features/) · [Chargie](https://chargie.org/chargie-android-app-guide/) · [Amply](https://github.com/d4rken-org/amply) | 两版已有手动和单次定时提前补电，最高仍为用户设置的上限。每周重复、到点目标百分比、快捷设置磁贴都可做。前两者适合通勤且愿意改变上限的人，需要清楚交代时区、夏令时、错过窗口、学习样本不足和临时取消；磁贴只缩短操作路径。它们均不会改善现有严格巡航的停充准确性。 |
| 怎样分辨热、待机耗电和电池容量变化？ | AccuBattery 利用充入电量与百分比变化估计容量，并说明传感器误差与样本数限制。Battery Guru 提供屏幕活动、待机、深睡、充电记录及提醒。Android 的两个单调时钟分别包括和排除深睡。[AccuBattery](https://accubattery.zendesk.com/hc/en-us/articles/209507189-Tab-3-battery-health-screen) · [Battery Guru](https://paget96projects.com/applications/batteryguru) · [Android 时钟](https://developer.android.com/reference/android/os/SystemClock) | 无 root 版已有热暴露、离电用量、互动/非互动、分段容量估计和可导出历史。新版可在诊断中通过时钟差查看开机累计深睡，全程无需额外权限或唤醒设备。它描述整机自开机以来的状态，与“昨夜某个应用唤醒了几次”是不同问题。双电芯和缺测继续显示来源，不按机型名称盲目翻倍。 |
| 能看到健康百分比、出厂和首用日期吗？ | Batt 和 aBattery 读取部分系统或驱动提供的资料。AOSP Health HAL 定义生产日期、首次使用和估算健康度，制造商可以缺报。aBattery 也说明了如何使用满充与设计容量，以及哪些结果依赖厂商实现。[Batt 作者的设备记录](https://gitlab.com/narektor/batt/-/issues/8) · [aBattery 作者 FAQ](https://blog.linshen.me/posts/abattery-faq/) · [AOSP Health 数据结构](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/health/aidl/android/hardware/health/BatteryHealthData.aidl) | 归根能展示驱动满充/设计容量、循环次数及充电片段估算；1.4 在唯一内置电池的标准驱动属性上提供有效生产日期。首用日期仍依赖有来源的厂商或系统接口。诊断电池更换或二手机时有用，不影响实时充电控制。若没有权限和可靠来源，日期留空；不同口径的估算健康度应分别注明来源。 |
| 为何后台记录有数据，我却没收到拔线提示？ | Android 8 起可单独关闭一个通知渠道；Android 的后台受限状态还可能延迟后台任务或开机恢复。Shizuku 的无线调试服务需要在开机后重新启动。[通知渠道](https://developer.android.com/develop/ui/views/notifications/channels) · [后台限制](https://developer.android.com/develop/background-work/background-tasks/bg-work-restrictions) · [Shizuku](https://shizuku.rikka.app/guide/setup/) | 记录开关、提醒渠道、前台服务和 Shizuku 可用性应分别反馈。1.4 可从提示进入被关闭的渠道或本应用系统设置，继续允许用户只记录。厂商后台管理和用户强行停止仍可能要求再次打开应用。 |

在商业锂电芯的受控对照中，储存温度与荷电状态会共同影响日历老化，具体效果取决于电芯和使用条件。这支持记录高温和高电量重叠的时间，也说明不能仅凭某次测量承诺多用几年。对一般手机，减少高温高电量暴露、避免为了制造曲线而主动循环、让可验证的控制端工作，比显示一个缺乏依据的寿命预测值更有意义。[商业电芯储存实测](https://www.sciencedirect.com/science/article/pii/S2451904924000544)

## AlDente 其余功能逐组核对

下表补充前表未单列的 Mac 功能。涉及 AlDente 1.39 的变化以厂商当前功能页为准。[完整功能索引与行为说明](https://apphousekitchen.com/aldente-overview/features/)

| AlDente 功能 | 在 Android 上的价值和可能性 |
| --- | --- |
| 手动放电、接电时自动放电、校准 | 降低高电量停留对长期插电设备有价值。主动放电需要能切断输入或驱动支持；手机若已由适配器旁路供电，主动放电会增加电池吞吐量。校准针对电量计偏差，流程应遵照设备厂商说明。归根已有自然回落和插座断电。只有设备出现电量计偏差时才有理由评估校准。 |
| 暂停时的热保护、硬件电量百分比 | 温控与巡航须协作。ACC 版可并列展示 Android 和 ACC 实际采用的读数，便于解释阈值为什么触发。AlDente 1.39 起硬件电量仅用于展示，控制依据是 macOS 电量；不同平台可获得的原始信息不同。 |
| 应用耗能列表、Power Flow | 无 root 版授权 Shizuku 后能查看 Android 统计窗口内的 UID 耗电量，普通模式有电池端带方向的电流和功率。AlDente 的实时高耗能应用、关闭应用，以及新版支持的部件功率分解没有等价的跨机型公开测量接口。杀后台也不能当成电池养护效果。 |
| 退出时停充、休眠时停充、到达限值前禁止休眠、主动睡眠控制、快速切换系统用户 | ACC 守护可独立于 GUI。无 root 应用依赖用户启用的前台服务，关机后由固件或外设接管。手机熄屏后仍可能运行工作负载；持续持有唤醒锁会干扰待机测量。Android 用户之间有各自的应用数据，但电池和插座是共享硬件，跨账户接管需要明确的归属和恢复。 |
| 低功耗模式、Apple 快捷指令 | Android 的省电模式由系统管理。归根的定向接口分享策略，允许用户把它连入自己的自动化，直接写入阈值或控制另一个用户的设备需要另外设计授权与撤销。 |
| MagSafe 灯、实时状态图标、菜单栏和弹窗的自定义 | Android 对应的是通知、桌面组件和应用页面；状态可读性、横竖屏和大字体直接影响操作。Mac 的充电指示灯和菜单栏属于平台专属。悬浮窗、主题皮肤和持续动画主要提供个性化，额外权限与耗电也应计算在内。 |

## 界面库和后续扩展条件

归根现有页面使用平台 Views。窄窗、大字号、键盘、显示挖孔、读屏标签和焦点顺序已有平台接口。引入 UI 库应针对一个能复现的缺口。例如折叠展开形成的大窗口可按真实可用宽度排版；如果设备有遮住内容的物理铰链，还需通过 WindowManager 的折叠特征识别遮挡范围，分别验证书本和桌面姿态。平板截图只能证明大窗口布局。[自适应 Views](https://developer.android.com/develop/ui/views/layout/responsive-adaptive-design-with-views) · [窗口边衬](https://developer.android.com/develop/ui/views/layout/insets) · [折叠特征](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware) · [无障碍原则](https://developer.android.com/guide/topics/ui/accessibility/principles)

旧版 Android 也有具体依赖门槛。仓库锁定的 Shizuku 13.1.5 客户端 api.aar 在自身清单中声明最低 API 24；归根使用的部分文件和时间接口自 API 26 提供。无 root 版现在使用 Google Maven 分发的 Java 兼容库在 Android 7.0 上执行预约和文件操作。实际测得旧系统兼容库给同路径替换的目录返回相同的文件标识，故用系统报告的设备号和 inode 辨认内置电池；这条路径及 Android 8、17 的既有实现均通过了同路径替换测试。系统版本、控制后端及显示场景的实测以[验证记录](android-compatibility.md)为准。[Shizuku 官方发布包](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/api/13.1.5/) · [Android 文件 API](https://developer.android.com/reference/java/nio/file/Files) · [Java API 兼容库](https://developer.android.com/studio/write/java8-support)

本轮把优先级给了会改变动作正确性、提醒可达性和阈值可操作性的缺口。新的原厂控制适配，需要相应 ROM 和可复现的写入、恢复、电流观测；离线保护需要常通电的外设；精确到点充电需要足够的同机同供电条件样本。具备这些条件时，扩展仍有价值。普通无 root 手机的本地监测、提醒、持久化巡航、临时补电和外设联动已覆盖主要养护路径。
