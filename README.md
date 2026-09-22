# RainLove ❤️🎵

当心率连续达到自定义阈值时，播放用户选择的本地音乐，或打开指定 BV 号的哔哩哔哩视频。项目最初为 Garmin fēnix 6X Sapphire 设计，也兼容采用标准 Bluetooth LE Heart Rate Service 的心率设备。

> 娱乐项目，不是医疗器械，不提供心率异常诊断或健康建议。

## 当前功能

- 标准 BLE Heart Rate Service（UUID `0x180D`）扫描与实时 BPM 读取
- 可切换 ANT+ 心率数据源，自动搜索、断线重连，并检查所需的 ANT+ 系统服务
- 扫描并手动选择兼容心率设备，记住上次连接设备
- BLE 意外断开后自动重连
- 前台服务持续监测，切到后台或锁屏后仍可触发音乐
- 可选开机自动恢复：仅在关机前处于真实设备监测状态时尝试恢复
- 本地心率采样历史、最近 300 点曲线及完整 CSV 导出（包含 Demo 采样，最多每秒记录一次）
- 可保存多套命名触发方案，在停止监测后切换或确认删除方案
- 自定义触发心率和恢复心率
- 自定义触发保持、恢复保持和冷却时长，并自动保存设置
- 连续超阈值后才触发，避免瞬时误触；即使手表在心率稳定时降低通知频率，也会按保持时长推进
- 恢复成立后暂停，并进入冷却
- 从 Android 文件选择器选择任意本地音频
- 记住所选音乐，并显示真实文件名
- 可在开始监测前测试本地音乐；播放失败时显示文件或解码错误
- 保存指定 BV 号；RainLove 在前台触发时直接打开哔哩哔哩，打开后可延迟发送播放命令；可选择授权后台直接跳转，否则通过通知安全降级
- 支持网易云歌曲 ID、歌曲链接和 `orpheus://song/...` 链接；优先打开网易云音乐 App，并可尝试自动播放
- 可配置其他音乐 App 支持的歌曲链接或深链，选填目标 App 包名；是否能定位歌曲由目标 App 决定
- Demo 模式，无需手表即可拖动虚拟心率测试；保持超过设定时长后会自动触发
- 纯 Kotlin 状态机及单元测试

## Garmin fēnix 6X Sapphire 使用方法

1. 在手表进入“心率”小组件。
2. 长按 `MENU`，进入“心率选项”。
3. 开启“广播心率”。
4. 在 RainLove 关闭 Demo 模式，授权附近设备权限并开启心动模式。

也可以在关闭 Demo 模式后选择 `ANT+`。手机需要 ANT+ 硬件或兼容的 ANT USB 适配器，并安装 ANT Radio Service 与 ANT+ Plugins Service；RainLove 会自动连接第一个可用的 ANT+ 心率设备。没有 ANT+ 环境时请继续使用默认的 Bluetooth LE。

“开机自动恢复监测”默认关闭。开启后，只有关机前仍在监测、Demo 已关闭且权限仍有效，设备重启并解锁后才会尝试重启前台监测。Android 12 及更高版本使用 ANT+ 时仍需授予附近设备权限，以满足系统对连接设备前台服务的要求；这不代表 ANT+ 数据通过蓝牙传输。

## 本地运行

1. 使用 Android Studio 打开项目根目录。
2. 使用 JDK 17，同步 Gradle。
3. 连接 Android 8.0（API 26）或更高版本的真机。
4. 运行 `app`。

项目不附带任何商业音乐。请使用“选择本地音乐”选择你有权使用的音频文件。

个人使用的 Debug 图标可以放在 `app/src/debug/res/drawable/ic_launcher.jpg`。该路径已被 Git 忽略，只影响本机 Debug APK；公开仓库和 Release APK 仍使用项目自带的默认图标。请勿把没有公开使用许可的图片打包分发。

## 发布构建

运行 `.\gradlew.bat testDebugUnitTest lintDebug assembleRelease` 可检查代码并生成未签名的 Release APK。未签名产物仅用于构建检查，不能直接作为正式安装包分发。正式发布前，请在 Android Studio 中创建并妥善备份自己的密钥库，再于当前 PowerShell 会话设置以下四个环境变量；不要把密钥库或密码提交到仓库：

```powershell
$env:RAINLOVE_RELEASE_STORE_FILE = 'D:\安全位置\rainlove-release.jks'
$env:RAINLOVE_RELEASE_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '密钥库密码' -AsSecureString)).Password
$env:RAINLOVE_RELEASE_KEY_ALIAS = '<密钥别名>'
$env:RAINLOVE_RELEASE_KEY_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '密钥密码' -AsSecureString)).Password
.\gradlew.bat assembleRelease
```

四项都设置后，Gradle 会签名 Release APK；缺少任意一项会直接报错，避免误把未签名版本当作发布包。构建后请用 Android SDK 的 `apksigner verify --print-certs` 检查签名，并在真机上测试后再发布。签名密钥丢失可能影响后续更新，请安全保管。仓库会忽略 `.jks` 和 `.keystore` 文件。

哔哩哔哩模式接受 BV 号或包含 BV 号的完整视频链接。启用“打开后尝试自动播放”时，RainLove 会打开哔哩哔哩 App，并在播放器加载期间发送三次播放命令。该行为会受哔哩哔哩版本、网络和页面状态影响。RainLove 界面在前台时，心率触发会直接启动链路。Android 10 及更高版本会限制后台界面启动；如需后台直接跳转，可主动开启开关并授予“显示在其他应用上层”权限。RainLove 不会创建悬浮窗，仅使用该权限满足系统的后台启动例外；未授权或权限被撤销时安全降级为通知。外部播放器启动后，RainLove 不会在心率恢复时强制关闭它。

网易云模式接受纯数字歌曲 ID、`music.163.com` / `y.music.163.com` 歌曲分享链接和 `orpheus://song/...` 链接。RainLove 会先尝试网易云音乐 App 的歌曲深链，再尝试由 App 打开标准歌曲网页；未安装网易云音乐时回退到浏览器。自动播放同样通过延迟媒体播放命令尝试，能否成功取决于网易云音乐版本、登录状态、版权和页面状态。

“其他音乐 App 链接”模式接受完整 `http(s)://` 歌曲链接或目标 App 自己支持的深链。可选填 Android 包名以限定打开目标；留空时由 Android 选择可处理该链接的 App。只有指定了包名，才可选择尝试发送播放命令。请先使用“测试打开链接”确认实际打开的是目标内容。RainLove 不会绕过第三方 App 的登录、会员或版权限制，也无法通用地保证每个音乐 App 自动播放。

在“触发方案”中输入名称可保存或覆盖当前配置，停止监测后点击方案名即可切换，也可以经确认后删除方案；删除不会改变当前设置。方案包含阈值、保持时间、Demo/设备类型与所选设备、播放目标和媒体链接；“开机自动恢复监测”是全局选项，不随方案切换。本地音乐方案依赖先前授予的文件读取权限，文件移动或权限失效时需要重新选择。

## 触发状态机

`ARMED → HIGH_PENDING → PLAYING → RECOVERY_PENDING → COOLDOWN → ARMED`

- 心率达到触发值：进入 `HIGH_PENDING`
- 连续保持 5 秒：开始播放
- 心率降至恢复值：等待连续恢复 10 秒
- 恢复成立：暂停并冷却 60 秒

## 路线图

- [x] 持久化触发时长、恢复时长与冷却时间设置
- [x] 前台服务与锁屏后台运行
- [x] 记住上次连接的心率设备
- [x] ANT+ Heart Rate 数据源
- [x] 设备选择界面和断线重连

## 隐私与版权

心率数据只在设备本地用于触发，不上传服务器。本项目不包含《雨爱》或其他受版权保护的音频。
Android 自动备份已关闭；导出 CSV 仅在用户主动选择保存位置时进行，RainLove 不会自动上传历史记录。

## License

[MIT](LICENSE)

项目包含的 ANT+ PluginLib 使用 ANT+ Shared Source License，详情见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
