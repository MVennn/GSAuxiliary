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
| `io.github.SansKritO4.GSAuxiliary.GszxModule` | 框架按 `META-INF/xposed/java_init.list` 里的类名反射实例化 |
| `io.github.SansKritO4.GSAuxiliary.app.GszxApp` | manifest 的 `android:name` |
| `io.github.SansKritO4.GSAuxiliary.ui.SettingsActivity` | manifest 声明，LSPosed 从自己进程跳转过来 |
| `io.github.SansKritO4.GSAuxiliary.app.DebugConfigProvider` | manifest 声明，adb 通道 |
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
dexdump -f /tmp/apkx/classes*.dex | grep "Lio/github/SansKritO4/GSAuxiliary/GszxModule;"

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

`settings.gradle.kts` 里优先使用阿里云镜像：

```kotlin
maven("https://maven.aliyun.com/repository/gradle-plugin")
maven("https://maven.aliyun.com/repository/google")
maven("https://maven.aliyun.com/repository/public")
```

海外网络下可以注释掉这三行，直接用 `google()` / `mavenCentral()`。

---

## 7. 签名

仓库**不提交任何 keystore**（`.gitignore` 已排除 `*.keystore` / `keystore.properties`）。

- 有 `keystore.properties` → 用你自己的签名
- 没有 → 自动退回 AGP 自带的 debug 签名，仍然能安装到自己的测试机

> **注意**：LSPosed 里已启用过的模块，如果换了签名，需要先卸载再重装，
> 并且要在 LSPosed 里重新勾选。保持同一个 keystore 可以避免这一步。
