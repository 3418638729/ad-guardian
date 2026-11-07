# 截图目录

主 README 的「界面」一节引用了下面三张图。**文件名必须完全一致**，
否则图片会显示为裂图。

| 文件名 | 内容 | 状态 |
|---|---|---|
| `check.png` | 一切正常，五项全绿 | ✅ 已拍 |
| `broken.png` | 发现 2 个问题（AdAway VPN 未运行） | ✅ 已拍 |
| `fix.png` | 点「修复」后跳到 AdAway 首页 | ✅ 已拍 |

拍摄方式：`adb exec-out screencap -p > 文件名.png`，直接落到本目录。

重新拍时的注意事项：

- 手机截图直出，不要用另一台手机拍屏幕
- 🚫 **打码**状态栏里的手机号、运营商、通知内容
- 三张图的风格统一（同一台手机、同一套主题）

**复现三种状态的方法：**

```bash
# 全绿：确认 AdAway 的 VPN 在跑
adb shell ip -br addr | grep tun0

# 故障：强制停掉 AdAway，VPN 隧道会一起断
adb shell am force-stop org.adaway
```

> 注意：`force-stop org.adaway` 之后要重新打开 AdAway 并点一下底部圆形按钮，
> 否则 VPN 不会自己恢复。
