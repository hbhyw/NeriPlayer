# 手表端适配说明（Android 8.1 / API 27）

NeriPlayer 原版最低支持 Android 9（API 28）。本分支让它在 **Android 8.1（API 27）手表**上跑起来。

**设计取向：手表上直接使用功能完整的手机版界面**，只做「小屏密度适配」，
不另做精简界面——手表屏幕（本次实测 402×476、约 292×346dp）本质上就是一块小号手机屏，
另做一套界面只会丢功能。

---

## 1. 真机验证环境

| 项目 | 值 |
| --- | --- |
| 型号 | OW20W1（Qualcomm，国产 Android 手表） |
| 系统 | Android 8.1.0 / SDK 27 |
| 屏幕 | 402×476 px，density 320，override density 220（≈292×346dp，方屏） |
| ABI | armeabi-v7a |
| 特性 | 声明了 `android.hardware.type.watch` |

---

## 2. 改了哪些地方

### 2.1 最低系统版本 28 → 27

| 文件 | 改动 |
| --- | --- |
| `build-logic/convention/src/main/kotlin/Version.kt` | `minSdk = 27`，NDK 默认 `28.2.13676358` |
| `app/src/main/AndroidManifest.xml` | 内置 FFmpeg 解码 AAR 声明了 `minSdkVersion=28`，需要用 `tools:overrideLibrary` 放行 |
| `app/build.gradle.kts` | 新增 `-PwatchAbi=<abi>`：只打一个 ABI，包体减半，便于塞进手表 |

FFmpeg 扩展在 `ReactiveRenderersFactory` 里本来就用 `FfmpegLibrary.isAvailable()` 探测，
不可用时自动退回系统 MediaCodec，所以 27 上合并它是安全的。

### 2.2 三个真实的 API 28 调用点（不修必然崩）

这三个都是真机跑出来的，`NoSuchMethodError` / `NoClassDefFoundError` 在 API 27 上直接闪退：

| 位置 | 问题 | 处理 |
| --- | --- | --- |
| `NeriPlayerApplication.onCreate` | `Application.getProcessName()` 是 **API 28** 静态方法，启动第一屏就崩 | 新增 `ProcessNameCompat`（`:model` 模块）：P 以上用系统方法，低版本读 `/proc/self/cmdline` |
| `NeriUserDataDatabase` 主进程校验 | 同上 | 同一套兼容实现 |
| `AudioDownloadCoverImage` | 封面校验用 `ImageDecoder`（API 28） | API 27 退回 `BitmapFactory`（先 `inJustDecodeBounds` 取尺寸，再按预算采样），并补了文件头嗅探 mimeType |
| `NeriPlayerApplication` 多进程 WebView | `WebView.setDataDirectorySuffix`（API 28） | `SDK_INT < P` 直接跳过 |

### 2.3 手表界面的密度适配

手机版界面在 292dp 宽的屏幕上「一屏只放得下一行」。所以：

| 文件 | 改动 |
| --- | --- |
| `app/.../watch/WatchDevice.kt` | 手表识别：`FEATURE_WATCH` / uiMode=watch / 小圆屏兜底 |
| `app/.../activity/MainActivity.kt` | `WATCH_UI_DENSITY_FACTOR = 0.8f`，手表上把全局 UI 密度缩到 0.8（用户在设置里调的密度仍在此基础上生效） |

密度从 1.0 降到 0.8 后，等效画布从 292×346dp 变成约 365×432dp，接近小号手机，一屏能放 2~3 行内容。

### 2.4 小屏布局（正在播放 / 歌词页）

内存复用手机版 UI，只针对「画布高度约 432dp」这一事实做尺寸收敛：

| 文件 | 改动 |
| --- | --- |
| `NowPlayingAdaptiveLayout.kt` | 新增手表档：`shouldUseNowPlayingWatchLayout()`（高度 ≤460dp）、封面 `min(宽×0.22, 高×0.14, 150dp)`（真机约 60dp），控件基准 56/64dp |
| `NowPlayingScreen.kt` | 手表档用固定高度封面，把省下的空间让给播放控件 |
| `HomeScreen.kt` | `screenHeightDp ≤ 420` 时用紧凑 `TopAppBar`，应用名（音理音理）移到顶部导航栏 |
| `LyricsScreen.kt` | 视口 <460dp 且宽 <600dp 判为紧凑：标题与歌词之间 16dp→4dp、底部控件内边距 10dp→4dp、歌词基准字号 20→17sp、当前行视口比例 0.40→0.32 |
| `SyncedLyricsView.kt` / `AdvancedLyricsView.kt` | **关键修复**：上下渐隐遮罩（72dp / 80+196dp）在矮视口会盖住整屏歌词，导致「当前歌词看不见 / 发白」；现在矮视口下遮罩收敛为视口高度的 16%（顶部）与 20%（底部），手机/平板视口不受影响 |

### 2.5 构建脚本

| 文件 | 改动 |
| --- | --- |
| `gradle/wrapper/gradle-wrapper.properties` | 下载源换成腾讯云镜像（`services.gradle.org` 在国内会超时） |

---

## 3. 构建

环境：JDK 17、`platforms;android-37.0`、`build-tools;37.0.0`、`ndk;28.2.13676358`、`cmake;3.22.1`。

```powershell
$env:JAVA_HOME="C:\tools\jdk17\jdk-17.0.20+8"
$env:ANDROID_HOME="C:\tools\android-sdk"

# 手表用：只打 armeabi-v7a 的 release 包（R8 压缩 + 签名，包体最小）
.\gradlew.bat :app:assembleRelease -PwatchAbi=armeabi-v7a `
  -PKEYSTORE_FILE=C:\path\to\watch-release.jks `
  -PKEYSTORE_PASSWORD=****** -PKEY_ALIAS=****** -PKEY_PASSWORD=******

# 调试包（体积大，装不进去时用下面的腾空间办法）
.\gradlew.bat :app:assembleDebug -PwatchAbi=armeabi-v7a
```

产物：`app/build/outputs/apk/release/`、`app/build/outputs/apk/debug/app-debug.apk`。

本机实测：release **13.3MB**（R8 压缩 + 单 ABI，`NeriPlayer-d61c977.10181218.apk`），debug **51.4MB**。
`neri/NeriPlayer-watch-armeabi-v7a.apk` 就是这份 release 包（已装到 OW20W1 上验证过启动）。

> `watchAbi` 只影响打包的 ABI；不带这个参数时行为与原版一致（release 默认 arm64-v8a）。
> `lint { checkReleaseBuilds = false }` 是为了让 `assembleRelease` 不被 `lintVitalRelease` 拖住，
> 发布前请单独跑一次 `:app:lintDebug`。
> `KEYSTORE_FILE` 用**绝对路径**：`app/build.gradle.kts` 里的 `project.file(...)` 是相对 `app/` 解析的，
> 写相对路径会静默拿不到 keystore，构建最后一步失败。

---

## 4. 安装到手表

```powershell
adb devices
# 空间不足时（这台 ROM 会直接报 INSTALL_FAILED_INSUFFICIENT_STORAGE）：
adb uninstall -k moe.ouom.neriplayer   # 保留数据，只卸 APK
adb install -r NeriPlayer-<version>-armeabi-v7a.apk
```

实测这台手表 `/data` 需要约 700MB 以上空闲才肯安装，低于阈值会报
`Requested internal only, but not enough space`；`adb install` 和 `pm install` 都会撞同一阈值。

---

## 5. 实测结果（OW20W1 真机）

| 检查项 | 结果 |
| --- | --- |
| 安装 / 启动 | ✅ 修完 3 个 API 28 调用点后无崩溃 |
| 首次启动流程 | ✅ 免责声明 → 语言 → 平台 → 播放源兜底 → 歌词预览 → 进入主界面 |
| 首页 | ✅ 标题、私人雷达列表（一屏 3 行封面+歌曲）、mini 播放器、底部四 Tab |
| 正在播放 | ✅ 封面约 60dp 居中、歌名在顶部导航栏、进度条（01:33 / 04:01）、随机/上一首/播放/下一首/循环放大到 56/64dp、底部二级功能条 |
| 歌词页 | ✅ 当前歌词高亮清晰（渐隐遮罩按视口收敛后不再「发白/看不见」），上一句/下一句可见，逐行自动滚动 |
| 资料库 | ✅ 平台 Tab（本地/收藏/网易云/YouTube/哔哩）、歌单/歌手子 Tab、搜索框、新建歌单 |
| 实际播放 | ✅ 正在播放网易云的歌曲，进度正常推进 |
| 触摸 / 点击 | ✅ 正常；`adb shell input tap` 也可用（`input swipe` 在这台 ROM 上被拒） |

> 密度 0.8 是关键：默认密度下首页一屏只放得下一行，缩到 0.8 后一屏 3 行且不憋屈。

---

## 6. 已知限制

- **引导页头部会被裁切**：引导页顶部大标题按长屏设计，短屏上第二行会被截断（内容区可滚动，功能不受影响）。密度 0.8 已缓解。
- **歌词页纵向空间仍然紧张**：432dp 高的画布上，顶部栏 + 进度 + 播放控件 + 二级功能条占掉约 2/3，歌词区只剩约 150dp（约 2~3 行）。
  已通过压缩留白、字号 20→17sp、当前行放在视口 32% 处来适配；再想要更多行只能把二级功能条挪走（会少几个入口，暂未做）。
- **release 打包在本机会卡在 `lintVitalRelease`**：已设置 `lint { checkReleaseBuilds = false }` 跳过它；发布前单独跑 `:app:lintDebug` 即可。
- **存储紧张**：这台手表 `/data` 约 4GB、装完系统后只剩 500~800MB，
  51MB 的 debug 包需要先 `adb shell pm trim-caches 3000M` 才能安装；release 包明显更小。
- **在线音源需要登录态**：网易云/YouTube 需要 Cookie；B 站未登录也能搜索播放。
- **不做 AOD/常亮表盘**：播放依赖 `AudioPlayerService` 前台服务，息屏继续播。
- **未逐一验证**：下载、一起听、音效、USB 独占等重功能没有在手表上逐一试过。
