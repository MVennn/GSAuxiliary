# ---------------------------------------------------------------------------
# release 构建已开启 R8（isMinifyEnabled / isShrinkResources = true）。
#
# 下面每一条 keep 都对应一个「R8 无法从代码里看出、但运行期确实会用到」的入口。
# 新增入口时请同步补规则，否则会出现「编译通过、装上去不生效」这种最难查的问题。
# ---------------------------------------------------------------------------

# 入口类：框架按 META-INF/xposed/java_init.list 里的类名反射实例化
-keep class io.github.MVennn.GSAuxiliary.GszxModule { *; }

# Application / Activity / Provider 由系统按 manifest 实例化。
# AGP 自带的 proguard-android-optimize.txt 对这些基类已有通用 keep，
# 这里显式写出是为了意图清晰，且不依赖 AGP 默认文件的内容变化。
-keep class io.github.MVennn.GSAuxiliary.app.GszxApp { *; }
-keep class io.github.MVennn.GSAuxiliary.ui.SettingsActivity { *; }
-keep class io.github.MVennn.GSAuxiliary.app.DebugConfigProvider { *; }

# libxposed 服务通道（remote preferences / scope / 热重载）。
# XposedProvider 由系统实例化；IXposedService 的 binder 描述符是跨进程查找的。
# 这个包本身很小（classes.jar 约 23KB），整体保留的代价可以接受。
-keep class io.github.libxposed.service.** { *; }

# 配置键与默认值：模块进程与目标进程共用，键名参与序列化语义
-keep class io.github.MVennn.GSAuxiliary.core.ConfigKeys { *; }
-keep class io.github.MVennn.GSAuxiliary.core.ConfigSnapshot { *; }

# 功能实现由 FeatureManager 直接构造，保留以防被误裁
-keep class * implements io.github.MVennn.GSAuxiliary.feature.Feature { *; }

# 过滤代理（策略详情「筛选股票」装到 ListView 上的适配器代理）：
# 类名与 getTarget() 是**跨实例识别**的判据 —— 热重载后新实例要靠它们认出
# 旧实例留在列表上的代理（见 CODE-RULES 1.2 与 StgStockFilterFeature.unwrapProxy）。
# 2026-10-19 真机定位：模块自己也开 R8，这条类名被混淆成 `f2.m`，
# 于是「类名里含 FilteredAdapter」这条判据连**自己刚装的**代理都认不出
# —— 表现为「按『确定』筛选不生效、退出重进才生效」。
# 注意：同实例的识别已改为身份直判（不依赖名字），这条 keep 保的是跨实例那条路。
-keepnames class io.github.MVennn.GSAuxiliary.feature.stgfilter.StgStockFilterFeature$FilteredAdapter
-keepclassmembers class io.github.MVennn.GSAuxiliary.feature.stgfilter.StgStockFilterFeature$FilteredAdapter {
    public android.widget.ListAdapter getTarget();
}

# 宿主类名/字段名全部是运行时反射查的（见 util/Reflect.kt），
# 规则里不需要列出宿主；保留行号信息便于排查崩溃堆栈
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# 降噪
# ---------------------------------------------------------------------------
# libxposed api / annotation 是 compileOnly，运行期由框架在目标进程注入。
# 它们不在本 APK 的运行时 classpath 上，R8 会提示 missing，属于预期。
-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**
