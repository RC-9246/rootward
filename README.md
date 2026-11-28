# Rootward · 归根

Rootward is an Android battery-care app with a Chinese interface. The ACC edition controls charging on a compatible rooted phone. The Shizuku edition monitors stock phones, can switch a dedicated charger outlet through Home Assistant, and supports on-device charging control when the kernel grants Android’s shell access to a compatible interface. The ACC edition supports Android 6.0 and later. The non-root edition supports Android 7.0 and later.

| 安装包 | 适合哪台手机 | 能做什么 |
| --- | --- | --- |
| [归根 1.7 · ACC 版](https://raw.githubusercontent.com/RC-9246/rootward/downloads/v1.7/Rootward-1.7.apk) | 已 root、内核支持停充 | 通过 ACC 控制真实充电，设置电量和温度区间，跨插拔保持停充，或手动、预约提前补电一次。 |
| [归根 · Shizuku 1.4](https://raw.githubusercontent.com/RC-9246/rootward/downloads/v1.7/Rootward-Shizuku-1.4.apk) | 无 root，Shizuku 可选 | 跨插拔提醒、手动与预约补电、容量分析、养护方案、桌面组件和可选的 Home Assistant 插座控制。授权 Shizuku 后可读驱动和系统应用耗电，兼容机型可直接限充。 |

两个版本使用独立包名，可以并存。若选择多个充电控制器，请检查各自的电量与温控限制。[Shizuku 版的功能、授权与构建方法](shizuku/README.md)

## 内置 ACC 与 Magisk

ACC 版通过标准 `su` 获取用户授予的 root 权限，Magisk 可以提供该权限并安装 ACC 模块。使用其他 root 方案时，除了兼容的 `su` 和硬件充电开关，还需满足 ACC 的 BusyBox 依赖，并在开机后运行 `/data/adb/vr25/acc/service.sh` 初始化守护进程。[ACC v2023.10.16 安装要求](https://github.com/VR-25/acc/blob/v2023.10.16/README.md#requirements)

ACC 版签名 APK 内置 VR25 发布的原版 ACC 模块。首次安装时，在「充电控制 → 内置 ACC 模块 → 导出模块 ZIP」选择保存位置，然后在 Magisk 的「模块 → 从本地安装」选择该 ZIP。按管理器提示完成安装并回到归根刷新。模块已经安装时，归根直接连接现有守护进程。升级归根本身会保留 ACC 配置。

内置资产为 `acc_v2023.10.16_202310160.zip`，SHA-256 为 `fe071f77a6300f1c14aee1ffc8e22452e448635a7a40e6d97480f3b44fb8c795`。APK 离线保存完整模块，导出前校验其摘要。ACC 的版权归 VR25（2017–2023）所有，使用 GNU GPL v3 或更高版本分发。ZIP 保留上游的 `License.md`、源脚本、构建脚本、文档和署名。[ACC 官方发行包](https://github.com/VR-25/acc/releases/tag/v2023.10.16) · [此版本的完整源码与许可证](https://github.com/VR-25/acc/tree/v2023.10.16)

## 使用

- **仪表盘**显示系统报告的电量、温度、电池电流与健康状态，并用电池电压和电流估算电池端功率。打开「查看趋势」可查看前台采样记录。部分机型只提供其中一部分读数。
- **充电控制**可设置停充上限与自然回落幅度。例如将上限设为 55%、回落设为 10 个百分点，常规恢复阈值就是 45%。温度保护可独立设置暂停与恢复温度。
- **跨插拔保持停充**位于「充电控制」的电量区间下方。例如上限为 75%、恢复电量为 50%，开启后 ACC 到 75% 停充，此后即使在 60% 拔线并重新接入，也须等到 50% 或以下且温度等条件允许才复充。若本轮从低电量向上充，经过 60% 时仍可继续充到 75%。
- **本次继续充电**用于已经到过上限、正在等回落的这一轮。例如在 60% 时打开，温度允许且有外部电源时，ACC 可提前恢复充电。到原上限 75% 后开关自动关闭，重新等 50%；中途关掉也会恢复等待。中间插拔线或退出应用会保留本次选择。首次从 1.3 使用这项操作时，归根会升级自己的巡航插件并重启一次 ACC 守护进程。
- **预约单次补电**适合希望稍后再补电的人。在已经到过上限、正等待回落的这一轮，选择今天或次日的本地开始时间，例如 07:00。07:00–08:00 内温控和外部供电允许时可以提前充电，最高仍为原上限。窗口结束，即使还没有充到上限也会恢复原巡航；到原上限、自然降至恢复线或变更电量区间都会结束预约。可随时取消，也可改为手动继续。已有 1.3 或 1.4 插件的设备只在显式操作需要新插件时升级，保留已保存的回落阶段。
- 首次点击开启时，归根从签名 APK 安装自己的 ACC 插件，并重启 ACC 守护进程以载入插件。原有电量、温度和 `force_off` 设置保留。开启时电量已达上限便从等下限开始，已到下限则允许充电。若处在两者之间或暂时没有电量读数，请按本轮情况选择「已经到过上限」或「正在充向上限」。
- 插件保存本轮阶段，拔线、换线、退出归根均保留该阶段。仪表盘分别展示最近读回的阶段和 Android 上报的充电状态。USB 重新协商时，驱动可能短时恢复供电，ACC 需要轮询并重新施加停充。若显示「插件待载入」，可重新载入并沿用已保存阶段。卸载归根前，可在充电控制中关闭插件。
- 此功能适配 ACC v2023.10.16 的默认配置、百分比阈值和可用的硬件停充开关。严格模式用保存的阶段处理守护重启时的区间中段，原配置中的 `off_mid` 保留。若 `resume_temp` 带 `r`，ACC 可按温度越过恢复电量，归根会提示冲突并禁止开启跨插拔保持。
- 在 root 管理器中授权归根，然后点「刷新」读取手机现有配置。点「应用」后，归根会从 ACC 读回设置并核对。「养护指南与初始设置」可恢复本应用首次读取的电量或温度区间。
- 仪表盘显示带读取时间的 ACC 驱动电量、温度和状态，方便对照 Android 的显示值。单次补电使用 ACC 实际采用的电量判定。前台约每分钟复核，失去有效读数会停用相关写入。若明确读到 ACC 守护进程已停止，可以在应用内手动启动并核验状态。

[本轮竞品与原厂方案调研](docs/capability-review.md) · [查看能力边界和竞品功能取舍](docs/capabilities.md) · [Android 版本与界面验证范围](docs/android-compatibility.md)

## 构建与签名

准备 Bash、JDK 17、Android SDK Platform 37.2、Build Tools 37.0.0、zip 与 openssl。将 `ANDROID_HOME` 指向本机 SDK 根目录，在项目根目录运行构建脚本。

```bash
./build.sh
```

构建脚本会签名并验证 APK，输出为 `build/Rootward.apk`。首次构建会在项目上一级的 `signing/` 生成 `accpanel.p12` 和 `passphrase`。也可以在构建前将 `ACC_SIGNING_DIR` 指向自己的签名目录。

请私下备份签名目录中的两个文件。构建更新时复用该目录，Android 才能用同一签名覆盖安装原版本。应用包名为 `io.local.accpanel`。
