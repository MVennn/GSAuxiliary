package io.github.SansKritO4.GSAuxiliary.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import io.github.SansKritO4.GSAuxiliary.core.ConfigKeys
import java.util.Locale

/**
 * 界面语言。
 *
 * 设置页的「语言」项支持「跟随系统 / 简体中文 / English」。
 * 实现方式是在 Activity 的 `attachBaseContext` 里按选择包装 Context，
 * 之后所有 `stringResource(...)` 都会走对应 `values-xx/strings.xml`。
 * （默认 `values/` 是简体中文，`values-en/` 是英文。）
 *
 * 为什么不用 `LocaleManager`？那需要 API 33+，而本项目 minSdk 27；
 * `createConfigurationContext` 从 API 17 起就可用，覆盖面更广。
 *
 * ## 为什么不能直接返回 `createConfigurationContext()` 的结果
 *
 * `Context.createConfigurationContext()` 返回的是 **`ContextImpl`**，它继承自
 * `Context` 而**不是** `ContextWrapper`，`baseContext` 链是断的。
 *
 * 而 androidx 查找「宿主」的方式是沿 `ContextWrapper` 链上溯：
 *
 * ```kotlin
 * // androidx.activity.compose，反编译 LocalActivityResultRegistryOwner 可见
 * var ctx: Context = LocalContext.current
 * while (ctx is ContextWrapper) {           // ← 这里对 ContextImpl 直接不成立
 *     if (ctx is ActivityResultRegistryOwner) return ctx
 *     ctx = ctx.baseContext
 * }
 * ```
 *
 * 一旦 `LocalContext` 被换成 `ContextImpl`，`LocalActivityResultRegistryOwner`
 * 的默认值计算就会在 `checkcast ContextWrapper` 处抛 **ClassCastException**，
 * 表现为「切换语言后点进设置页立刻闪退」—— 因为只有设置页用了
 * `rememberLauncherForActivityResult`（备份 / 恢复）。
 *
 * 所以这里**包一层 [ContextWrapper]**：`baseContext` 保持指向原 Activity，
 * 宿主查找链完整；只把 `resources` 换成新 locale 的。
 */
object LocaleHelper {

    /** 按当前配置包装 Context；「跟随系统」时原样返回 */
    fun wrap(base: Context): Context {
        val lang = ModulePreferences.getString(ConfigKeys.LANGUAGE, ConfigKeys.DEFAULT_LANGUAGE)
        if (lang == ConfigKeys.LANGUAGE_SYSTEM) return base

        // forLanguageTag 取代已废弃的 Locale(String) 构造器
        val locale = runCatching { Locale.forLanguageTag(lang) }
            .getOrNull()
            ?.takeIf { it.language.isNotEmpty() }
            ?: return base
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return LocalizedContextWrapper(base, base.createConfigurationContext(config))
    }
}

/**
 * 只替换资源、保留 [ContextWrapper] 身份的 Context 包装。
 *
 * - `baseContext` → 原 Activity：`findOwner` 类查找（`ActivityResultRegistryOwner`、
 *   `OnBackPressedDispatcherOwner`、`LifecycleOwner` …）都能正常上溯
 * - `resources` / `assets` → 新 locale 的：`stringResource(...)` 立刻生效
 * - `theme` **不覆写**：沿用 Activity 已应用的主题，避免丢失 manifest 里的主题属性
 */
private class LocalizedContextWrapper(
    base: Context,
    private val localized: Context,
) : ContextWrapper(base) {

    override fun getResources(): Resources = localized.resources

    override fun getAssets(): AssetManager = localized.assets
}
