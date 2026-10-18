import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// 第三方 UI 库版本
//
// 集中在这里定义，一处改动同时作用于：依赖声明 + BuildConfig 注入（设置界面
// 「模块」页会展示 Miuix 版本，见 ModuleTab）。
// 不要升到 0.9.4+：会要求 AGP >= 9.1，与 org.jetbrains.kotlin.android 冲突。
// ---------------------------------------------------------------------------
val miuixVersion = "0.9.3"

// ---------------------------------------------------------------------------
// 签名配置
//
// 两条路，优先级：环境变量 > keystore.properties > AGP 自带的 debug 签名。
//
// 1) 本地开发 —— 在项目根目录放一个 keystore.properties（已被 .gitignore 忽略）：
//        storeFile=gszx.keystore
//        storePassword=******
//        keyAlias=gszx
//        keyPassword=******
//
// 2) CI —— GitHub Actions 把 Secrets 注入成下面这四个环境变量（见
//    .github/workflows/release.yml）。**四个必须齐全且文件存在**才认，
//    半套配置不算，免得出现「以为用了自己的签名、其实用了 debug 签名」。
//
// 两条都没有时退回 debug 签名 —— 仍然能装到自己的测试机，但**不要**拿它发版：
// LSPosed 里已启用过的模块换了签名必须卸载重装。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

val envKeystorePath = System.getenv("GS_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val envKeystorePassword = System.getenv("GS_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val envKeystoreAlias = System.getenv("GS_KEYSTORE_ALIAS")?.takeIf { it.isNotBlank() }
val envKeystoreKeyPassword = System.getenv("GS_KEYSTORE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasEnvKeystore = envKeystorePath != null &&
    envKeystorePassword != null &&
    envKeystoreAlias != null &&
    envKeystoreKeyPassword != null &&
    rootProject.file(envKeystorePath).exists()

val hasCustomKeystore = keystorePropsFile.exists() || hasEnvKeystore

// 走到 debug 签名时明确说一声，别让人以为 release 包是自己签的
if (!hasCustomKeystore) {
    logger.lifecycle(
        "[签名] 没有 keystore.properties，也没有 GS_KEYSTORE_* 环境变量，" +
            "release 将使用 AGP 自带的 debug 签名（仅供自测，不可用于发版）",
    )
}

android {
    namespace = "io.github.MVennn.GSAuxiliary"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.MVennn.GSAuxiliary"
        minSdk = 27
        targetSdk = 36
        versionCode = 47
        versionName = "3.2.0"

        // 构建时间（设置界面「模块」页展示用）。配置期求值，随每次构建刷新。
        // 注意：必须用顶部 import 的 SimpleDateFormat —— 脚本里 `java` 会被解析成
        // Gradle 的 JavaPluginExtension，写成 `java.text.X` 会 Unresolved reference。
        buildConfigField(
            "String",
            "BUILD_TIME",
            "\"${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\"",
        )

        // 第三方 UI 库版本，供设置界面「模块」页展示
        buildConfigField("String", "MIUIX_VERSION", "\"$miuixVersion\"")
    }

    signingConfigs {
        if (hasCustomKeystore) {
            create("release") {
                if (hasEnvKeystore) {
                    // CI 路径：GitHub Actions 从 Secrets 注入（release.yml 已设全四个）
                    storeFile = rootProject.file(envKeystorePath!!)
                    storePassword = envKeystorePassword
                    keyAlias = envKeystoreAlias
                    keyPassword = envKeystoreKeyPassword
                } else {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            // 2.4.0 起开启 R8。
            //
            // 开启原因：Miuix / Compose / backdrop 里大量分支本模块用不到，
            // 不开裁剪时它们会原样进包，dex 体积是裁剪后的数倍。
            // 入口类的 keep 规则见 proguard-rules.pro（框架靠 java_init.list
            // 里的类名反射实例化 GszxModule，必须保留）。
            //
            // 注意：libxposed API 是 compileOnly，不会因为开 R8 被打进包
            // （实测 dex 内 io/github/libxposed/api 相关类为 0 个）。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasCustomKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            signingConfig = if (hasCustomKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 只打包中英文：依赖库（androidx / Compose / Miuix）自带几十种语言，
    // 全量进包对中文用户毫无用处。资源裁剪（shrinkResources）删不掉它们 ——
    // 那些字符串被库自身引用，是「用得到但用户永远看不到」的那一类。
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/versions/**",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---------------------------------------------------------------------
    // 现代 Xposed API（libxposed API 102）
    //
    // 必须用 compileOnly：运行期由框架在目标进程注入这些类。
    // 一旦被打进 APK，LSPosed 会拒绝加载模块并报
    //   "The Xposed API classes are compiled into the module's APK"
    // ---------------------------------------------------------------------
    compileOnly("io.github.libxposed:api:102.0.0")
    // 仅供编译期解析 api 上的 @SinceApi / @InternalApi（RetentionPolicy.CLASS，运行期不需要）
    compileOnly("io.github.libxposed:annotation:1.0.0")

    // ---------------------------------------------------------------------
    // 模块 App 侧的服务通道（remote preferences / scope / 热重载）
    //
    // 这个必须打包：它跑在模块自己的进程里，用来和 LSPosed 服务通信。
    // 它会自动合并一个 XposedProvider（authority = <applicationId>.XposedService），
    // 框架通过它把 IXposedService 的 binder 投递给模块 App。
    // ---------------------------------------------------------------------
    implementation("io.github.libxposed:service:102.0.0")

    // Miuix（小米 HyperOS 风格 Compose UI）
    // 0.9.4+ 会要求 AGP >= 9.1，详见 docs/BUILD.md
    implementation("top.yukonga.miuix.kmp:miuix-ui:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference:$miuixVersion")

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.13.1")

    // 界面图标：**不引第三方图标库**。
    //
    // 曾经用过 androidx.compose.material:material-icons-extended:1.7.5，
    // 它把 Material 全量图标（5 种风格）都打进包，dex 里实测 11400 个类、
    // 占类总数 43%，而界面只用得到 7 个。现已改为在 ui/AppIcons.kt 里用
    // 官方 path 数据自建 ImageVector，零依赖。
    // 新增图标请照抄 AppIcons.kt 的写法，不要引回图标库。

    // 液态玻璃（Liquid Glass）悬浮底栏
    // 参考 Kyant0/AndroidLiquidGlass，提供 layerBackdrop / drawBackdrop / lens / blur 等效果。
    // 必须停在 1.x：2.0.1 依赖 Compose 1.12，而 Compose 1.12 要求 AGP ≥ 9.1，
    // 与本项目锁定的 AGP 8.10.1 冲突（详见 docs/BUILD.md）。1.0.6 只要求 Compose 1.10.3。
    implementation("io.github.kyant0:backdrop:1.0.6")
}
