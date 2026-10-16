package io.github.MVennn.GSAuxiliary.feature.uiclean

import android.app.Activity
import android.view.View
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.PageSyncFeature

/**
 * 「界面精简」类功能的公共骨架。
 *
 * 调度（改视图的时机、首帧屏障、限速、失配快照、预热）全在
 * [PageSyncFeature] 里，本类只补三件精简类特有的事：
 *
 *  1. 改动一律走 [ViewPatcher] —— 于是「一键还原」与「首帧屏障的判据」都是现成的；
 *  2. 幂等快速通道 [alreadyHidden] / [stillAttached]（布局回调与首帧屏障每帧都可能来，
 *     `applyTo` 里绝不能无脑走整棵视图树）；
 *  3. 语义仍旧叫 [applyTo]（精简 = 认出现成视图然后隐藏 / 压缩），
 *     与各 `*Targets.kt` 里的候选数组注释一一对应。
 *
 * ## 幂等与自愈
 *
 * 宿主会**随时重建同一块视图**（切 Tab 重建 Fragment、翻页换形态、布局参数被重置），
 * 我们改过的东西会随着旧视图一起消失。所以 [alreadyHidden] / [stillAttached] 判的是
 * 「**那个视图现在还在不在树上**」而不是「我记不记得自己做过」——
 * 旧视图 detach 后标记自动失效，于是下一次布局回调会重新处理一遍，
 * 用户不会遇到「改过一次、被宿主还原之后就再也回不来」。
 */
abstract class UiCleanFeature : PageSyncFeature() {

    /**
     * 在当前页面上应用本功能。
     *
     * 返回 `true` = 处理完了（**包括「这页本来就没有目标控件」**）；
     * 返回 `false` = **认不出目标控件**，交给基类累计次数 / 限速 / 必要时打视图树快照。
     */
    protected abstract fun applyTo(env: FeatureEnv, activity: Activity): Boolean

    /** 本功能的补丁表；所有改动都过它，才能一键还原（也是首帧屏障的判据来源） */
    protected val patcher: ViewPatcher by lazy { ViewPatcher(id) }

    /** 首帧屏障的判据 = 补丁表里「改过几次」（累计、单调递增、还原也算） */
    final override val mutations: Int get() = patcher.mutations

    /** 关掉开关：把改过的界面全部还原 */
    final override fun restore() {
        patcher.restoreAll()
    }

    /** 没有改动过就不出声（`restoreAll` 自己有日志），免得每次改配置都刷噪音 */
    final override val hasChanges: Boolean get() = patcher.size > 0

    final override fun sync(env: FeatureEnv, activity: Activity): Boolean = applyTo(env, activity)

    // ------------------------------------------------------------------ 给子类用

    /**
     * 幂等快速通道（被隐藏的视图版）：目标视图**还在树上**且已经是 `GONE` ——
     * 说明本功能已经处理过，直接返回，不必再走一遍整棵视图树。
     *
     * 用 `isAttachedToWindow` 而不是只判 `visibility`：宿主重建 Fragment 后
     * 旧的视图对象仍然是 `GONE`，但它已经不在树上了，必须重新认一遍。
     */
    protected fun alreadyHidden(v: View?): Boolean =
        v != null && v.isAttachedToWindow && v.visibility == View.GONE

    /**
     * 幂等快速通道（容器版）：上次处理过的**容器**还在树上 → 本功能已经生效。
     *
     * 适用于「改的是容器的子项或容器自己的高度」这类功能 ——
     * 它们没有「被隐藏的那一个视图」可以拿来判，用容器本身当标记即可：
     * 宿主重建页面时旧容器会 `detach`，标记自动失效，于是重新处理一遍。
     */
    protected fun stillAttached(v: View?): Boolean = v != null && v.isAttachedToWindow
}
