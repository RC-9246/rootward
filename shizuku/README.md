# 归根 · Shizuku | Rootward

无需 root 的 Android 电池养护工具。支持 Android 8.0 及以上，界面使用中文，包名为 `io.local.rootward.shizuku`，可与归根 ACC 版并存。[下载签名 APK](https://raw.githubusercontent.com/RC-9246/rootward/downloads/shizuku-v1.2/Rootward-Shizuku-1.2.apk)

| 手机和外设条件 | 可以使用的功能 |
| --- | --- |
| 普通 Android 手机 | 跨插拔电量提醒、手动和预约补电提醒、高温提醒、供电与散热诊断、本地历史、容量和用量分析、方案与桌面组件 |
| 有 Home Assistant 充电插座 | 根据同一电量和温度策略控制充电器供电，每次操作后读取插座状态，无需 Shizuku |
| 通过 ADB 启动并授权 Shizuku | 读取供电驱动和系统估算的应用耗电；内核向 shell 开放写权限时，还可控制兼容的停充开关或修改原生限充阈值 |

## 电量和温度提醒

打开「开始记录与提醒」，并允许接收通知。默认上限是 75%，下限是 50%，高温提醒为 40°C，各项可在应用内调整。Android 13 及以上可拒绝通知并继续本机记录，此时系统不会弹出提醒。

- 第一次观察到电量达到或超过 75% 时，归根提示可拔线，随后记住等待回落的阶段。在 51–74% 之间反复拔线、重插或重新打开应用，均保留该阶段。观察到 50% 或以下时，归根提示可以接电，并开始下一轮。
- 处于等待回落阶段时，可以手动提前开始一轮。仅使用提醒时，开关显示「再次提醒我到达上限」，实际充电仍由手机和外部电源决定。启用硬件巡航或充电插座联动后，开关显示「手动继续充电一次」，归根会按本轮选择请求相应设备切换。中途关闭会恢复等待，达到原上限后自动结束。更改电量阈值也会结束这一轮提前补电。
- 到达温度阈值时通知一次，温度回落至少 2°C 后允许下次通知。系统电池设置入口用于查看本机是否提供限充、智能充电或相应的人工控制。不同系统提供的选项会有差异。

### 预约本轮提前补电

已经达到过上限、正在等待回落，且电量处在上下限之间时，可点「预约单次补电」，明确选择今天或明天的本地开始时间。比如约在 07:00，07:00–08:00 内温控允许即可提前向原上限补电。插拔和重启保留同一个截止时刻；达到上限、自然降到下限、修改电量或温度阈值、停止记录或窗口到期都会结束预约。随时可取消，主动选择手动继续会替换预约。

- 仅启用提醒时，归根会在窗口开始且温控允许时通知你接电，窗口结束后按原区间提醒。
- 已授权并主动开启硬件巡航时，归根会尝试在这段时间放开自己设置的停充，温度过高或没有可靠读数时继续等待。
- 已启用外部联动时，接收方依旧读取当前建议。窗口内允许补电时对外报告 `allow/filling`，过期的预约不能作为新一次供电的依据。

前台服务通常每分钟及在电池状态变化时采样。系统休眠、应用被强制停止等情况可能延迟通知和控制。错过整个窗口会直接恢复原策略。本功能无需精确闹钟权限。

## 养护方案和桌面组件

「保存与切换养护方案」提供长期接电（45%–55%、38°C）、日常（50%–75%、40°C）和出行（65%–90%、40°C）三个可选起点。也可按当前阈值保存命名方案。确认应用后，已开启的提醒、巡航或插座采用新的阈值。修改系统固件的原生上下限有单独的入口。

在桌面的小组件列表添加「归根 · 电池养护」，可查看电量、温度、Android 充放电状态、当前策略和上下限。点时间可刷新，点其余区域打开归根。记录开启时随采样刷新；停止记录时仍可手动读一次。

## Home Assistant 充电插座

1. 在 Home Assistant 确认充电器独占一个可控制的开关实体，如 `switch.phone_charger`。为归根创建访问令牌，准备手机能访问、证书受 Android 系统信任的 HTTPS 地址。[Home Assistant 开关](https://www.home-assistant.io/integrations/switch/) · [访问令牌](https://developers.home-assistant.io/docs/auth_api/#long-lived-access-token)
2. 在「本机记录 → Home Assistant 充电插座」填写服务器、实体 ID 和令牌。「验证并保存」只读取一次开关。令牌使用 Android Keystore 中的密钥加密保存在本机。只有使用此连接时归根才会联系所填服务器。[Home Assistant REST API](https://developers.home-assistant.io/docs/api/rest/)
3. 开始记录，在插座的「操作」中选择「按养护策略联动」。以 50%–75% 为例，达到 75% 请求断电，回落到 62% 时重插线仍会等待。到 50% 且温度允许时，归根才请求恢复自己已确认关闭的插座。手动或预约补电可以提前放行一轮。
4. 归根会在每次切换后读回开关状态。插座原本已关、断电归属发生变化或上一次断电结果待核实时，自动化会保留其断电状态。可以在归根关闭联动、检查插座，再显式选「手动恢复供电」。读回结果来自 Home Assistant，手机是否在充电可对照首页的电池状态与电流。
5. 插座断电后手机使用电池。手机关机、网络中断或应用被强行停止期间，如需自动恢复外部供电，可在家庭控制器或插座设置独立超时。希望使用 Tasker 等其他接收方时，可选用本机自动化接口。[本机自动化](../docs/automation.md)

## 读数与记录

- 首页显示电量、电源来源、电池状态、温度、电池电压、瞬时电流、由电池端电压和电流估算的有符号功率，剩余电荷、系统健康状态、Android 14 及以上可用的循环次数，以及系统可提供的预计充满时间。缺少读数时显示「—」。充电器输入功率与电池端功率的测量位置不同。
- 开启记录后前台服务约每分钟采样一次，电量、电源、状态或温度显著变化也会采样。本机 SQLite 记录保留最近 30 天，支持导出 UTF-8 CSV。图表显示最近一小时电量，近 24 小时统计按**当前阈值**计算高电量、高温和重叠时长，并显示已覆盖时长。时间跨度太大或中途曾停止服务的间隔会断开。
- 开启记录后，手机重启并首次解锁、归根覆盖升级或系统回收服务后，归根会尝试继续本地记录，并保留此前的提醒阶段。点击「停止记录与提醒」后，后续启动和更新均保持停止。系统的强行停止或厂商后台限制可能要求再次打开归根；关机或未采样区间不会计入覆盖时间，也无法判断期间是否到过上限。无线调试启动的 Shizuku 重启后仍需重新启动，期间本地采样和提醒可以继续。[Android 前台服务后台启动条件](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- 「查看养护历史与接电记录」可选 24 小时、7 天或 30 天，按当前阈值比较高电量、高温、两者重叠、接电和系统报告充电的时长。可查看最近八段接电记录的观测起点、首末电量和最高温度。当首末样本都有有效电荷计数时，也显示电池剩余电荷的净变化（mAh）。旧版记录直接沿用，缺失的电荷读数保持为空。
- 历史数据保存在本机，可通过系统文件选择器导出 CSV。手动指定接收应用后，也可共享单条当前策略状态。[本机自动化接入方法](../docs/automation.md)

## 用量与容量分析

「本机记录 → 用量与容量分析」读取最近 30 天数据，无需 root、Shizuku 或联网。当有近期稳定的系统读数时，还可粗估充到上限或离电降至下限的时间。按近 30 分钟内至少 5 分钟、3 个百分点的连续变化外推，供电变化、过热停充、样本不足或近期速率相差过大时不显示预计分钟。最近 7 天和此前 7 天各自列出覆盖时长与离电耗电。离电用量按两端电量的净变化统计，可分别查看设备可交互和非交互期间的覆盖时长。在屏幕常亮、息屏显示或特殊设备上，「可交互」由 Android 定义。旧记录没有交互字段，仍计入离电总量；新增 CSV 列 `android_interactive` 为 1 或 0，旧样本留空。

容量估算只取 5%–95% 区间内、跨越至少 20 个百分点且持续至少 5 分钟的连续充电片段。在同一可观测接电段只取一个片段，根据剩余电荷增量（mAh）除以该段电量增幅估算。程序跳过计数缺失、回退、读数跳变或计数像是由整数百分比直接换算的片段。至少三段且三分之二的候选处于候选中位数 ±15% 内时，显示这些片段的中位数、范围和电量跨度；界面同时列出筛选数量。双电芯手机沿用 Android 上报口径，不自动乘二。计数仍可能来自系统模型，结果仅供跟踪同机变化。日常 50%–75% 的自然充电已经有机会产生合格片段。[Android 电量与剩余电荷属性](https://developer.android.com/reference/android/os/BatteryManager#BATTERY_PROPERTY_CHARGE_COUNTER)

## 供电、散热与原厂养护

点首页「供电与散热诊断」即可读取电池端平均电流、剩余能量和系统热状态，无需连接 Shizuku。平均电流以电池为参照，流入表示净充电，流出表示净放电。Android 10 起可读取整机节流状态，Android 11 起可读取当前热负荷。热负荷 1.00 对应严重节流参考线，数值可以超过 1.00。点击刷新可更新快照，热负荷最多每 10 秒读取一次。设备未提供的量标为「未上报」。[Android 电池属性](https://developer.android.com/reference/android/os/BatteryManager) · [Android 热管理](https://developer.android.com/games/optimize/adpf/thermal) · [热负荷定义](https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int))

「系统电池设置」优先打开已安装、可调用的 LineageOS 充电控制，其他系统打开公开电池设置，缺少电池页时可回到系统设置。归根按厂商给出原厂菜单路径，由使用者在系统中选择可用模式。Pixel 6a 起支持 80% 选项，Galaxy 提供电池保护，一加 8T 原厂手册列有按习惯调整的充电优化。第三方 ROM 的选项以实际系统页面为准。[LineageOS 入口](https://github.com/LineageOS/android_packages_apps_LineageParts/blob/lineage-22.2/AndroidManifest.xml#L88-L98) · [Pixel 帮助](https://support.google.com/pixelphone/answer/6090612) · [三星支持](https://www.samsung.com/us/support/answer/ANS10002534/) · [一加 8T 手册](https://service.oneplus.com/content/dam/support/user-manuals/cn/OnePlus_8T_User_Manual_CN.pdf) · [OxygenOS 14 手册](https://service.oneplus.com/content/dam/support/user-manuals/common/OxygenOS_14.0_User_Manual.pdf)

在已授权的 Shizuku 诊断中，还可查看 USB 驱动公开的瞬时电压和电流，以及各自的上限。瞬时值描述 USB 接口当时的读数，电源的额定功率需查充电器铭牌。[Linux 电源属性](https://docs.kernel.org/power/power_supply_class.html)

## Shizuku 诊断

在 Shizuku 管理器中用 ADB 启动服务。Android 11 及以上可在支持无线调试的设备上通过配对完成，Android 8–10 可用电脑启动。回到归根，在 Shizuku 区域申请授权，然后读取诊断。手机重启后通常需要重新启动 Shizuku。[Shizuku 官方使用指南](https://shizuku.rikka.app/zh-hans/guide/setup/)

归根通过官方 Shizuku API 的 UserService 读取 shell 身份可访问的电池状态、循环次数、满充与设计容量、USB 在线状态、类型、电压与电流上限以及驱动温度。另读取系统电池服务报告的充电电流、电压上限与电荷计数。各机型公开的节点、单位与可读权限有差异，未提供的数据会标明。Shizuku 服务未运行或授权被拒绝时，标准 Android 电池读数、记录与提醒仍可使用。[Shizuku API](https://github.com/RikkaApps/Shizuku-API) · [Android BatteryManager](https://developer.android.com/reference/android/os/BatteryManager)

授权后点「查看各应用耗电估算」，可查看当前 Android 用户耗电最高的十个应用 UID。归根读取系统电池统计窗口内的 UID 耗电估算（mAh）。系统可能将多个应用计入同一 UID，包名、电量模型或统计片段都可能缺失。该快照在本机显示。[Android batterystats](https://developer.android.com/tools/dumpsys#battery)

点击「查看各应用耗电估算」可按需查看当前用户自上次充满以来的系统估算前十名，单位为 mAh。归根只在本机处理统计，并核对包名属于该用户的 UID。共享 UID 显示合计，设备尚未提供估算时会说明原因。[Android 电池统计格式](https://developer.android.com/tools/dumpsys#battery)

## 有硬件接口的手机

本版本使用 ADB shell 身份（UID 2000），无需 root、Magisk、KernelSU 或 ACC。归根会检查电池驱动是否提供标准 `charge_behaviour` 接口、列出 `auto` 与 `inhibit-charge` 两种模式，并允许 shell 读写。检查本身不会提交任何充电模式。普通原厂系统往往只开放读数，此时可使用电量与高温提醒、诊断和系统电池设置入口。[Linux 电源驱动接口](https://www.kernel.org/doc/Documentation/ABI/testing/sysfs-class-power)

- **手动操作**可以要求驱动暂停，或恢复系统自动充电。每次提交后都读取驱动返回的模式。在自动模式下，系统自己的充电上限与温控仍会生效。
- **前台硬件巡航**需要单独开启，并保持记录服务和 Shizuku 可用。例如 75% 停充后，拔线再接仍等到 50% 或以下；手动提前一轮可以在温度允许时恢复，到 75% 再暂停。高温时先暂停，至少降到预警温度以下 2°C 且电量阶段允许，才请求恢复。前台服务根据实际采样检查，所以驱动切换可能晚于越过阈值的时刻。
- 关闭巡航或正常停止记录时，归根会尝试解除自己确认设置的停充。系统休眠、强制停止、重启或 Shizuku 失联可能使操作延迟或留下上一次的驱动状态。重新连接后可在归根查看状态，必要时手动恢复系统自动充电。卸载前请先关闭巡航并确认驱动已恢复。

### 系统原生限充

在「Shizuku 驱动诊断」授权并读取驱动后，点「查看系统原生限充」。归根从系统供电设备中按电池类型、供电范围和在位状态识别主电池。若存在多个候选，界面会提示无法确定。系统提供一个端点时可单独查看该端点，shell 具有写权限时可一次修改其中一项。写入前会比对电池和原区间，写入后展示驱动实际接受的百分比。有些驱动只支持有限档位。

原生上下限由系统驱动解释，有些驱动仅在自定义充电模式下执行这些值。退出应用后设置可能继续保留。插拔时如何复充、是否包含边界值以及重启后是否保留设置取决于驱动。「撤销上一次修改」会将最近一次由归根成功更改的端点恢复为原值。记录保存在本机，退出并重新打开应用后仍可用。撤销前会再次核对电池身份、启动周期、写权限和两端实际值，完成后读回上下限。若期间更换电池、系统改过区间、写入回执不明或驱动联动修改另一端，界面会说明原因并显示记录中的原值。已开启前台硬件巡航时仍可查看这些值，需关闭巡航后手动修改。手动恢复系统自动充电后，驱动自身的阈值仍然有效。[Android 通用内核的 power_supply ABI](https://android.googlesource.com/kernel/common/+/android-mainline/Documentation/ABI/testing/sysfs-class-power#356)

## 从源码构建

准备 Bash、JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0，以及 curl、unzip、zip、openssl。将 `ANDROID_HOME` 指向 Android SDK 目录，在源码的 `shizuku/` 目录内运行以下命令。

```sh
./test.sh
./build.sh
```

构建过程从 Maven Central 和 Google Maven 下载固定版本的依赖并验证 SHA-256，然后签名及校验 `build/Rootward-Shizuku-1.2.apk`。默认与 ACC 版共用仓库外的 `signing/` 目录，可用 `ACC_SIGNING_DIR` 设置其他私有目录。自行构建的更新包需复用同一签名密钥。

Shizuku API 13.1.5 使用 MIT 许可证，AndroidX Annotation 1.3.0 使用 Apache License 2.0，这两份许可证随 APK 及源码附带于 `app/src/main/res/raw/`。
