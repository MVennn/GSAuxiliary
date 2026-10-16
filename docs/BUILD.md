# 构建指南

## 1. 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 或 21 | AGP 8.10.1 要求 17+；探针手工构建也要求 17+ |
| Android SDK Platform | **compileSdk 37** | Miuix 0.9.3 硬性要求 |
| Android SDK Build-Tools | 任意一个即可 | 只有**探针**手工构建时用到；`tools/probe/build.sh` 默认取 `build-tools/` 下**版本号最大**的那个，也可用 `BUILD_TOOLS` 指定。本机实测 35.0.0 |
| Gradle | 8.11.1 | 仓库自带 Wrapper，无需预装 |

```bash
# 1. 指定 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 签名（可选，缺失时自动退回 debug 签名）
cp keystore.properties.example keystore.properties
# 编辑填入 storeFile / storePassword / keyAlias / keyPassword

# 3. 构建
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> 干净环境（CI / 新机器）还要先补 compileSdk 37 平台：
> `bash tools/ci/setup-sdk37.sh`。为什么需要它见 §4.1 / §8.1。
> 想直接让 GitHub 帮你编，见 **§8 在 GitHub Actions 上构建**。

---

## 2. 依赖版本组合（重要）

**当前使用的稳定组合：**

```kotlin
AGP                 8.10.1
Kotlin              2.4.0
compose 插件         2.4.0
Miuix               0.9.3
backdrop            1.0.6
compileSdk          37
```

另外一条硬性约定：**不引任何第三方图标库**。
界面图标全部在 `ui/AppIcons.kt` 里用 Material 官方 path 数据自绘。
曾经引过 `androidx.compose.material:material-icons-extended:1.7.5`，
它会把 **11 400 个**用不到的图标类编进 dex，APK 从 1.34 MB 涨到 11.45 MB
（详见 `docs/UI.md` §10）。

这不是随便选的，是踩完一圈坑之后的唯一可行解。**不要轻易升级。**

### 为什么不能用更新的 Miuix

Miuix 的版本门槛是逐级递进的：

| Miuix | 要求 |
|---|---|
| 0.9.0 / 0.9.1 | 兼容 compileSdk 35 |
| **0.9.2 / 0.9.3** | 要求 **compileSdk 37** |
| 0.9.4+ | 要求 compileSdk 37 **且 AGP ≥ 9.1.0** |

Miuix 0.9.4 会带进 `androidx.compose.ui:ui-android:1.12.0`，
它的 AAR 元数据里硬性声明了：

```
minCompileSdk = 37
minAgpVersion = 9.1.0
```

Gradle 在依赖解析阶段就会拒绝，报：

```
Dependency 'androidx.compose.ui:ui-android:1.12.0' requires AGP 9.1.0 or higher
```

### 为什么不能直接升 AGP 9.x

AGP 9.0 起**内置了 Kotlin 支持**，再声明 `org.jetbrains.kotlin.android` 插件会直接报错：

```
The 'org.jetbrains.kotlin.android' plugin is no longer required
for Kotlin support since AGP 9.0
```

要用 AGP 9.x 就得把整个构建脚本改成 AGP 内置 Kotlin 的写法（去掉 kotlin 插件、
去掉 `kotlin { compilerOptions {} }` 块、改用 AGP 自己的 DSL），
为了一个 UI 库升级而重构构建脚本，收益不成正比。

**所以停在 AGP 8.10.1 + Miuix 0.9.3。**

### 为什么 backdrop 停在 1.x

液态玻璃底栏用的 `io.github.kyant0:backdrop`（Kyant0/AndroidLiquidGlass）有同一个坑：

| backdrop | 依赖 | 结论 |
|---|---|---|
| **1.0.6** | `androidx.compose.*:1.10.3` | ✅ 无 `minAgpVersion`，与 AGP 8.10.1 兼容 |
| 2.0.1 | `org.jetbrains.compose.*:1.12.0` → Compose 1.12 | ❌ 要求 AGP ≥ 9.1.0 |

判断依据是 AAR 里的 `META-INF/com/android/build/gradle/aar-metadata.properties`：
Compose 1.9 / 1.10 / 1.11 只有 `minCompileSdk=35`，
**Compose 1.12 才多出 `minAgpVersion=9.1.0`**。

```bash
# 自查某个 compose 版本的门槛
curl -sL -o ui.aar https://maven.aliyun.com/repository/google/androidx/compose/ui/ui-android/1.10.3/ui-android-1.10.3.aar
unzip -p ui.aar META-INF/com/android/build/gradle/aar-metadata.properties
```

**所以 backdrop 必须停在 1.x。**

### release 构建开启 R8（2.4.0 起）

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro",
        )
    }
}
```

裁剪效果（2.4.0 首次开启 R8 时实测）：dex 类数 26 553 → 2 987，APK 11.45 MB → 1.34 MB。
**2.12.1 当前实测**：类数 3 068、`classes.dex` 2.57 MB、release APK 1.43 MB
（比 2.4.0 略涨是因为此后新增了两个功能与自绘弹层 / 液态玻璃组件，属正常增长）。

**keep 规则的底线**（`app/proguard-rules.pro`）：凡是「R8 从代码里看不出、
但运行期确实会被反射/系统调用」的入口都必须显式 keep，目前包括 ——

| 入口 | 为什么必须 keep |
| --- | --- |
| `io.github.MVennn.GSAuxiliary.GszxModule` | 框架按 `META-INF/xposed/java_init.list` 里的类名反射实例化 |
| `io.github.MVennn.GSAuxiliary.app.GszxApp` | manifest 的 `android:name` |
| `io.github.MVennn.GSAuxiliary.ui.SettingsActivity` | manifest 声明，LSPosed 从自己进程跳转过来 |
| `io.github.MVennn.GSAuxiliary.app.DebugConfigProvider` | manifest 声明，adb 通道 |
| `io.github.libxposed.service.**` | `XposedProvider` 由系统实例化；binder 描述符跨进程查找 |
| `core.ConfigKeys` / `core.ConfigSnapshot` | 模块进程与目标进程共用，键名参与序列化 |
| `* implements feature.Feature` | 由 `FeatureManager` 直接构造 |

**新增反射入口时必须同步补规则**，否则会出现「编译通过、装上去不生效」
这种最难查的问题。

构建后的自查（红线仍然成立）：

```bash
APK=app/build/outputs/apk/release/app-release.apk

# 版本号（当前应为 2.12.1 / versionCode 32）
aapt2 dump badging "$APK" | head -2

# 类总数与入口类
unzip -o -q "$APK" 'classes*.dex' -d /tmp/apkx
dexdump -f /tmp/apkx/classes*.dex | grep -c "Class descriptor"
dexdump -f /tmp/apkx/classes*.dex | grep "Lio/github/MVennn/GSAuxiliary/GszxModule;"

# 红线：libxposed api 必须为 0 个
dexdump -f /tmp/apkx/classes*.dex | grep -c "Class descriptor.*Lio/github/libxposed/api"

# 清单里不能有 legacy 元数据 —— 精确查那几个 key，别用 grep -i xposed
aapt2 dump xmltree --file AndroidManifest.xml "$APK" \
  | grep -iE "xposedminversion|xposedmodule|xposedsharedprefs|xposedscope|xposeddescription"
#   应无输出
#
#   ⚠️ 不要用 `grep -i xposed` —— 下面两处是**合法且必需**的，会被误报：
#     · android:description="@string/xposed_description"（字符串资源名，带下划线）
#     · de.robv.android.xposed.category.MODULE_SETTINGS（LSPosed 靠它找设置入口）
#   真正的 legacy 判定 key 是 **`xposedminversion`**（无下划线），见 EXTENDING.md 红线 1。

# 系统栏沉浸的属性必须真的进包（默认 + night 两个变体）
aapt2 dump resources "$APK" | grep -A 8 "style/Theme.GSZX"
#   期望看到 windowLightNavigationBar / windowLightStatusBar = true（night 变体为 false）
#   以及 enforceStatusBarContrast / enforceNavigationBarContrast = false
```

> `versionName` / `versionCode` 的**唯一来源**是 `app/build.gradle.kts`，
> 每次发版两处必须同时改（见仓库根 `CHANGELOG.md` 的惯例）。
> `BuildConfig.BUILD_TIME` 是配置期求值的，所以同一份代码不同时间构建，
> 设置界面「概览」页显示的构建时间会不同 —— 这正常。

> R8 会输出一批 `An error occurred when parsing kotlin metadata` 警告，
> 原因是 Kotlin 2.4.0 比当前 AGP 内置的 R8 版本新。**属于已知无害警告**，
> 不影响产物（`BUILD SUCCESSFUL`，且上述自查全部通过）。

---

## 3. Xposed 依赖与模块元数据

### 3.1 Xposed API 用 Maven 依赖（libxposed API 102）

```kotlin
// 框架在目标进程注入这些类 —— 必须 compileOnly，不能打进 APK
compileOnly("io.github.libxposed:api:102.0.0")
// 仅供编译期解析 @SinceApi / @InternalApi（RetentionPolicy.CLASS，运行期不需要）
compileOnly("io.github.libxposed:annotation:1.0.0")

// 模块 App 侧的服务通道（remote preferences / scope / 热重载）—— 必须打包
implementation("io.github.libxposed:service:102.0.0")
```

**为什么 `api` 必须 `compileOnly`**：API 类由框架在目标进程注入。
一旦被打进 APK，LSPosed 会拒绝加载模块并报：

```
The Xposed API classes are compiled into the module's APK.
```

**为什么 `service` 必须 `implementation`**：它跑在**模块自己的进程**里，
用来和 LSPosed 守护进程通信。它的 AAR 清单会自动合并一个
`XposedProvider`（authority = `<applicationId>.XposedService`），
框架通过它把 binder 投递给模块 App —— 不需要自己写 Provider。

**验证 `compileOnly` 生效**（这是最容易搞错、又最难发现的地方）：

```bash
unzip -o -q app/build/outputs/apk/debug/app-debug.apk 'classes*.dex' -d /tmp/apkx
dexdump -f /tmp/apkx/classes*.dex | grep "Class descriptor" | grep 'libxposed'
# 期望：只有 io/github/libxposed/service/*，没有 io/github/libxposed/api/*
```

> ⚠️ 不要用 `strings` / 字节扫描来判断 —— 模块自己的类里会有
> `io/github/libxposed/api/...` 的**字符串常量引用**，那是误报。
> 必须看 dexdump 的 `Class descriptor`（类定义）。

### 3.2 模块元数据放哪

现代 API 模块**不用 manifest meta-data**，元数据全部在 APK 内的 `META-INF/xposed/`：

```
app/src/main/resources/META-INF/xposed/
├── module.prop        # minApiVersion / targetApiVersion / staticScope / exceptionMode / autoHotReload
├── java_init.list     # 入口类，一行一个
└── scope.list         # 作用域包名，一行一个
```

**`module.prop` 支持的键**（`.prop` 就是 Java properties 格式）：

| 键 | 取值 | 含义 |
|---|---|---|
| `minApiVersion` | int，必填 | 要求的最低框架 API 版本。本模块用了 API 102 才有的 `HookHandle.setId` / `detach` / 热重载，故填 `102` |
| `targetApiVersion` | int，必填 | 目标 API 版本，框架据此决定行为差异 |
| `staticScope` | `true` / `false` | 为 `true` 时用户不能把模块应用到 `scope.list` 之外的应用 |
| `exceptionMode` | `protective` / … | hooker 抛异常时的处理策略。`protective` = 不让目标 App 崩溃 |
| `autoHotReload` | `true` / `false` | 模块 APK 升级后自动热重载目标进程 |

> **`#` 开头的行是注释，可以放心写。**
> LSPosed 读取这个文件用的是 `java.util.Properties.load()`
> （见 LSPosed 仓库 `app/src/main/java/org/lsposed/manager/util/ModuleUtil.java`），
> 按 Java 规范 `#` / `!` 开头的行即注释行。
> 本项目的 `module.prop` 因此带完整中文注释，不影响解析。

> 放在 `src/main/resources/`（不是 `assets/`）—— Android 的 `resources/` 目录内容
> 会被原样打进 APK 根，而 `assets/` 会变成 `assets/` 前缀下的路径，位置不对。

**两个必须记住的坑**：

1. **manifest 里绝对不能有 `xposedminversion`**。LSPosed 的判定逻辑是
   `isLegacyModule = metaData.containsKey("xposedminversion")`，只要看到它就
   把模块当 legacy，然后去找不存在的 `assets/xposed_init` → **模块静默不加载**。
2. **`api` 不能是 `implementation`**（见 3.1）。

---

## 4. 环境踩坑记录

### 4.1 compileSdk 37 没有正式安装包

> **好消息：这一段现在已经自动化了。** 干净环境（CI / 新机器）里不用再手工做，
> 跑 `bash tools/ci/setup-sdk37.sh` 就行 —— 它做的是同一件事，见 §8.1。
> 下面留着手工步骤，是为了讲清**为什么**要这么折腾。

AGP 8.10.1 默认只会去下载「已发布」的 platform。compileSdk 37 需要手工装：

1. 下载 `platform-37.2_r01.zip`（解压后目录名是 `android-37.2`）
2. 放进 `<SDK>/platforms/`
3. **关键：改 XML schema**

AGP 8.x 只认 `package.xml` 的 **schema v3**，而新下载的包是 **v4**，直接放进去会报
「找不到 android-37」。解法是照抄已有的 `android-35/package.xml` 结构改写：

- 去掉内嵌的 license 文本块
- `localPackage path="platforms;android-37"`
- `source.properties` 里的 `AndroidVersion.ApiLevel` 要写成 AGP 认得的形态

改完在 `gradle.properties` 里加：

```properties
android.suppressUnsupportedCompileSdk=37,37.0
```

否则 AGP 会警告"未测试过该版本"。

> Gradle 有时会自己在 `<SDK>/platforms/android-37.0` 再放一份冗余副本，
> 两份内容一致，不影响构建，但可以删掉其中一份。

### 4.2 AGP 9.1 需要 Gradle ≥ 9.3.1

如果你真的要走 AGP 9.x 路线：AGP 9.1 要求 **Gradle 9.3.1 以上**，
用 9.2.0 会直接失败。

---

## 5. 构建探针模块（不依赖 Gradle）

`tools/probe/` 是一个独立的手工构建模块，用于 App 升级后重新定位类名。

```bash
bash tools/probe/build.sh
# 产物: tools/probe/out/GoldSunProbe.apk
```

它走的是最原始的链路：

```
拉取 libxposed api aar → javac → jar → d8 → aapt2
  → 打包 dex + META-INF/xposed/ → zipalign → apksigner
```

不需要 Gradle，所以适合在最小环境里跑。

**探针构建的三个特殊点：**

1. **API 从 Maven 现拉**：`io.github.libxposed:api:102.0.0` 是 aar，
   脚本下载后抽出里面的 `classes.jar` 作为编译期 classpath，
   并同时传给 `d8 --lib`（告诉 d8「这些类运行时由框架提供，不要打进 dex」）。
2. **必须编到 Java 17**：libxposed api 的字节码是 **major 61（Java 17）**，
   用 `--release 8` 会报
   `bad class file: class file has wrong version 61.0, should be 52.0`。
   脚本用 `--release 17`，失败回退 `-source 17 -target 17`。
3. **元数据靠打包时合并**：`META-INF/xposed/{module.prop,java_init.list,scope.list}`
   放在 `tools/probe/meta/`，由 `pack_dex.py` 的附加目录参数原样合并进 APK 根。
   不能用 `aapt2 -A assets` —— 那样会变成 `assets/META-INF/...`，路径不对。

**三个 Windows/Git Bash 特有的坑（脚本里已处理）：**

1. **原生工具不认 MSYS 路径**
   `javac` / `d8` / `aapt2` 都是原生程序，`/f/Project/...` 会被它们理解成
   `\f\Project\...` 而找不到文件。脚本里统一用 `winpath()` 转成 `F:/Project/...`。

2. **`d8.bat` 会自己读 `JAVA_HOME`**
   如果 `JAVA_HOME` 是 MSYS 形式（`/c/Program Files/Java/jdk-21`），
   批处理包装脚本会报 `JAVA_HOME is set to an invalid directory`。
   脚本在调用前会把 `JAVA_HOME` 也转成 Windows 形式。

3. **`env.sh` 会自动探测 `JAVA_HOME`**
   `tools/env.sh` 会在 `JAVA_HOME` 未设置时自动扫描常见安装路径
   （`/c/Program Files/Java/*`、`/c/Program Files/Eclipse Adoptium/*`、
   `~/.jdks/*`、`~/.sdkman/candidates/java/current`、`/usr/lib/jvm/*`），
   验证 `bin/javac` 存在后取用。无需手动 `export JAVA_HOME=...`。

构建脚本最后会**自动校验**产物里的现代元数据，并检查没有遗留
`assets/xposed_init`：

```
现代模块元数据:
  [OK] META-INF/xposed/java_init.list
  [OK] META-INF/xposed/module.prop
  [OK] META-INF/xposed/scope.list
  [OK] legacy assets/xposed_init        ← [OK] 表示确实没有
    minApiVersion=102
    targetApiVersion=102
    staticScope=true
    exceptionMode=protective
    java_init.list -> com.wb.probe.ProbeModule
    scope.list     -> com.guosen.android
```

---

## 6. 依赖镜像

`settings.gradle.kts` 里默认优先走阿里云镜像（国内网络快）：

```kotlin
maven("https://maven.aliyun.com/repository/gradle-plugin")
maven("https://maven.aliyun.com/repository/google")
maven("https://maven.aliyun.com/repository/public")
```

海外网络 / CI 下**不用改文件**，设一个环境变量就行：

```bash
GS_NO_MIRROR=1 ./gradlew assembleRelease
```

设了之后只留 `google()` / `mavenCentral()` / `gradlePluginPortal()`。
`.github/workflows/` 里的两个工作流都设了它 —— runner 在海外，直连更稳。

> 为什么不「镜像失败自动回落」：Gradle 只在某个仓库返回 **404** 时才换下一个，
> **超时不会自动兜底**。镜像站对海外 IP 一慢，构建就直接卡在那儿。

---

## 7. 签名

仓库**不提交任何 keystore**（`.gitignore` 已排除 `*.keystore` / `keystore.properties`）。

`app/build.gradle.kts` 按这个优先级找签名：

| 优先级 | 来源 | 用在哪 |
|---|---|---|
| 1 | 环境变量 `GS_KEYSTORE_PATH` / `GS_KEYSTORE_PASSWORD` / `GS_KEYSTORE_ALIAS` / `GS_KEYSTORE_KEY_PASSWORD` | CI（GitHub Actions 从 Secrets 注入） |
| 2 | 项目根目录的 `keystore.properties` | 本地开发 |
| 3 | AGP 自带的 debug 签名 | 兜底，仍能装到自己的测试机 |

- 环境变量那四个**必须齐全、且 `GS_KEYSTORE_PATH` 指向的文件存在**才认，
  半套配置不算 —— 免得出现「以为用了自己的签名、其实用了 debug 签名」。
- 走到第 3 档时构建日志里会有一行 `[签名] ...` 明说。
- 本地用法：

```bash
cp keystore.properties.example keystore.properties
# 编辑填入 storeFile / storePassword / keyAlias / keyPassword
```

> **注意**：LSPosed 里已启用过的模块，如果换了签名，需要先卸载再重装，
> 并且要在 LSPosed 里重新勾选。保持同一个 keystore 可以避免这一步 ——
> 所以 **release 包绝对不能一会儿用 debug 签名、一会儿用正式签名**。

---

## 8. 在 GitHub Actions 上构建

两个工作流，都在 `.github/workflows/`：

| 文件 | 触发 | 干什么 | 需要 Secret |
|---|---|---|---|
| `ci.yml` | 推 `main` / 开 PR / 手动 | 编译 `assembleRelease`（手动触发勾了 `build_debug` 才连 `assembleDebug`）+ 跑红线校验 + 上传 APK 与 R8 mapping | **可选**（配了就签真名，没配也能跑） |
| `release.yml` | 推 tag（`34-2.14.0`）/ 手动 | 校验 tag、查凭据权限、签名构建、跑红线、调 `publish.py` 发双仓库 | 5 个（+1 个可选），见 §8.2 |

> **`ci.yml` 默认只编 release。** debug 包从 2.17.1 起不再随 Release 发布，
> 却占了产物九成体积（约 11 MB，release 只有 1.5 MB）—— 日常没人下载它。
> 需要拿 debug 包装真机排查时，到 Actions 页面手动触发这个工作流、勾上 `build_debug`。
> 产物总大小因此从约 14 MB 降到约 2 MB。
>
> 两个工作流都钉 `ubuntu-24.04` 而不是 `ubuntu-latest`：后者从 2026-10-19 起
> 迁往 Ubuntu 26，迁移期的镜像差异不该体现在构建结果里。

`ci.yml` 是**零密钥可跑**的：仓库什么 Secret 都没配也能跑，release 会退回 debug 签名，
产物只当测试包用。所以「这个仓库能不能在 GitHub 上编译」的答案是 —— 推上去就跑。

> **配了 keystore Secrets 之后，`ci.yml` 会自动改用真密钥签**（四个 `GS_KEYSTORE_*`
> 挂在 job 级 env，`GS_KEYSTORE_BASE64` 由「解码签名密钥」那步落成文件并把
> `GS_KEYSTORE_PATH` 写进 `$GITHUB_ENV`），红线校验同时自动加上 `--reject-debug-cert`。
> 这两半**必须成对**：只加了校验没加解码，CI 必然红 —— 而它红得对
> （配了密钥却签出 debug 包，正是「用户必须卸载重装」那种事故）。
> fork 提的 PR 拿不到 Secrets，走的仍是零密钥路径。

两件容易忽略但很有用的事：

- **产物无论成败都上传**（`if: always()`）。红线校验红了的时候，那个 APK 正是你要
  下载下来查的东西；以前它只存在于 runner 上，几分钟后随机器一起消失。
- **R8 的 `mapping.txt` 一起传**。release 崩溃栈要靠它还原成可读的类名 / 行号 ——
  发版时把 `dist/mapping.txt` 留一份，比事后重新构建同一个 commit 靠谱。

> `release.yml` 手动触发（`workflow_dispatch`）时多一个 `dry_run` 开关：
> 打开后照常构建、照常跑红线，但**不往任何仓库写东西**。第一次用 CI 发版、
> 或者只是想确认密钥配对了没有，先拿它演一遍。


### 8.1 两个必须处理的坑

CI 是**干净环境**，本地那些「已经手工搞定过」的东西都得重来一遍：

**坑 1：compileSdk 37 装不上（这是最主要的一个）**

官方 SDK 仓库里根本没有 `platforms;android-37` 这个包 —— 只有
`platforms;android-37.0` / `37.1` / `37.2`（Android 16 之后新引入的「次要版本」命名）。
AGP 8.10.1 只认 `platforms/android-<api>` 老式目录 + 整数 api-level，于是：

```
Failed to find target with hash string 'android-37'
```

解法固化在 `tools/ci/setup-sdk37.sh`：用 sdkmanager 装一个次要版本平台
（默认 `platforms;android-37.2`，装不上依次退到 `37.1` / `37.0`），
再把它归一化成 AGP 认得的样子（目录改名 `android-37`，
`package.xml` 的 `api-level` / `localPackage path`、`source.properties` 的
`AndroidVersion.ApiLevel` 里的 `37.x` 全压成 `37`）。脚本幂等，已装好就直接过；
下载失败会自动重试（默认 3 次，`GS_SDK_INSTALL_TRIES` 可调）。

```bash
ANDROID_SDK_ROOT=/path/to/sdk bash tools/ci/setup-sdk37.sh
```

> 归一化**不按目录名猜版本号**，而是把所有 `37.<次要版本>` 一律压成 `37`。
> 目录名和元数据里的版本并不保证一致 —— 本机验证时就遇到过目录叫 `android-37.2`、
> 元数据里却写 `37.0` 的包；按目录名做替换会静默漏改，然后构建报一句
> `Failed to find target with hash string 'android-37'`，够查半天。
>
> 这和 §4.1 手工做的是同一件事，只是自动化了。
> 官方仓库里 `platforms;android-37.2` 在**稳定通道**，不需要开 preview 通道。


**坑 2：`gradlew` 的可执行位**

Windows 上 `git config core.fileMode=false`，很容易把 `gradlew` 提交成 `100644`，
到 Linux runner 上就是 `./gradlew: Permission denied`。仓库里已经用
`git update-index --chmod=+x gradlew` 修成 `100755` 了 —— **别把它改回去**。

```bash
git ls-files -s gradlew      # 必须是 100755
```

### 8.2 发布需要的 5 个 Repository secrets

到 `Settings → Secrets and variables → Actions` 加。
**每个 secret 的造法与取值只见 [`docs/RELEASE.md` §1.2](RELEASE.md#12-ci-发版需要的-5-个-repository-secrets)，这里不重抄。**

这里只记 `release.yml` 据此做了什么：

- **缺任何一个（前 5 个）都直接失败**，不会给你一个「看着成功、实际用户收不到更新」的假象。
- `publish.py` 故意不读 Actions 自带的 `GITHUB_TOKEN` —— 那个只对当前仓库有效，
  拿去写模块仓库会 403；它只认 `GS_GITHUB_TOKEN` / `GH_TOKEN`。
- 装 JDK / SDK **之前**先跑 **`python tools/publish.py --check`**：它只调 GitHub API，
  几秒钟确认 token 对两个仓库都有写权限。值得单独一步 —— `publish.py` 是先发源码仓库、
  再发模块仓库，token 写不了模块仓库的话，源码仓库那份已经建好了、用户却什么都收不到，
  而日志只报「失败」，很容易被当成偶发问题重跑。本地发版前也可以先跑一遍：

```bash
python tools/publish.py --check
#   [OK] token 有效，身份：MVennn
#   [OK] MVennn/GSAuxiliary 可写（默认分支 main）
#   [OK] Xposed-Modules-Repo/io.github.MVennn.GSAuxiliary 可写（默认分支 main）
```


### 8.3 红线校验已经自动化

`docs/BUILD.md` / `docs/RELEASE.md` 里那堆手工自查现在固化成一条命令，CI 和本地共用：

```bash
python tools/ci/verify_apk.py app/build/outputs/apk/release/app-release.apk
```

它**不依赖 `dexdump` / `aapt2` / `apksigner`** —— dex 的 `class_defs` 表、
APK 签名块都自己解析，纯标准库，所以在最小环境里也能跑。

> 为什么非得解析 `class_defs` 而不能字节扫 `io/github/libxposed/api`：
> 模块自己的类里有这个包的**字符串常量引用**，字节扫必然误报（见 §3.1）。
> 只有 `class_defs` 里出现的才是真·类定义。

**检查清单**（`[红线]` = 不通过就退出码 1）：

| # | 检查 | 级别 |
|---|---|---|
| 1 | `META-INF/xposed/{module.prop,java_init.list,scope.list}` 都在，且非空 | 红线 |
| 2 | `module.prop` 有 `minApiVersion` / `targetApiVersion` / `staticScope` / `exceptionMode` / `autoHotReload`，且 `minApiVersion=102` | 红线 |
| 3 | 没有 legacy `assets/xposed_init`（有它 = LSPosed 当老模块，静默不加载） | 红线 |
| 4 | dex 里没有 `io/github/libxposed/api` 与 `io/github/libxposed/annotation` 的**类定义**（两者都必须是 compileOnly） | 红线 |
| 5 | `io/github/libxposed/service` 的类**在**包里（App 侧服务通道，必须打包） | 红线 |
| 6 | 入口类 `GszxModule` 的类定义在（R8 keep 规则生效） | 红线 |
| 7 | `AndroidManifest.xml` 无 legacy `xposedminversion` 等 key，且 applicationId 仍是预期值 | 红线 |
| 8 | 打包的三个元文件与 `app/src/main/resources/META-INF/xposed/` 母本**逐字一致** | 红线 |
| 9 | APK **已签名**（未签名的包根本装不上） | 红线 |
| 10 | 签名证书**不是 Android 调试证书** | 只在 `--reject-debug-cert` 时是红线，否则只是提醒 |
| 11 | 签名证书 SHA-256 与期望值一致 | 只在给了 `--expect-cert-sha256` / `GS_EXPECT_CERT_SHA256` 时是红线 |

第 8 条值得解释：`app/src/main/resources/META-INF/xposed/` 是母本，一旦被依赖
（libxposed service 也带同名资源）覆盖、或改完忘了重建，APK 里就是旧值 ——
而「装上去不生效」这类问题极难从构建日志看出来。母本不存在时这条自动跳过
（例如拿一个下载来的 APK 单独校验）。

**release 包必须再加两条硬校验**（`release.yml` 已经这么跑了）：

```bash
python tools/ci/verify_apk.py --reject-debug-cert \
    --expect-cert-sha256 a7fc6034… app/build/outputs/apk/release/app-release.apk
```

`--reject-debug-cert` 是**防止「静默退回 debug 签名」的守卫**：
`app/build.gradle.kts` 在四个 `GS_KEYSTORE_*` 不齐全时会安静地退回 debug 签名，
签出来的包**签名和以前不一样** —— 已装用户必须卸载重装才能更新。
以前这件事只写在文档里靠人记，现在它是一条会变红的检查。

**输出分三级**：`[OK]` 通过、`[!]` 提醒（不影响退出码）、`[X]` 踩红线。
`--quiet` 可以只看提醒与红线。签名证书的指纹是用纯标准库解析 APK Signing Block
（v3 → v2 → v1）算出来的，实测与 `apksigner verify --print-certs` 逐字节一致。

输出长这样：

```
  [OK] 元数据 META-INF/xposed/module.prop
  [OK] 无 legacy assets/xposed_init
  [OK] 【红线】dex 无 io/github/libxposed/api 类定义（发现 0 个）
  [OK] libxposed service 已打进包（35 个类）
  [OK] 入口类已保留 Lio/github/MVennn/GSAuxiliary/GszxModule;
  [OK] dex 类定义总数 3087
  [OK] manifest 无 legacy xposed key
  [OK] 打包的 module.prop 与源码母本一致
  [OK] 已签名（scheme v2）证书 SHA-256: a7fc6034bc5937df…
```


### 8.4 版本号的唯一来源没变

CI 里的版本号也是读 `app/build.gradle.kts`（`tools/ci/version.py`），
和 `publish.py` 一致 —— 不另开一处能改偏的地方。

```bash
python tools/ci/version.py          # vc=34  vn=2.14.0
python tools/ci/version.py --tag    # 34-2.14.0
```

`--check-tag` 是 `release.yml` 与本地发版前共用的同一份判断 ——
免得「CI 校验一套、人眼校验另一套」：

```bash
python tools/ci/version.py --check-tag 34-2.14.0
#   [OK] tag 34-2.14.0 与 app/build.gradle.kts 的 versionCode-versionName 一致
```

### 8.5 CI 日志里这几条 WARNING 是**预期的**，不用查

CI 跑起来会刷出一批看着吓人的 WARNING。下面这些都已经确认无害，
**别为了消掉它们去改构建配置** —— 那才是引入风险：

| 日志 | 为什么无害 |
|---|---|
| `WARNING: R8: An error occurred when parsing kotlin metadata.`（会刷二十几行） | AGP 8.10.1 自带的 R8 不认识 Kotlin 2.4 的 metadata 版本。R8 只是退回「不看 metadata」的保守裁剪 —— keep 规则仍由 `proguard-rules.pro` 显式给出，而入口类 / service 类是否被保留，§8.3 的检查 5、6 每次都会验 |
| `Unable to strip the following libraries … libandroidx.graphics.path.so` | 这个 `.so` 没有符号表可剥，原样打包。无害 |
| `yes: standard output: Broken pipe`（老版本脚本才有） | sdkmanager 提前关掉了管道，`yes` 吃了个 SIGPIPE。已在新版 `setup-sdk37.sh` 里静音 |
| `w: … 'fun get(p0: String!): Any?' is deprecated.` | Java 侧 API 的弃用提示，出现在业务代码里，与构建链路无关 |
| `[!] 签名证书是 Android 调试证书` | **零密钥 CI 的正常状态** —— 仓库没配 keystore Secrets，release 只能退回 debug 签名。配了 Secrets 之后这条会消失（并且 `ci.yml` 会自动改跑 `--reject-debug-cert`，再出现就是真的错了） |

判断「这次 CI 到底行不行」只看两处：**job 有没有绿**，以及
**`校验产物红线` 那一步的最后一行**（`=> 全部通过` / `=> 有 N 条红线被踩`）。


