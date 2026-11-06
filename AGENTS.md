# RyanMusic agent 约定（M1）

- 仓库：https://github.com/ahbbyzchr/RyanMusic ，默认分支 main。
- 方案：D（增强 WebView + 自托管 Node）。音频仍在 WebView；不要引入 ExoPlayer 解码播放。
- 包名：cn.ryanmusic.app。Kotlin 源码在 android/app/src/main/java/cn/ryanmusic/app/。
- 代码管理（所有 agent 必须遵守）：
  - 本地代码目录固定在 D:\Keep-Learning：主 clone 为 D:\Keep-Learning\RyanMusic（git clone https://github.com/ahbbyzchr/RyanMusic.git D:\Keep-Learning\RyanMusic）。不要把代码放到其他目录。
  - GitHub 仓库是唯一的准。只在本地 clone 上改，不要直接改任何远程服务器或部署环境，不要 force push。
  - main 只放可运行的稳定代码，任何 agent 不得直接提交到 main。
  - 集成分支 android-dev：M1 所有任务 PR 都合进它；M1 整体验收通过后才由仓库主人把 android-dev 合回 main 并打 v* tag 出包。
  - 任务分支：从最新的 android-dev 切出，一个任务一条分支、一个 PR、一个 agent。命名：m1/<编号>-短横线描述，例如 m1/t0-gradle-wrapper-sdk36、m1/b1-health-endpoint（后端同一仓库、同一规则）。
  - 多个 agent 同时干活时，每个任务用独立目录：在 D:\Keep-Learning\RyanMusic 里执行 git worktree add D:\Keep-Learning\RyanMusic-<编号> -b m1/<编号>-描述 origin/android-dev（例如 D:\Keep-Learning\RyanMusic-t3），该任务的 agent 只在这个目录里工作；任务合并后用 git worktree remove 清理。不要两个 agent 共用一个工作目录。
  - 开工前 git fetch 并基于最新 android-dev；提 PR 前 rebase 到最新 android-dev 并确认能构建。
  - PR 目标分支是 android-dev（不是 main）。PR 由仓库主人和审查方（蔡源）review 后再合并，agent 不要自己合并。
  - 只做本任务；发现别的问题写进 PR 描述的“遗留事项”，不要顺手改。
- Diff 要小：只改本任务列出的文件；不要顺手重构、不要改无关端（macos/windows）除非任务写明。
- 不要提交密钥：keystore、密码、RYANMUSIC_TOKEN、网易/QQ Cookie、session.json 一律不进 git。
- 已知问题：android/gradle/wrapper/gradle-wrapper.jar 损坏（外层 jar 包着真正的官方 8.7 wrapper；./gradlew 会 NoClassDefFoundError: org/gradle/wrapper/IDownload）。T0 会修；在修好前可用本机安装的 Gradle 8.7：在 android/ 下执行 `gradle assembleDebug`。
- 本地构建（安卓）：
  cd android && ./gradlew :app:assembleDebug
  （Windows PowerShell / CMD 下用 .\gradlew.bat :app:assembleDebug；需安装 JDK 17 和 Android SDK，并设置 ANDROID_HOME 或在 android/local.properties 写 sdk.dir）
  产物：android/app/build/outputs/apk/debug/app-debug.apk
- 本地构建（前端，仅 T4）：
  cd web && npm ci && npm run build
- 真机：Android 16（API 36）侧载；验收时必须连自托管（桌面版局域网地址或 Docker :8088），不要用 Vercel 验收长播放。
- 提交信息用中文或英文均可，但 PR 描述写清：改了什么、怎么验证、未做的事项。
