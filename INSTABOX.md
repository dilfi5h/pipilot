# INSTABOX

pipilot 的编译和单测跑在 instabox 的 Linux 盒子上，本地只负责改代码、装 APK。
本机不需要装 Android SDK 也能出 release 包。

## 机器：只用 `ib`

**不允许用 `instabox create` 新建机器，一律用默认的 `ib`。**
装工具链、升 SDK、清缓存都在 `ib` 上做，不要另起一台。

| 项 | 值 |
| --- | --- |
| 盒子 | `ib`（instabox free plan，4 vCPU / 8 GB，`agent-browser` 模板，Debian 13） |
| 仓库 | `/data/workspace/pipilot`（`/data` 是持久盘，HOME 是 `/data/home`） |
| 用户 | `user`（免密 sudo；注意不是本地用户名 `dilfish`） |
| Gradle | 8.9，`/opt/gradle-8.9`，软链到 `/usr/local/bin/gradle` |
| Android SDK | `/data/android-sdk`：`platforms;android-35`、`build-tools;35.0.0`、`platform-tools` |
| JDK | 系统自带 OpenJDK 21（Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 都支持） |
| 签名 | `pipilot-release.keystore` + `keystore.properties` 放在仓库根，被 `.gitignore` 忽略，不走同步 |
| `local.properties` | `sdk.dir=/data/android-sdk`，同样被忽略，不走同步 |

上面 Gradle / SDK / 仓库这几项是**首次准备**（见文末）之后才有的状态；`ib` 刚恢复或换新时先跑一次准备步骤。

闲置 30 分钟会自动挂起，`instabox exec` / `instabox ssh` 会自动唤醒它。
单次运行上限看 `instabox get ib` 的 `maxRunMinutes`（`ib` 是 **480 分钟**），到点强制挂起并冷却 10 分钟（`cooldownUntil`）。

**手动挂起的盒子不会自动唤醒**：`instabox suspend ib` 之后，`exec` / `ssh` 会直接报 `sandbox ib is paused; run instabox start ib`，必须先 `instabox start ib`。所以平时别手动 suspend，交给空闲自动挂起即可，脚本也能用。

## 日常：一条命令

```sh
tools/instabox-build.sh
```

它做四件事：

1. `git bundle` 把当前分支同步过去，并在盒子上 `git reset --hard`，所以本地删除 / 改名的文件在盒子上也会消失；
2. `instabox push --all` 推未提交改动（含未跟踪但不被 ignore 的文件）；
3. 盒子上跑 `gradle :app:testDebugUnitTest assembleRelease`；
4. 把 `app/build/outputs/apk/release/app-release.apk` 拉回来覆盖本地同名文件。

脚本开头会 `instabox start ib` 兜底（盒子已运行就是空操作），所以手动 suspend 过的盒子也能直接跑。
额外 gradle 参数直接跟在后面，例如 `tools/instabox-build.sh --offline`。

耗时参考：首次（下依赖）约 3 分钟；改几个 Kotlin 文件约 30–60 秒；无改动约 30 秒。
单测失败时脚本以非 0 退出，不会拉 APK。

## 手动操作

```sh
instabox ssh ib                                                             # 进盒子（exit 退出）
instabox exec ib --cwd /data/workspace/pipilot -- 'git status --porcelain'   # 看盒子上的状态
instabox ssh ib -- 'cd /data/workspace/pipilot && ANDROID_HOME=/data/android-sdk gradle :app:testDebugUnitTest'
instabox cp ib:/data/workspace/pipilot/app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/app-release.apk
instabox pull ib /data/workspace/pipilot/app/build/reports/tests/testDebugUnitTest /tmp/pipilot-test-report   # 单测报告
instabox get ib                                                             # 状态、运行时长、冷却
instabox suspend ib                                                         # 手动挂起（之后要先 start）
```

- `instabox exec` 最多 600 秒，长任务用 `instabox ssh`（无时间限制），或用 `instabox exec --background ib -- 'cmd'` 加 `instabox jobs logs ib <job> -f`。
- 看盒子上有什么进程：`instabox ps ib`。
- 盒子里的 git 没有远端凭据，只能编译，`git push` / `git pull` 在盒子上会失败；需要的话在盒子里 `gh auth login`。

## 注意

- **盒子是编译机，不要在盒子上改代码。** 脚本每次 `git reset --hard` + 推送，盒子上的改动会被覆盖。
- 签名文件和 `local.properties` 被 gitignore，同步不会碰它们；脚本发现盒子上缺 `keystore.properties` 时会自动补拷一次。
- 盒子出的 APK 用同一把钥匙签名，可以直接覆盖手机上已装的包。
- 代码以本地为唯一真相，盒子掉了不影响仓库。
- 本地无改动但盒子行为诡异时，先 `instabox exec ib --cwd /data/workspace/pipilot -- 'git status --porcelain'` 对比；盒子上的残留未跟踪文件不会被自动清理（`git reset --hard` 只管已跟踪文件），需要时手动 `git clean -nd` 看一眼再清。
- 磁盘：`df -h / /data` 看用量。`/data` 放代码和 gradle 缓存。

## 首次准备（在 `ib` 上做一次）

`ib` 的 JDK 21 是模板自带的，缺的只有 Gradle 和 Android SDK。装完直接跑脚本，剩下的它自己补：

```sh
instabox start ib
instabox ssh ib -- 'curl -fsSL -o /tmp/g.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip && sudo unzip -q -o /tmp/g.zip -d /opt && sudo ln -sf /opt/gradle-8.9/bin/gradle /usr/local/bin/gradle'
instabox ssh ib -- 'mkdir -p /data/android-sdk/cmdline-tools && curl -fsSL -o /tmp/c.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && unzip -q -o /tmp/c.zip -d /tmp/ct && mv /tmp/ct/cmdline-tools /data/android-sdk/cmdline-tools/latest && yes | /data/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/data/android-sdk --licenses >/dev/null && /data/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/data/android-sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"'
tools/instabox-build.sh
```

脚本负责剩下的：

- `/data/workspace/pipilot` 不存在时，从 `git bundle` 直接 `git clone`，之后每次改成 `fetch` + `reset --hard`；
- 盒子上的 `keystore.properties` 不存在时，自动补拷 `pipilot-release.keystore`、`keystore.properties` 并写 `local.properties`（被 gitignore，日常同步不碰它们）。

实测：工具链装完到出包约 3–4 分钟（首次要下 gradle 依赖）。

升 `compileSdk` 时只补对应组件，不要新建机器：

```sh
instabox ssh ib -- '/data/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/data/android-sdk "platforms;android-36" "build-tools;36.0.0"'
```

升 `compileSdk` 时只补对应组件，不要新建机器：

```sh
instabox ssh ib -- '/data/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/data/android-sdk "platforms;android-36" "build-tools;36.0.0"'
```
