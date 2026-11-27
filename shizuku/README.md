# 归根 · Shizuku | Rootward

无需 root 的 Android 电池养护工具。支持 Android 8.0 及以上，界面使用中文，包名为 `io.local.rootward.shizuku`，可与归根 ACC 版并存。[下载签名 APK](https://github.com/RC-9246/rootward/releases/download/v1.4/Rootward-Shizuku-1.0.apk)

## 电量和温度提醒

打开「开始记录与提醒」，并允许接收通知。默认上限是 75%，下限是 50%，高温提醒为 40°C，各项可在应用内调整。Android 13 及以上可拒绝通知并继续本机记录，此时系统不会弹出提醒。

- 第一次观察到电量达到或超过 75% 时，归根提示可拔线，随后记住等待回落的阶段。在 51–74% 之间反复拔线、重插或重新打开应用，均保留该阶段。观察到 50% 或以下时，归根提示可以接电，并开始下一轮。
- 处于等待回落阶段时，可以手动提前开始一轮。仅使用提醒时，开关显示「再次提醒我到达上限」，实际充电仍由手机和外部电源决定。开启硬件巡航后，开关显示「手动继续充电一次」，前台服务会按本轮选择请求驱动切换。中途关闭会恢复等待，达到原上限后自动结束。更改电量阈值也会结束这一轮提前补电。
- 到达温度阈值时通知一次，温度回落至少 2°C 后允许下次通知。系统电池设置入口用于查看本机是否提供限充、智能充电或相应的人工控制。不同系统提供的选项会有差异。

## 读数与记录

- 首页显示电量、电源来源、电池状态、温度、电池电压、瞬时电流、由电池端电压和电流估算的有符号功率，剩余电荷、系统健康状态、Android 14 及以上可用的循环次数，以及系统可提供的预计充满时间。缺少读数时显示「—」。充电器输入功率与电池端功率的测量位置不同。
- 开启记录后前台服务约每分钟采样一次，电量、电源、状态或温度显著变化也会采样。本机 SQLite 记录保留最近 30 天，支持导出 UTF-8 CSV。图表显示最近一小时电量，近 24 小时统计按**当前阈值**计算高电量、高温和重叠时长，并显示已覆盖时长。时间跨度太大或中途曾停止服务的间隔会断开。
- 已记录的提醒阶段保存在本机。设备重启后打开归根可恢复已启用的记录，关机、被系统强制停止或未采样时的阈值穿越无法回溯。点击「停止记录与提醒」可主动停用前台服务，应用会记住上一次阶段。
- 数据全部留在本机，应用仅在选择导出时向系统文件选择器选定的位置写入 CSV。

## Shizuku 诊断

在 Shizuku 管理器中用 ADB 启动服务。Android 11 及以上可在支持无线调试的设备上通过配对完成，Android 8–10 可用电脑启动。回到归根，在 Shizuku 区域申请授权，然后读取诊断。手机重启后通常需要重新启动 Shizuku。[Shizuku 官方使用指南](https://shizuku.rikka.app/zh-hans/guide/setup/)

归根通过官方 Shizuku API 的 UserService 读取 shell 身份可访问的电池状态、循环次数、满充与设计容量、USB 在线状态、类型、电压与电流上限以及驱动温度。另读取系统电池服务报告的充电电流、电压上限与电荷计数。各机型公开的节点、单位与可读权限有差异，未提供的数据会标明。Shizuku 服务未运行或授权被拒绝时，标准 Android 电池读数、记录与提醒仍可使用。[Shizuku API](https://github.com/RikkaApps/Shizuku-API) · [Android BatteryManager](https://developer.android.com/reference/android/os/BatteryManager)

## 有硬件接口的手机

本版本使用 ADB shell 身份（UID 2000），无需 root、Magisk、KernelSU 或 ACC。归根会检查电池驱动是否提供标准 `charge_behaviour` 接口、列出 `auto` 与 `inhibit-charge` 两种模式，并允许 shell 读写。检查本身不会提交任何充电模式。普通原厂系统往往只开放读数，此时可使用电量与高温提醒、诊断和系统电池设置入口。[Linux 电源驱动接口](https://www.kernel.org/doc/Documentation/ABI/testing/sysfs-class-power)

- **手动操作**可以要求驱动暂停，或恢复系统自动充电。每次提交后都读取驱动返回的模式。在自动模式下，系统自己的充电上限与温控仍会生效。
- **前台硬件巡航**需要单独开启，并保持记录服务和 Shizuku 可用。例如 75% 停充后，拔线再接仍等到 50% 或以下；手动提前一轮可以在温度允许时恢复，到 75% 再暂停。高温时先暂停，至少降到预警温度以下 2°C 且电量阶段允许，才请求恢复。前台服务根据实际采样检查，所以驱动切换可能晚于越过阈值的时刻。
- 关闭巡航或正常停止记录时，归根会尝试解除自己确认设置的停充。系统休眠、强制停止、重启或 Shizuku 失联可能使操作延迟或留下上一次的驱动状态。重新连接后可在归根查看状态，必要时手动恢复系统自动充电。卸载前请先关闭巡航并确认驱动已恢复。

## 从源码构建

准备 Bash、JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0，以及 curl、unzip、zip、openssl。将 `ANDROID_HOME` 指向 Android SDK 目录后运行：

```sh
./test.sh
./build.sh
```

构建过程从 Maven Central 和 Google Maven 下载固定版本的依赖并验证 SHA-256，然后签名及校验 `build/Rootward-Shizuku-1.0.apk`。默认与 ACC 版共用仓库外的 `signing/` 目录，可用 `ACC_SIGNING_DIR` 设置其他私有目录。自行构建的更新包需复用同一签名密钥。

Shizuku API 13.1.5 使用 MIT 许可证，AndroidX Annotation 1.3.0 使用 Apache License 2.0，这两份许可证随 APK 及源码附带于 `app/src/main/res/raw/`。
