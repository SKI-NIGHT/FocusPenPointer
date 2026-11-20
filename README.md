# FocusPen Pointer

**把小米焦点触控笔 Pro 变成隔空指针** · **Turn the Xiaomi Focus Pen Pro into an air pointer**

[简体中文](#简体中文) · [English](#english)

FocusPen Pointer is an LSPosed module for rooted Xiaomi tablets on **HyperOS 3–4**. It replaces Xiaomi's virtual laser with a system-style pointer and turns the pen-body pinch into a finger: wherever the pointer is, a pinch touches the screen there. It uses runtime hooks only and never modifies system partitions.

FocusPen Pointer 是一个 LSPosed 模块，面向运行 **HyperOS 3–4** 的 Root 小米平板。它把小米的虚拟激光换成系统样式的指针，并把“捏笔身”变成一根手指：指针在哪里，捏一下就在哪里触摸屏幕。只做运行时 Hook，不修改系统分区。

> [!WARNING]
> The module runs inside system_server. It has been tested on the device and builds listed below only. After a system update, add your drawing and note apps to the blacklist first and test carefully.
>
> 本模块运行在 system_server 中，目前只在下列设备和系统版本上测试过。系统升级后，请先把绘画、笔记类应用加入黑名单再小心测试。

## 简体中文

### 怎么用

用小米原本的方式（或轻捏四次）开启虚拟激光后，激光点会变成鼠标箭头：

| 操作 | 效果 |
|---|---|
| 轻捏一下 | 点击 |
| 快速捏两下 | 双击 |
| 捏住不动 | 长按 |
| 捏住并移动 | 滑动、滚动、拖动 |
| 在左右边缘捏住往里滑 | 返回 |
| 在底部边缘捏住往上滑 | 回桌面、呼出 Dock、多任务 |
| 手指触屏 | 退出 |

点击、长按、滚动等都由应用自己识别，和真手指完全一样。

### 功能

- **系统样式指针**：使用系统自带的鼠标箭头。
- **边缘手势**：解除小米激光离边缘约 8dp 的限制（可关），指针能贴到屏幕边上；左右边缘会等笔真正开始往里滑再落指，满足 HyperOS 返回手势“按下 150ms 内必须移动”的要求。
- **捏笔防抖**：捏笔时笔身会先歪一下（按键触发前约 150ms 就开始），松开时会回弹。模块在捏下时把指针退回到歪之前的位置，并在可调时长内忽略晃动；抬起时消除回弹，避免手势被判为“反悔”。
- **指针手感**：灵敏度 20%–200%，平滑（One Euro 滤波，快动跟手、慢动去抖）。
- **激光关闭时的手势**：轻捏两次、两次并按住、笔身上下滑可映射为音量、返回、桌面、最近任务、滚轮等；全局轻捏四次可开启指针或启动应用。
- **生效范围**：所有应用都生效；黑名单内的应用完全不 Hook，保持小米原版激光和画笔。
- 简体中文、繁體中文、English 界面。

### 兼容性

- 测试设备：小米 Pad 8 Pro（`25091RP04C`）+ 小米焦点触控笔 Pro，`arm64-v8a`。
- 测试系统：HyperOS `OS4.0.5.0.XPYCNXM`（Android 17）。HyperOS 3 / 4 两套注入路径可自动识别，也可手动选择。
- 其他平板和系统版本未逐一验证。

### 安装

1. 安装 APK，在 LSPosed 中启用 **FocusPen Pointer**，作用域选“系统框架”。
2. 如果装过 FocusPen Pro X 或其他焦点触控笔模块，请在 LSPosed 中停用它们，避免同时 Hook。
3. 重启设备，打开应用，授予“获取应用列表”权限，打开总开关。

### 安全

- 不修改 `system`、`product`、`vendor`、`odm` 等分区，不关闭 SELinux，不联网、不收集数据。
- Hook 异常会被拦截并放行原始事件；连续异常会熔断。模拟的手指有超时释放。
- 关闭总开关即停止接管；在 LSPosed 中停用或卸载后重启即可完全恢复。

### 构建

需要 JDK 21、Android SDK Platform 36.1、Build Tools 36.x。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

debug 包支持**热重载**：模块已加载后，重新安装 debug APK 即可替换 system_server 里的逻辑，无需重启；只有 Hook 点变化时需要重启。正式签名：在项目根目录放置不纳入 Git 的 `keystore.properties`，执行 `.\gradlew.bat :app:assembleRelease`。

## English

### Using it

Turn on the virtual laser the usual way (or pinch four times); the laser dot becomes a mouse arrow:

| Action | Result |
|---|---|
| Pinch | Tap |
| Pinch twice quickly | Double tap |
| Pinch and hold | Long press |
| Pinch and move | Swipe, scroll, drag |
| Pinch-swipe inward from the left/right edge | Back |
| Pinch-swipe up from the bottom edge | Home, dock, recents |
| Touch the screen | Leave |

Apps see an ordinary finger, so they recognise everything exactly as for a real touch.

### Features

- **System-style pointer** using the system's own mouse arrow.
- **Edge gestures**: lifts Xiaomi's ~8dp edge margin (switchable) so the pointer reaches the edges. At the left/right edges the finger goes down only when the pen starts sliding inward, which HyperOS's back gesture requires (a touch that has not moved 150 ms after going down is cancelled).
- **Pinch anti-jitter**: squeezing tilts the pen (starting ~150 ms before the pinch registers) and releasing springs it back. A pinch rewinds the pointer to before the tilt and ignores wobble for an adjustable time; the release spring-back is removed so gestures do not read as a change of mind.
- **Pointer feel**: sensitivity 20–200 % and smoothing (One Euro filter: responsive when fast, steady when slow).
- **Laser-off gestures**: two pinches, two pinches and hold, and pen-body swipes map to volume, back, home, recents, wheel and more; a global four-pinch turns the pointer on or launches an app.
- **Scope**: every app; blacklisted apps are not hooked at all and keep Xiaomi's laser and brush.
- Simplified Chinese, Traditional Chinese and English UI.

### Compatibility

- Tested on Xiaomi Pad 8 Pro (`25091RP04C`) with Xiaomi Focus Pen Pro, `arm64-v8a`.
- Tested build: HyperOS `OS4.0.5.0.XPYCNXM` (Android 17). Separate HyperOS 3 / 4 injection paths, detected automatically or selectable.
- Other tablets and builds are untested.

### Installation

1. Install the APK, enable **FocusPen Pointer** in LSPosed with the "System Framework" scope.
2. Disable FocusPen Pro X or any other Focus Pen module in LSPosed so they do not hook the same code.
3. Reboot, open the app, grant the app-list permission and turn on the master switch.

### Safety

- Never modifies `system`, `product`, `vendor` or `odm`, does not disable SELinux, has no network access and collects no data.
- Hook failures are contained and pass input through; repeated failures trip a circuit breaker. The virtual finger is released on timeout.
- Turning off the master switch stops all interception; disabling or uninstalling in LSPosed and rebooting restores everything.

### Build

Requires JDK 21, Android SDK Platform 36.1 and Build Tools 36.x.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Debug builds **hot-reload**: once the module is loaded, reinstalling the debug APK replaces the logic inside system_server without a reboot; only hook-point changes need one. For signed releases, provide an untracked `keystore.properties` and run `.\gradlew.bat :app:assembleRelease`.

## Credits and license

FocusPen Pointer started as a fork of [FocusPen Pro X](https://github.com/HMQYHM/FocusPenProX) by **HMQYHM** and has since been largely rewritten. Thanks to the original author for the LSPosed groundwork and the configuration UI it builds on.

本项目源自 HMQYHM 的 [FocusPen Pro X](https://github.com/HMQYHM/FocusPenProX)，之后经过大幅重写。感谢原作者打下的 LSPosed 基础和配置界面。

Copyright © 2026 SKI-NIGHT. Based on FocusPen Pro X, Copyright © 2026 HMQYHM. Licensed under the [GNU General Public License v3.0 only](LICENSE); see [NOTICE](NOTICE).
