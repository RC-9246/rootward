# Android 适配与验证

归根 ACC 1.6 和无 root 1.3 使用 Android 17 SDK 37.2、Build Tools 37.0.0 构建，目标 API 为 37，最低安装 API 为 26（Android 8.0）。两个版本分别沿用原包名及签名。两份 APK 都没有随应用进程加载的原生共享库。[Android 17 行为变更](https://developer.android.com/about/versions/17/behavior-changes-17) · [Android 16 KB 内存页](https://developer.android.com/guide/practices/page-sizes)

## 本次验证范围

| 运行环境 | 已验证的行为 |
| --- | --- |
| Android 8.0（API 26） | ACC 1.5、无 root 1.2 分别覆盖升级；两版首页在竖屏和横屏双倍字号下均可见。 |
| Android 11（API 30） | 320dp 窄屏、深色、200% 字体、横屏表单和大屏；无 root 版旋转保留阈值输入，小组件在 160×88dp 和 600×300dp 分别显示紧凑和完整内容。ACC 版的小横屏对话框可滚动到全部操作。 |
| Android 17.2 稳定版（API 37.2，CP41.260831.015，16 KB 页） | 两版覆盖升级，验证横竖屏、深色、大字体、挖孔屏和三键导航。无 root 版在 Gboard 上方显示阈值表单，旋转保留输入，平板和两种尺寸的桌面组件读回新阈值。由 1.2 升级后，75% 回落至 62% 的等待状态保留，后台记录自动恢复，到 50% 转入下一轮。局域网授权经系统弹窗验证，拒绝后仍可记录，再次授权保留已填服务器地址。 |
| Android Canary 2026-11-04（ZP11.261016.008，16 KB 页） | 两版覆盖升级、默认字号竖屏和双倍字号横屏。官方 Shizuku 13.6.0 在 ADB 模式通过授权，归根成功读取 UID 2000 驱动诊断，并从系统估算中列出十个应用 UID。 |

这里的 Canary 是独立预览渠道。QPR3 Beta 4 的官方 Pixel 构建号为 DP11.261030.003，本轮没有运行该构建。[QPR3 版本记录](https://developer.android.com/about/versions/17/qpr3/release-notes)

## 可复现的界面检查

在专用模拟器安装同一版本的两个安装包，解锁屏幕后，从仓库根目录运行以下命令，将序列号换为该模拟器的实际值。

```sh
python3 tests/display-smoke.py emulator-5554 --screenshots /tmp/rootward-display
```

脚本分别用竖屏默认字号和横屏双倍字号启动两个版本，核对界面树中的可见文字，按需保存系统截图，完成后恢复原有字号和旋转设置。截图可用于检查状态栏、导航栏、留白和换行。设置页、表单、桌面组件及实际充电状态另有专项检查。

## 权限与硬件

- 记录和温度提醒由用户开启的前台服务提供。Android 13 及以上允许单独关闭通知；没有通知权限时，系统提醒的可见性受限。
- Android 17 访问局域网 Home Assistant 服务器时，在插座对话框中申请「附近的设备」权限。公网 HTTPS 服务可直接连接。系统信任的证书和可访问的服务器仍是前提。[Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)
- 真正的电池停充分别依赖获得授权的 ACC 驱动、允许 shell 写入的内核接口或外接智能插座。模拟器可验证界面和策略；厂商系统后台限制、物理插座通断和电芯电流需在相应设备验证。

两份 APK 均已校验原更新签名和 16 KB ZIP 对齐。另以 Android Lint 检查版本受限的 API、内联常量和资源类型，并运行无 root 版的策略测试及 ACC 版的配置读回、跨插拔巡航回归测试。
