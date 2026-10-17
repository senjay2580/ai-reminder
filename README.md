# AI提醒

个人用的 Android 提醒 app：像闹钟一样到点响铃 + 全屏弹窗（锁屏也弹），语音或文字说一句话，AI 理解后建 / 改 / 删提醒，确认后生效。

## 发新版

```bash
git commit -am "改了什么"   # 提交信息会成为 APP 里看到的更新说明
git push
git tag v1.0.1 && git push origin v1.0.1
```

推 tag 后 GitHub Actions 跑单测 → 构建 → 发 Release（约 3 分钟）。版本号由 tag 决定，不用手改 `build.gradle.kts`。
APP 启动时会自动检查，设置页也可手动「检查更新」，下载后弹出系统安装界面，点「安装」即覆盖（同签名，数据保留）。

## 首次配置（手机上，设置页）

- **AI**：接口地址默认 VPS 上的 New-API（墙外，手机要开代理才能连）；Key 填 New-API 里名为 `ai-reminder` 的令牌；模型逗号分隔，前一个失败自动换下一个。
- **GitHub Token**：仓库是私有的，检查更新要 token。到 <https://github.com/settings/personal-access-tokens/new> 建 fine-grained token：Repository access 只选 `ai-reminder`，Permissions → Contents: Read-only。
- 首页红卡片列出没开的权限，逐个点「去开启」；再用设置页「测试响铃」确认锁屏能响能弹。

## 结构

| 文件 | 作用 |
|---|---|
| `Task.kt` | 提醒模型；`next()` 算下次响铃时间（单测覆盖） |
| `Store.kt` | 提醒存 `tasks.json`；`Store.put` 是唯一写入口，顺带同步系统闹钟 |
| `Alarms.kt` | `setAlarmClock` 排程；闹钟广播 / 开机重排接收器 |
| `AlarmService.kt` | 前台服务：循环放铃声 + 震动 + 全屏通知，5 分钟无人理自动停 |
| `AlarmActivity.kt` | 锁屏上的响铃弹窗（关闭 / 稍后 10 分钟） |
| `Ai.kt` | 调 OpenAI 兼容接口；AI 输出逐条校验，不合格的写进回复不静默丢 |
| `Updater.kt` | 查私有仓库 Release、下载 APK、调起安装 |

签名 keystore `android/app/reminder.keystore` 已入库（私有仓库）。**不要换它**，换了旧版就无法覆盖升级。
