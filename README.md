# Rootward · 归根

Rootward is a native Android dashboard for [Advanced Charging Controller (ACC)](https://github.com/VR-25/acc). It shows battery readings and lets you set charge and temperature limits on a rooted phone. The interface is in Chinese.

[Download the signed APK](https://github.com/RC-9246/rootward/releases/latest)

归根是一个 Android 电池仪表盘，也是 Advanced Charging Controller（ACC）的图形界面。支持 Android 8.0 及以上。充电控制需要设备已取得 root 权限、安装兼容版本的 ACC，并运行 ACC 守护进程。

## 使用

- **仪表盘**显示系统报告的电量、温度、电池电流与健康状态，并用电池电压和电流估算电池端功率。打开「查看趋势」可查看前台采样记录。部分机型只提供其中一部分读数。
- **充电控制**可设置停充上限与自然回落幅度。例如将上限设为 55%、回落设为 10 个百分点，电量降至 45% 时允许恢复充电。温度保护可独立设置暂停与恢复温度。
- 在 root 管理器中授权归根，然后点「刷新」读取手机现有配置。点「应用」后，归根会从 ACC 读回设置并核对。「养护指南与初始设置」可恢复本应用首次读取的电量或温度区间。

## 构建与签名

准备 Bash、JDK 17、Android SDK Platform 34、Build Tools 34.0.0、zip 与 openssl。将 `ANDROID_HOME` 指向本机 SDK 根目录，在项目根目录运行构建脚本。

```bash
./build.sh
```

构建脚本会签名并验证 APK，输出为 `build/Rootward.apk`。首次构建会在项目上一级的 `signing/` 生成 `accpanel.p12` 和 `passphrase`。也可以在构建前将 `ACC_SIGNING_DIR` 指向自己的签名目录。

请私下备份签名目录中的两个文件。构建更新时复用该目录，Android 才能用同一签名覆盖安装原版本。应用包名为 `io.local.accpanel`。
