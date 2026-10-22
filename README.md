# AI提醒

个人用的 Android 提醒 app：像闹钟一样到点响铃 + 全屏弹窗（锁屏也弹），提前 10 分钟在通知栏倒计时；底部按住说一句话（仿微信）或发 1–3 张图（课程表、通知、聊天截图、票据……，可再补一句话），AI 理解后建 / 改 / 删提醒，确认后生效；AI 逐件判断是一次还是重复。重复方式：一次 / 每天 / 每周勾选几天 / 每月几号。右上角可切到月历视图，看每天要响什么。

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
- AI 令牌不进代码也不进 APK：在 APP 设置页填 `.secrets/arelay-token.txt` 里的令牌（VPS 上 `/opt/arelay/.app-token`）。接口默认 VPS 上的 arelay（agentrouter 多 key 网关，墙外，手机要开代理）；Caddy 只对这个令牌放行 `/v1/responses`，其它 `/v1` 一律 401。模型默认 `gpt-6.1-sol,gpt-6-astra`，前一个失败自动换下一个（请求带 `X-Arelay-Keep-Model: 1`，否则 arelay 会把模型钉成组设置）。
- 旧的 New-API 令牌（`.secrets/newapi-key.txt`，New-API token id 5）已不用；New-API 的 glm/deepseek 只开 chat/completions，换回去要先改回那个接口。

## 首次使用

首页红卡片列出没开的权限，逐个点「去开启」；再用设置页「测试响铃」确认锁屏能响能弹。

**清理后台也要响**：国产系统「一键清理」会强行停止本应用，系统闹钟随之全部取消。设置页「清理后台也要响」一节：「去开自启动」直达各品牌自启动页（找不到退到应用详情），再在多任务里锁定本应用，最后用「清后台测试」（1 分钟后响）实测。兜底：每次回到前台都重排全部闹钟；`Alarms` 记下每个提醒下一次该响的时间（`expect` 偏好），过点 1 分钟还没被改写 = 被杀过，首页红卡片列出来。

## Cortex 联动

服务器默认 `https://cortex.104.238.160.199.nip.io`（墙外，手机要开代理）。三种连法：

1. **一键连接**：手机浏览器打开 Cortex 网页，点「在手机上一键连接」→ 打开 `aireminder://cortex?server=<编码过的地址>&token=<设备令牌>`，App 存好地址和令牌、打开联动、马上同步并提示「已连接 Cortex」。地址必须 `https://`；链接指向的服务器和当前设置的不同时先弹框确认（那边能推闹钟、能看全部提醒）。
2. **账号连接**：设置页填 Cortex 账号密码点「用 Cortex 账号连接」→ `POST /api/login` 拿会话 cookie → `GET /api/device` 取令牌。密码只在这次请求里用，不存盘。
3. 手动贴设备令牌（Cortex 自动化 → 推送 里复制）。

- **收推送**：前台服务 `CortexService`（类型 `specialUse`，常驻低优先级通知「Cortex 联动中」）循环挂 `GET /api/device/wait` 长轮询；出错按 5 秒起翻倍、封顶 5 分钟退避。兜底是 WorkManager 每 15 分钟 `GET /api/device/pull`。开机 / 更新 / 回到前台都会重新拉起。
- **alarm** → 本机新建一次性提醒（标记来源推送 id），走正常全屏响铃；收到时已过点 ≤10 分钟马上响，更晚只发「错过的闹钟」通知，不补响。**notify** → 「Cortex 推送」通知，点开跳 `url`（`/` 开头的补服务器地址）。处理完 `POST /api/device/ack`；按推送 id 判重（ack 没送到会被推回来），已被确认过的不再处理。
- **网页改手机提醒**：`reminder_upsert`（data = id/title/time/repeat/enabled/note）按 id 原地改（铃声保留）、找不到就按这个 id 新建；网页新建的 id 是 `cx…`，存进提醒的 `cid` 字段，之后改 / 删都按它找。`reminder_delete` 按 id 删，本机没有就不动。落地 → ack → 马上整表上传（网页的「等手机确认」靠它清掉）。内容不合法弹通知说明、照常 ack；不认识的推送类型直接 ack 忽略。
- **传提醒**：任何改动（`Store.put`：新建 / 修改 / 删除 / 开关 / 一次性提醒响过）1 秒防抖后马上整表 `POST /api/device/reminders`，传不上交给 WorkManager 等有网重传；回到前台、每次定时任务也各传一次（全量替换；id = 网页 id 或本机数字 id；repeat = `once` / `daily` / `weekly:1,3,5` / `monthly:15`，星期 ISO 1=周一）。列表界面看 `Store.rev`，后台改了实时刷新。
- 失败不弹不崩，设置页状态行显示实时通道是否在线、上次同步时间和上次错误（服务端的 `{"error"}` 原话）；「立即同步」= 拉一次 + 上传一次。回到前台也自动拉一次 + 上传一次。
- 已知限制：不持 WakeLock，深度休眠时长轮询可能晚到，靠 15 分钟兜底与回到前台补拉；国产系统「一键清理」会连服务和定时任务一起停，照上面「清理后台也要响」设置。

## 结构

| 文件 | 作用 |
|---|---|
| `Task.kt` | 提醒模型；`next()` 算下次响铃（一次 / 每周几 / 每月几号，小月取月末）；`firesOn()` / `on()` 给月历用，单测覆盖 |
| `Store.kt` | 提醒存 `tasks.json`；`Store.put` 是唯一写入口，顺带同步系统闹钟、通知界面重读、触发上传 Cortex |
| `Alarms.kt` | `setAlarmClock` 正点闹钟 + 提前 10 分钟闹钟；记录预期响铃时间、检测被杀漏响；闹钟广播 / 开机重排接收器 |
| `AlarmService.kt` | 前台服务：循环放铃声 + 震动 + 全屏通知；同时响的几个一起列出；5 分钟无人理自动停 |
| `AlarmActivity.kt` | 锁屏上的响铃弹窗（关闭 / 稍后 10 分钟） |
| `Notices.kt` | 「即将提醒」常驻倒计时通知、「已新建」通知 |
| `Voice.kt` | 仿微信按住说话（SpeechRecognizer；没有识别服务时退回系统语音弹窗） |
| `Ai.kt` | 调 Responses 接口（agentrouter 的 gpt-6.x 只开这个，effort 只认 high）；图片按 EXIF 摆正、长边缩到 2048、白底 JPEG 发出；提示词写明一次 / 重复的判断信号；默认新建、明确说改/删才改删；输出逐条校验，不合格的写进回复不静默丢 |
| `Updater.kt` | 查公开 Release、下载 APK、调起安装 |
| `Cortex.kt` | Cortex 联动：推送处理决策（判重、晚到规则、重复格式映射、网页改 / 删提醒、一键连接链接解析，单测覆盖）、接口调用、账号登录换令牌、常驻长轮询服务、WorkManager 兜底 |
