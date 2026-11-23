# Changelog

## Unreleased

### 中文

- **修复激光关闭时轻捏误点击**：不再注入轻捏副本（系统会把副本变成“确认键”，点击当前焦点控件，例如左上角菜单）。支持笔的应用（备忘录、StarNote、WPS 等）直接收到真实按键，捏住不放时的重复按键被丢弃；其他应用收不到轻捏。
- **修复四连捏偶尔失效**。
- **刚打开激光时的轻捏被忽略**（600ms 内），避免误点。
- **远程桌面（如 UU 远程）指针方向和速度正确**：应用捕获鼠标时，按屏幕旋转修正方向并恢复速度；只在确实收到捕获数据时修正，未连接时不受影响。
- 需要重启一次（外壳版本 5）。

### English

- **No more stray clicks from laser-off pinches**: pinch copies are no longer injected (Android turned them into DPAD_CENTER, clicking the focused view such as a top-left menu). Pen-aware apps (Notes, StarNote, WPS, …) get the real key with held-pinch repeats dropped; other apps get no pinch.
- **Four-pinch no longer fails occasionally.**
- **Pinches within 600 ms of turning the laser on are ignored.**
- **Remote-desktop apps (e.g. UU Remote) get the right pointer direction and speed**: while an app captures the pointer, deltas are rotated for the display and rescaled; the correction applies only while captured data actually arrives, so a disconnected app is unaffected.
- Needs one reboot (shell version 5).

## 0.1.0 - 2026-11-20

First release of FocusPen Pointer as an independent project
(package `io.github.skinight.focuspenpointer`). It installs alongside, not over,
FocusPen Pro X; configuration does not carry over.

### 中文

- **捏笔就是一根手指**：捏下 = 手指在指针处按下，移动 = 滑动，松开 = 抬起。点击、双击、长按、滚动、拖动都由应用自己识别。
- **系统样式指针**：用系统自带的鼠标箭头代替小米激光点。
- **边缘手势**：指针可到达屏幕边缘（可关）；在左右边缘捏住滑动可返回，在底部可回桌面、呼出 Dock。
- **防抖**：手指落在笔身歪斜前的位置（最多校正 40 像素），屏幕上的指针不跳动；在可调时长内忽略晃动；松开时消除笔身回弹，松手后 100ms 内指针保持不动。
- **点击像手指一样干脆**：轻捏松开后才瞬间点击，双击能被小窗等 200ms 判定窗口识别；松开时的回弹不会把点击变成拖动。
- **指针手感**：可调灵敏度和平滑（One Euro 滤波）。
- **生效范围**：所有应用都生效，黑名单应用保持小米原版。
- 激光关闭时的两连捏、四连捏全局动作和笔身滑动映射保留。
- **激光关闭时不干扰小米的轻捏**：轻捏功能为“快捷面板”时，未形成手势的轻捏按原间隔全部交还小米（双捏不再丢失）；为其他功能（如切换笔刷/橡皮）时，轻捏立即以一次干净的按下抬起交给应用，捏住不放也只算一次。
- 首次启动申请 HyperOS 的“获取应用列表”权限，授权后自动刷新。
- 开发：Hook 拆成固定外壳 + 可替换模块，debug 包重装后热重载，无需重启。

### English

- **The pinch is a finger**: pinch = finger down at the pointer, move = slide, release = lift. Apps recognise taps, double taps, long presses, scrolling and drags themselves.
- **System-style pointer** replaces Xiaomi's laser dot.
- **Edge gestures**: the pointer can reach the screen edges (switchable); pinch-swipe from the left/right edge for back, from the bottom for home and the dock.
- **Anti-jitter**: the finger lands where the pointer was before the squeeze tilted the pen (corrected by at most 40 px) while the drawn pointer stays put; wobble is ignored for an adjustable time; the release spring-back is removed and the pointer holds still for 100 ms after a release.
- **Finger-like taps**: a short pinch taps instantly on release, so double taps fit HyperOS's ~200 ms window (e.g. the small window); the release spring can no longer turn a tap into a drag.
- **Pointer feel**: adjustable sensitivity and smoothing (One Euro filter).
- **Scope**: every app except the blacklist.
- Laser-off two/four-pinch global actions and pen-body swipe mappings are kept.
- **Laser-off pinches leave Xiaomi's own pinch alone**: with the shortcut-panel function, pinches that form no gesture are all handed back with their original spacing (double pinches are no longer lost); with any other function (e.g. brush/eraser switch) the app gets one clean press-and-release at once, and a held pinch counts once.
- GitHub Actions: unit tests and APK builds on every push; tagged releases publish the APKs.
- Requests HyperOS's app-list permission on first launch and refreshes when granted.
- Development: hooks split into a fixed shell and a reloadable module; debug builds hot-reload on reinstall.

---

Before 0.1.0 the code was FocusPen Pro X by HMQYHM; see its history in the
[original repository](https://github.com/HMQYHM/FocusPenProX).
