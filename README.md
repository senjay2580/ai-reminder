# AI提醒

个人用的 Android 提醒 app：像闹钟一样到点响铃 + 全屏弹窗（锁屏也弹），提前 10 分钟在通知栏倒计时；底部按住说一句话（仿微信），AI 理解后建 / 改 / 删提醒，确认后生效。重复方式：一次 / 每天 / 每周勾选几天 / 每月几号。

## 发新版

```bash
git commit -am "改了什么"   # 提交信息会成为 APP 里看到的更新说明
git push
git tag v1.2.0 && git push origin v1.2.0
```

推 tag 后 GitHub Actions 跑单测 → 构建 → 发 Release（约 3–4 分钟）。版本号由 tag 决定，不用手改 `build.gradle.kts`。
仓库是公开的，APP 检查更新、下载都不需要任何 token；下载后弹出系统安装界面，点「安装」即覆盖（同签名，数据保留）。

## 签名与密钥

- 签名 keystore **不在仓库里**：CI 从 Actions secret `KEYSTORE_B64`（base64）和 `KEYSTORE_PASSWORD` 还原。本地备份在 `.secrets/release.keystore` 与 `.secrets/keystore-pass.txt`（gitignore）。
- **不要换签名**，换了旧版就无法覆盖升级，只能卸载重装（数据清空）。v1.0.x 用的是旧签名，升到 v1.1.0 起需要卸载重装一次。
- AI Key 不进代码也不进 APK：在 APP 设置页填 New-API 里名为 `ai-reminder` 的令牌（本地副本 `.secrets/newapi-key.txt`）。接口默认 VPS 上的 New-API（墙外，手机要开代理）；模型逗号分隔，前一个失败自动换下一个。

## 首次使用

首页红卡片列出没开的权限，逐个点「去开启」；再用设置页「测试响铃」确认锁屏能响能弹。小米、华为等还要在系统设置里开「自启动」「后台弹出界面」「锁屏显示」。

## 结构

| 文件 | 作用 |
|---|---|
| `Task.kt` | 提醒模型；`next()` 算下次响铃（一次 / 每周几 / 每月几号，小月取月末），单测覆盖 |
| `Store.kt` | 提醒存 `tasks.json`；`Store.put` 是唯一写入口，顺带同步系统闹钟 |
| `Alarms.kt` | `setAlarmClock` 正点闹钟 + 提前 10 分钟闹钟；闹钟广播 / 开机重排接收器 |
| `AlarmService.kt` | 前台服务：循环放铃声 + 震动 + 全屏通知；同时响的几个一起列出；5 分钟无人理自动停 |
| `AlarmActivity.kt` | 锁屏上的响铃弹窗（关闭 / 稍后 10 分钟） |
| `Notices.kt` | 「即将提醒」常驻倒计时通知、「已新建」通知 |
| `Voice.kt` | 仿微信按住说话（SpeechRecognizer；没有识别服务时退回系统语音弹窗） |
| `Ai.kt` | 调 OpenAI 兼容接口；默认新建、明确说改/删才改删；输出逐条校验，不合格的写进回复不静默丢 |
| `Updater.kt` | 查公开 Release、下载 APK、调起安装 |
