# RyanMusic Android

独立 APK：WebView 壳 + 在线 Node 服务。安装后即可搜索、播放（仍需联网取流）。

搜索与取流必须经过后端签名代理（网易云 / QQ），所以各端本质是「同一套服务 + WebView」。电脑 / Docker / 线上站点统一使用 **Node（`server/`）**；Android 默认连接线上 Node 站点，也可切到局域网电脑或自建地址。

## 安装

1. 打开 [Releases](https://github.com/ahbbyzchr/RyanMusic/releases) 或源码仓 **Actions** 下载 `RyanMusic-android.apk`
2. 允许「安装未知应用」后安装
3. 仅支持 **64 位 ARM** 手机（近年安卓机基本都是）

首次启动若弹出通知权限，请允许，方便切到后台后继续播放。

返回键可切换：**本机服务 / 在线服务 / 自定义服务器**。

## 使用在线服务或局域网服务

默认会连接 [ryanmusic.vercel.app](https://ryanmusic.vercel.app)，一般无需再开电脑。

若线上站点较慢、或想走家里电脑的代理：

1. 电脑打开 RyanMusic（Mac / Windows 已监听局域网）
2. 手机与电脑连同一 Wi‑Fi
3. 电脑托盘 / 菜单选「复制手机访问地址」，粘贴到手机「自定义服务器」
4. 点「连接自定义地址」

也可自建：仓库根目录 `docker compose up`，或 `cd server && npm run dev -- --listen 0.0.0.0 --port 8088`。

## 开发者打包

```bash
cd web && npm ci && npm run build
cd android && ./gradlew assembleRelease
# 产物：android/app/build/outputs/apk/release/app-release.apk
```


## Release 签名

Release 不使用 debug 证书。请安全保存自己的 keystore 和密码；更换证书后，已安装的旧版本无法直接覆盖升级。Debug 构建不需要这些配置。

生成个人 keystore 的示例（在安全位置执行；根据提示输入密码并妥善备份）：

```bash
keytool -genkeypair -v -keystore ryanmusic-release.jks -alias ryanmusic -keyalg RSA -keysize 2048 -validity 10000
```

Release 构建前通过环境变量设置 `RYANMUSIC_STORE_FILE`（或 `ANDROID_KEYSTORE_PATH`）、`RYANMUSIC_STORE_PASSWORD`、`RYANMUSIC_KEY_ALIAS` 和 `RYANMUSIC_KEY_PASSWORD`，然后运行 `./gradlew :app:assembleRelease`。也可用同名 Gradle 属性（例如在不提交的用户级 `~/.gradle/gradle.properties` 中设置）；相对路径按 `android/` 目录解析。缺少配置时 Release 会报错。不要将密钥或密码提交到仓库，也不要在共享日志中打印密码。

Windows PowerShell 中，在 keystore 所在的安全目录执行以下命令，将生成文件的内容设置为 GitHub 仓库 Secret `ANDROID_KEYSTORE_BASE64`：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("ryanmusic-release.jks")) | Set-Content -NoNewline keystore.b64.txt
```

`keystore.b64.txt` 包含可还原的密钥材料，须像 keystore 一样保密，不要提交或分享；配置完成后安全删除该临时文件。

GitHub Actions 需配置仓库 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。工作流支持手动运行，也在推送 `v*` tag 时构建并发布到当前仓库的 Release。已有旧版若使用不同证书，首次切换正式证书时可能需要先卸载；请先备份应用数据。
