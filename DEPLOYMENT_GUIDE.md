# Navipilot APP 闪退修复与工程化部署指南

> 分支：`Amapauto`（已推送至 `mouxangithub/navipilot`）
> 验证环境：Android Gradle Plugin 8.6.0 / Kotlin 2.1.0 / compileSdk 35 / minSdk 29

---

## 1. APP 闪退根因分析与修复

### 1.1 根本原因

崩溃堆栈集中在 `ScreenMirrorActivity` 的触摸发送线程：

```text
java.lang.InterruptedException
    at java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.reportInterruptAfterWait(AbstractQueuedSynchronizer.java:2025)
    at java.util.concurrent.LinkedBlockingQueue.poll(LinkedBlockingQueue.java:430)
    at com.jixiexiaoge.drivingassist.ScreenMirrorActivity$startTouchChannel$2.invoke(ScreenMirrorActivity.kt:331)
```

**根因**：`touchQueue.poll(1, TimeUnit.SECONDS)` 在持有锁的 Condition 上等待时，如果线程被 `interrupt()`，会抛出 `InterruptedException`。原代码没有 catch，导致 `FATAL EXCEPTION: Thread-12`，App 直接崩溃。

触发路径：
1. `onPause()` / `onDestroy()` / `connectThread` 重连 都会调用 `touchThread?.interrupt()`。
2. `LinkedBlockingQueue.poll(timeout, unit)` 的 Javadoc 明确声明会抛 `InterruptedException`。
3. 原实现只 catch 了 `sleep()` 的 InterruptedException，遗漏了 `poll()`。

### 1.2 已落地的修复

文件：`app/src/main/java/com/jixiexiaoge/drivingassist/ScreenMirrorActivity.kt`

- **poll() 异常捕获**：把 `touchQueue.poll(...)` 包进 `try/catch(InterruptedException)`，收到中断后恢复标志、退出循环、关闭 socket。
- **重复线程防护**：`startTouchChannel()` 入口处检查 `touchThread != null && isAlive`，避免 `onResume()` + `startAutoConnect()` 双重启动。
- **生命周期安全的多设备选择弹窗**：`askUserToPickDevice()` 使用 `CountDownLatch` 等待用户选择，但旧实现如果在弹窗期间 Activity 被销毁，发现线程会永远阻塞。现在把 latch 保存到成员变量 `devicePickLatch`，在 `onPause()` / `onDestroy()` 中 `countDown()` 强制释放。
- **OTA 回调保护**：`AppUpdater.checkLatest` 的回调先判断 `isFinishing || isDestroyed`，避免 Activity 销毁后弹 Toast/Dialog 引发 `BadTokenException`。
- **libVLC 初始化保护**：`LibVLC(applicationContext)` 和 `MediaPlayer` 创建包进 try/catch，失败时记录日志并提示用户，而不是直接崩溃。

### 1.3 验证步骤

```bash
# 本地构建 debug APK
./gradlew assembleDebug -Pnavipilot.abis=arm64-v8a

# 安装到手机测试闪退场景
adb install -r -d app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

重点验证：
1. 进入 `ScreenMirrorActivity` 后快速按 Home / 返回，反复进出，不应再崩溃。
2. 连接过程中断网/切换 Wi-Fi，自动重连时不崩溃。
3. 发现多台设备弹窗时，按 Home 键或返回，不应 ANR/崩溃。

---

## 2. APK 体积优化与压缩

### 2.1 当前体积构成

| 构建类型 | 文件 | 体积 | 主要占比 |
|---|---|---|---|
| 优化前 debug | app-arm64-v8a-debug.apk | ~59 MB | libvlc.so 41 MB (69.6%) |
| 优化后 release | app-arm64-v8a-release.apk | **67.28 MB** | libvlc.so 41 MB (61.1%), dex 16 MB (23.8%) |

> 注：release 比 debug 略大是因为 debug 的 `isDebuggable=true` 会禁用 R8 优化；release 启用 R8 后 dex 被压缩到 16 MB。

### 2.2 已做的优化

文件：`app/build.gradle.kts`

1. **多 ABI 分包**：默认仅 `arm64-v8a`；CI 可通过 `-Pnavipilot.abis=arm64-v8a,armeabi-v7a,x86_64` 输出多包。
2. **R8 全模式 + 资源裁剪**：
   ```kotlin
   isMinifyEnabled = true
   isShrinkResources = true
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
   ```
3. **Native lib 压缩**：`packaging.jniLibs.useLegacyPackaging = false`，让 so 在 APK 中保持压缩。
4. **剔除 libVLC 非必要资源**：`assets/subtitles/**`、`lua/meta`、`lua/extensions`、`lua/sd`。
5. **修正 ProGuard 包名**：原规则里大量 `com.example.navipilot` 是错误包名，已改为 `com.jixiexiaoge.drivingassist`，让真正的业务代码可以被混淆/裁剪。
6. **PNG 压缩**：release 开启 `isCrunchPngs = true`。

### 2.3 进一步压缩空间（可选）

**最大头像是 `libvlc-all`（41 MB）**。App 只需要播放车机 `screencastd` 的 **MPEG-TS / H.264 over TCP**，完全不需要 libVLC 的全部功能。

推荐方案（可再减 30-40 MB）：

#### 方案 A：切换为精简版 libvlc

```kotlin
implementation("org.videolan.android:libvlc:3.6.5")
```

`libvlc` AAR 比 `libvlc-all` 小很多，但需验证是否支持 MPEG-TS/H.264。

#### 方案 B：替换为 ExoPlayer + FFmpeg extension

```kotlin
implementation("androidx.media3:media3-exoplayer:1.4.0")
implementation("androidx.media3:media3-exoplayer-ffmpeg:1.4.0") // 仅 H.264 软解兜底
```

优点：
- 系统有 MediaCodec 硬解时几乎不增加 so 体积；
- FFmpeg extension 可以只编译 H.264 decoder，体积极小；
- 与 Android 生命周期/Surface 集成更稳定。

`ScreenMirrorActivity.startVideo()` 对应改造示例：

```kotlin
private fun startVideo(ip: String) {
    val player = exoPlayer ?: return
    val mediaItem = MediaItem.fromUri("tcp://$ip:$VIDEO_PORT")
    player.setMediaItem(mediaItem)
    player.prepare()
    player.play()
}
```

> 注意：TCP MPEG-TS 不是标准 HTTP/RTSP，ExoPlayer 默认可能不支持。需要自定义 `DataSource` 读取 TCP socket，或用 `UdpDataSource`/FFmpeg 容器解析。此改造属于 P1，建议单独开一个分支验证。

---

## 3. 多平台打包与发布

### 3.1 已配置

`app/build.gradle.kts`：

```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86_64")
        isUniversalApk = false
    }
}

bundle { /* AAB */ }
```

### 3.2 本地构建命令

```bash
# 仅 arm64 release APK
./gradlew assembleRelease -Pnavipilot.abis=arm64-v8a \
  -PRELEASE_STORE_PASSWORD=xxx -PRELEASE_KEY_PASSWORD=xxx -PRELEASE_KEY_ALIAS=xxx

# 多 ABI release APK（输出 app-arm64-v8a-release.apk / app-armeabi-v7a-release.apk / app-x86_64-release.apk）
./gradlew assembleRelease -Pnavipilot.abis=arm64-v8a,armeabi-v7a,x86_64 \
  -PRELEASE_STORE_PASSWORD=xxx -PRELEASE_KEY_PASSWORD=xxx -PRELEASE_KEY_ALIAS=xxx

# AAB（Google Play 用，自动按设备 ABI 分发）
./gradlew bundleRelease \
  -PRELEASE_STORE_PASSWORD=xxx -PRELEASE_KEY_PASSWORD=xxx -PRELEASE_KEY_ALIAS=xxx
```

### 3.3 分发策略

| 渠道 | 产物 | 适用场景 |
|---|---|---|
| 自托管 / GitHub Release | multi-ABI APK zip | 用户侧载安装 |
| Google Play | AAB | 自动按设备分发最小包 |
| 车载/离线 | arm64-v8a APK | C3 车机通过 adb 安装 |

---

## 4. OTA 在线更新与可视化进度

### 4.1 实现

文件：`app/src/main/java/com/jixiexiaoge/drivingassist/AppUpdater.kt`

- 启动时检查 GitHub `releases/latest`；
- tag 形如 `r260901`，解析数字作为 versionCode；
- 大于本地 versionCode 时弹窗提示；
- 点击下载后使用 `DownloadManager`；
- 新增 **通知栏进度条**：每 500ms 查询 `DownloadManager.Query`，更新 `NotificationCompat` 的 progress；
- 下载完成后 `FileProvider` 拉起系统安装器；
- Android 8+ 先检查 `canRequestPackageInstalls()`，未授权则跳转设置页。

### 4.2 发布约定

1. tag 必须命名为 `r<versionCode>`，例如 `r260901`；
2. versionCode 必须大于当前安装的 versionCode；
3. Release Assets 中至少包含一个 `.apk`；
4. Release body 写更新内容，弹窗会展示前 500 字符。

### 4.3 兼容性注意

- Android 13+ 需要 `POST_NOTIFICATIONS` 运行时权限（已在 Manifest 声明）；
- Android 8+ 需要 `REQUEST_INSTALL_PACKAGES` 权限 + 用户手动开启「允许安装未知应用」；
- 签名必须一致，否则覆盖安装失败；
- 大陆网络建议保留 ghfast.top / gh-proxy.com 镜像 fallback。

---

## 5. GitHub Actions CI/CD

### 5.1 Workflow 文件

`.github/workflows/release.yml`

触发方式：
- 手动触发 `workflow_dispatch`，输入 version_code / version_name / notes；
- 推送 tag `r*` 自动触发。

任务：
1. 检出代码；
2. 解码 base64 签名密钥：`app/release.keystore`；
3. Gradle 缓存；
4. 构建 multi-ABI release APK；
5. 构建 release AAB；
6. zip 压缩产物；
7. 创建 GitHub Release 并上传 zip + apk + aab。

### 5.2 需要配置的 Secrets

在仓库 `Settings → Secrets and variables → Actions` 添加：

| Secret | 说明 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | release.keystore 的 base64 编码 |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_PASSWORD` | key 密码 |
| `RELEASE_KEY_ALIAS` | key alias |

生成 base64：

```bash
base64 -i app/release.keystore -o keystore.b64
# 然后把 keystore.b64 内容粘贴到 Secret
```

### 5.3 使用

手动触发：

```text
Actions → Release OTA → Run workflow
version_code: 260901
version_name: v260901
notes: 修复闪退；优化包体积；OTA 进度显示
```

自动触发：

```bash
git tag r260901
git push origin r260901
```

---

## 6. 兼容性与性能事项

### 6.1 兼容性

| 项目 | 注意 |
|---|---|
| minSdk 29 | Android 10+；如需支持 Android 5-9，需降 minSdk 并测试 |
| libVLC | `libvlc-all` 体积大但兼容性好；替换 MediaCodec/ExoPlayer 需验证 MPEG-TS over TCP |
| R8 混淆 | 已保留 Compose / Kotlin / OkHttp / Gson / libVLC；新增反射类需手动 `-keep` |
| 通知权限 | Android 13+ 首次安装需申请 `POST_NOTIFICATIONS` |
| 安装权限 | Android 8+ 首次 OTA 需用户手动允许「安装未知应用」 |
| 签名一致 | OTA 覆盖安装必须同一 keystore，否则只能卸载重装 |

### 6.2 性能

- `ScreenMirrorActivity` 触摸线程使用 `LinkedBlockingQueue` + 独立线程，避免主线程阻塞；
- OTA 进度查询每 500ms 一次，使用 `Handler` 在主线程更新通知，开销极低；
- R8 优化后启动时间和运行时内存均有改善；
- `extractNativeLibs` 关闭 legacy packaging 后，so 在 APK 中压缩，安装时解压，首次启动稍慢但 APK 更小。

### 6.3 已知待验证

1. `libvlc-all` 替换为 `libvlc` 或 ExoPlayer 后画面是否正常；
2. release 包在真机上首次启动、权限申请、OTA 下载安装全流程；
3. 多 ABI APK 在 armeabi-v7a / x86_64 模拟器/设备上是否可安装运行；
4. 与车机 `10.255.128.121:7080/7071` 的 end-to-end 联调（需 C3 恢复后）。

---

## 7. 文件改动清单

```text
.github/workflows/release.yml        # 重写 CI/CD
.gitignore                           # 忽略签名 keystore
app/build.gradle.kts                 # R8/ABI/AAB/签名/依赖
app/proguard-rules.pro               # 精简 + 修正包名
app/src/main/AndroidManifest.xml     # 移除 extractNativeLibs 属性
app/src/main/java/.../AppUpdater.kt  # OTA 通知进度
app/src/main/java/.../ScreenMirrorActivity.kt  # 闪退修复
```

---

## 8. 立即执行的验证命令

```bash
cd /e/navipilot

# 1. 构建 release arm64
./gradlew assembleRelease -Pnavipilot.abis=arm64-v8a \
  -PRELEASE_STORE_PASSWORD=你的密码 -PRELEASE_KEY_PASSWORD=你的密码 -PRELEASE_KEY_ALIAS=你的alias

# 2. 构建 AAB
./gradlew bundleRelease \
  -PRELEASE_STORE_PASSWORD=你的密码 -PRELEASE_KEY_PASSWORD=你的密码 -PRELEASE_KEY_ALIAS=你的alias

# 3. 查看产物
ls -lh app/build/outputs/apk/release/
ls -lh app/build/outputs/bundle/release/

# 4. 安装到手机
adb install -r -d app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

## 分支 push 自动出 debug APK（Amapauto → `debug-latest`）

2026-10-11 起，**向 `Amapauto` 分支 push 代码即自动出一个可直接安装的 debug APK**（不需要打 tag、不需要手动 dispatch）。

- 产物位置：Releases 的**滚动 prerelease** `debug-latest` → <https://github.com/FOREVERZAX1988/navipilot/releases/tag/debug-latest>
  - `app-arm64-v8a-debug.apk` / `app-armeabi-v7a-debug.apk` / `app-x86_64-debug.apk`（多 ABI 拆包）
  - `sp-dazi-debug-r<versionCode>.zip`（一次打包全部 APK）
- versionCode = UTC 日期 `yyMMdd`（如 `261011`），versionName = `v261011-debug-<sha7>` → 天然大于线上版本，**不会被系统拒装**
- **签名**：仓库配了 4 个签名 secret 时，debug 变体也用 **release keystore** 签（`-Pnavipilot.debugSign=release`）→ 与我们已装的 release 包**同签名，可直接覆盖安装，不用卸载**；secret 缺失时回退内置 debug key（此时装到有 release 包的机器上需要先卸载）
- 手动出包：Actions → Build and Release → Run workflow → 勾 **`build_debug`**（`workflow_dispatch` 读的是**默认分支**的 workflow，所以 `master` 上的 `release.yml` 也要保持同步）
- 正式发版不变：打 tag `r<versionCode>` 或 dispatch 不勾 `build_debug`

> ⚠️ 与作者的「特投屏（Map2Tesla）」包关系：两者 **applicationId 相同**（`com.jixiexiaoge.drivingassist`），但**签名不同**，且对方 versionCode 是 8 位（`YYMMDDNN`，如 `26093008`）大于我们的 6 位（`260902`）→ 两边的包**不能共存**，互相覆盖装需要先卸载。
