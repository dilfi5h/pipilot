# INSTABOX

pipilot 的编译和单测跑在 instabox 的 Linux 盒子上，本地只负责改代码、装 APK。
盒子里有完整的 gradle / Android SDK / 签名，本机不需要装 Android SDK 也能出 release 包。

## 盒子

| 项 | 值 |
| --- | --- |
| 盒子 | `pipilot`（instabox free plan，4 vCPU / 8 GB，Debian 13） |
| 仓库 | `/data/workspace/pipilot`（`/data` 是持久盘，HOME 是 `/data/home`） |
| 用户 | `dilfish`，免密 sudo |
| Gradle | 8.9，装在 `/opt/gradle-8.9`，软链到 `/usr/local/bin/gradle` |
| Android SDK | `/data/android-sdk`：`platforms;android-35`、`build-tools;35.0.0`、`platform-tools` |
| JDK | 系统自带 OpenJDK 21（Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 都支持） |
| 签名 | `pipilot-release.keystore` + `keystore.properties` 已放在仓库根，被 `.gitignore` 忽略，不走同步 |
| `local.properties` | `sdk.dir=/data/android-sdk`，同样被忽略，不走同步 |

盒子闲置 30 分钟自动挂起，`instabox exec` / `instabox ssh` 会自动唤醒。
free plan 单次运行上限 480 分钟，到点强制挂起并冷却 10 分钟（`instabox get pipilot` 的 `cooldownUntil`）。

## 日常：一条命令

```sh
tools/instabox-build.sh
```

它做四件事：

1. `git bundle` 把当前分支同步过去，并在盒子上 `git reset --hard`，所以本地删除 / 改名的文件在盒子上也会消失；
2. `instabox push --all` 推未提交改动（含未跟踪但不被 ignore 的文件）；
3. 盒子上跑 `gradle :app:testDebugUnitTest assembleRelease`；
4. 把 `app/build/outputs/apk/release/app-release.apk` 拉回来覆盖本地同名文件。

额外 gradle 参数直接跟在后面，例如 `tools/instabox-build.sh --offline`。
盒子名可以用环境变量覆盖：`INSTABOX_BOX=other-box tools/instabox-build.sh`。

耗时参考：首次（下依赖）约 3 分钟；改几个 Kotlin 文件约 30–60 秒；无改动约 30 秒。
单测失败时脚本以非 0 退出，不会拉 APK。

## 手动操作

```sh
instabox ssh pipilot                                                             # 进盒子（exit 退出）
instabox exec pipilot --cwd /data/workspace/pipilot -- 'git status --porcelain'   # 看盒子上的状态
instabox ssh pipilot -- 'cd /data/workspace/pipilot && ANDROID_HOME=/data/android-sdk gradle :app:testDebugUnitTest'
instabox cp pipilot:/data/workspace/pipilot/app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/app-release.apk
instabox pull pipilot /data/workspace/pipilot/app/build/reports/tests/testDebugUnitTest /tmp/pipilot-test-report   # 单测报告
instabox get pipilot                                                             # 状态、运行时长、冷却
instabox suspend pipilot                                                         # 手动挂起（不用删盒子）
```

- `instabox exec` 最多 600 秒，长任务用 `instabox ssh`（无时间限制），或用 `instabox exec --background pipilot -- 'cmd'` 加 `instabox jobs logs pipilot <job> -f`。
- 看盒子上有什么进程：`instabox ps pipilot`。
- 盒子里的 git 没有远端凭据，只能编译，`git push` / `git pull` 在盒子上会失败；需要的话在盒子里 `gh auth login`。

## 注意

- **盒子是编译机，不要在盒子上改代码。** 脚本每次 `git reset --hard` + 推送，盒子上的改动会被覆盖。
- 签名文件和 `local.properties` 被 gitignore，同步不会碰它们，只在首次搭建时拷过一次。
- 盒子出的 APK 用同一把钥匙签名，可以直接覆盖手机上已装的包。
- 代码以本地为唯一真相，盒子掉了不影响仓库。
- 本地无改动但盒子行为诡异时，先 `instabox exec pipilot --cwd /data/workspace/pipilot -- 'git status --porcelain'` 对比；盒子上的残留未跟踪文件不会被自动清理（`git reset --hard` 只管已跟踪文件），需要时手动 `git clean -nd` 看一眼再清。
- 磁盘：`df -h / /data` 看用量。`/data` 放代码和 gradle 缓存，满了用 `instabox resize pipilot --disk N`（只能变大，需要停机）。

## 重建盒子

盒子被删或换新机器时（名字必须换或先 `rm`）：

```sh
instabox create --name pipilot --meta repo=pipilot
instabox ssh pipilot -- 'curl -fsSL -o /tmp/g.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip && sudo unzip -q -o /tmp/g.zip -d /opt && sudo ln -sf /opt/gradle-8.9/bin/gradle /usr/local/bin/gradle'
instabox ssh pipilot -- 'mkdir -p /data/android-sdk/cmdline-tools && curl -fsSL -o /tmp/c.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && unzip -q -o /tmp/c.zip -d /tmp/ct && mv /tmp/ct/cmdline-tools /data/android-sdk/cmdline-tools/latest && yes | /data/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/data/android-sdk --licenses >/dev/null && /data/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/data/android-sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"'
tools/instabox-build.sh
instabox cp pipilot-release.keystore pipilot:/data/workspace/pipilot/pipilot-release.keystore
instabox cp keystore.properties pipilot:/data/workspace/pipilot/keystore.properties
instabox exec pipilot -- 'printf "sdk.dir=/data/android-sdk\n" > /data/workspace/pipilot/local.properties'
tools/instabox-build.sh
```

（先跑一次脚本是为了把仓库推上去、建出目录，之后拷签名，再跑一次出签名包。）
