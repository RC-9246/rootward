# Rootward · 归根

Rootward is a native Android dashboard for [Advanced Charging Controller (ACC)](https://github.com/VR-25/acc). It shows battery readings and lets you set charge and temperature limits on a rooted phone. The interface is in Chinese.

[Download the signed APK](https://github.com/RC-9246/rootward/releases/latest)

归根是一个 Android 电池仪表盘，也是 Advanced Charging Controller（ACC）的图形界面。支持 Android 8.0 及以上。充电控制需要设备已取得 root 权限、安装兼容版本的 ACC，并运行 ACC 守护进程。

## 使用

- **仪表盘**显示系统报告的电量、温度、电池电流与健康状态，并用电池电压和电流估算电池端功率。打开「查看趋势」可查看前台采样记录。部分机型只提供其中一部分读数。
- **充电控制**可设置停充上限与自然回落幅度。例如将上限设为 55%、回落设为 10 个百分点，常规恢复阈值就是 45%。温度保护可独立设置暂停与恢复温度。
- **跨插拔保持停充**位于「充电控制」的电量区间下方。例如上限为 75%、恢复电量为 50%，开启后 ACC 到 75% 停充，此后即使在 60% 拔线并重新接入，也须等到 50% 或以下且温度等条件允许才复充。若本轮从低电量向上充，经过 60% 时仍可继续充到 75%。
- 首次点击开启时，归根从签名 APK 安装自己的 ACC 插件，并重启 ACC 守护进程以载入插件。原有电量、温度和 `force_off` 设置保留。开启时电量已达上限便从等下限开始，已到下限则允许充电。若处在两者之间或暂时没有电量读数，请按本轮情况选择「已经到过上限」或「正在充向上限」。
- 插件保存本轮阶段，拔线、换线、退出归根均保留该阶段。仪表盘分别展示最近读回的阶段和 Android 上报的充电状态。USB 重新协商时，驱动可能短时恢复供电，ACC 需要轮询并重新施加停充。若显示「插件待载入」，可重新载入并沿用已保存阶段。卸载归根前，可在充电控制中关闭插件。
- 此功能适配 ACC v2023.10.16 的默认配置、百分比阈值和可用的硬件停充开关。严格模式用保存的阶段处理守护重启时的区间中段，原配置中的 `off_mid` 保留。若 `resume_temp` 带 `r`，ACC 可按温度越过恢复电量，归根会提示冲突并禁止开启跨插拔保持。
- 在 root 管理器中授权归根，然后点「刷新」读取手机现有配置。点「应用」后，归根会从 ACC 读回设置并核对。「养护指南与初始设置」可恢复本应用首次读取的电量或温度区间。

## 构建与签名

准备 Bash、JDK 17、Android SDK Platform 34、Build Tools 34.0.0、zip 与 openssl。将 `ANDROID_HOME` 指向本机 SDK 根目录，在项目根目录运行构建脚本。

```bash
./build.sh
```

构建脚本会签名并验证 APK，输出为 `build/Rootward.apk`。首次构建会在项目上一级的 `signing/` 生成 `accpanel.p12` 和 `passphrase`。也可以在构建前将 `ACC_SIGNING_DIR` 指向自己的签名目录。

请私下备份签名目录中的两个文件。构建更新时复用该目录，Android 才能用同一签名覆盖安装原版本。应用包名为 `io.local.accpanel`。
