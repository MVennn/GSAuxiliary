package io.github.MVennn.GSAuxiliary.feature

import android.os.Handler
import android.os.Looper
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.aistare.AiStareCardDetailFeature
import io.github.MVennn.GSAuxiliary.feature.aistare.AiStareQuickConfigFeature
import io.github.MVennn.GSAuxiliary.feature.dock.DockCleanFeature
import io.github.MVennn.GSAuxiliary.feature.home.HomeBannerFeature
import io.github.MVennn.GSAuxiliary.feature.ipoclean.IpoDialogCleanFeature
import io.github.MVennn.GSAuxiliary.feature.market.MarketBannerFeature
import io.github.MVennn.GSAuxiliary.feature.market.MarketOverviewCompactFeature
import io.github.MVennn.GSAuxiliary.feature.market.MarketSectorCompactFeature
import io.github.MVennn.GSAuxiliary.feature.navbar.NavBarBlendFeature
import io.github.MVennn.GSAuxiliary.feature.stgfilter.StgStockFilterFeature
import io.github.MVennn.GSAuxiliary.feature.strategyquote.StgDirectQuoteFeature
import io.github.MVennn.GSAuxiliary.feature.strategyselect.StgSelectAllFeature
import io.github.MVennn.GSAuxiliary.feature.stockbar.StockDetailBarFeature
import io.github.MVennn.GSAuxiliary.feature.watchlist.WatchlistFeature

/**
 * 功能注册表。
 *
 * 生命周期：
 *   `onPackageReady` → [installAll]
 *   配置变化         → [onConfigChanged]（切到主线程后分发给各功能）
 *   热重载 / 退出     → [uninstallAll]
 */
class FeatureManager(private val env: FeatureEnv) {

    private val bag = HookBag()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 全部已登记的功能。
     *
     * 新增功能 = 在这里加一行。这是唯一的接入点。
     */
    val features: List<Feature> = listOf(
        WatchlistFeature(),
        StgDirectQuoteFeature(),
        StgSelectAllFeature(),
        StgStockFilterFeature(),
        StockDetailBarFeature(),
        DockCleanFeature(),
        HomeBannerFeature(),
        MarketBannerFeature(),
        MarketSectorCompactFeature(),
        MarketOverviewCompactFeature(),
        NavBarBlendFeature(),
        IpoDialogCleanFeature(),
        AiStareQuickConfigFeature(),
        AiStareCardDetailFeature(),
    )

    /** 安装所有「当前配置下启用」的功能 */
    fun installAll() {
        val cfg = env.config
        var installed = 0
        for (f in features) {
            if (!f.isEnabled(cfg)) {
                GLog.i("跳过功能 [${f.id}]：当前配置下未启用")
                continue
            }
            try {
                f.install(env, bag)
                installed++
                GLog.i("已安装功能 [${f.id}] ${f.title}")
            } catch (t: Throwable) {
                GLog.e("安装功能 [${f.id}] 失败", t)
            }
        }
        GLog.i("功能安装完成：$installed/${features.size}，共 ${bag.size} 个钩子")
    }

    /** 配置变化：更新快照并分发给各功能（主线程） */
    fun onConfigChanged(cfg: ConfigSnapshot) {
        env.config = cfg
        mainHandler.post {
            for (f in features) {
                runCatching { f.onConfigChanged(env, cfg) }
                    .onFailure { GLog.w("功能 [${f.id}] 处理配置变化失败", it) }
            }
        }
    }

    /**
     * 卸载全部钩子（热重载 / 进程结束前）。
     *
     * 审查 2026-10-17 高-1：**只卸钩子不够**。挂在宿主 `decorView` 上的
     * `OnGlobalLayoutListener` / `OnPreDrawListener`（PageSync 系）、写进窗口的
     * 导航条颜色、留在 ListView 上的过滤代理、注入到底栏里的按钮 —— 这些都不是
     * Xposed 钩子，`unhook` 摘不掉；而旧实例的 `env.config` 冻结在「开启」态，
     * 永远等不到 `onConfigChanged(false)`，变成关不掉的僵尸。
     *
     * 修法：构造一份「全部功能关闭」的快照，让每个功能先走一遍自己的
     * 关闭还原路径（[Feature.onConfigChanged] —— PageSync 系 detach+restore、
     * 导航条还原窗口属性、筛选股票拆代理），把视图现场清干净，再卸钩子。
     * 还原涉及视图操作，必须在主线程；热重载回调不保证在主线程，故按需投递。
     */
    fun uninstallAll() {
        val off = allOffSnapshot()
        env.config = off   // 旧实例自此只读得到「全关」：钩子里的开关判断也立即失效
        val features = this.features
        val teardown = Runnable {
            for (f in features) {
                runCatching { f.onConfigChanged(env, off) }
                    .onFailure { GLog.w("热重载清理：功能 [${f.id}] 关闭还原失败", it) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) teardown.run()
        else mainHandler.post(teardown)

        GLog.i("卸载全部钩子（共 ${bag.size} 个）")
        bag.unhookAll()
    }

    /**
     * 「所有功能全关」的配置快照：boolean 全 false，数值 / 字符串取
     * [ConfigKeys] 的默认值。只用于热重载清理，不落任何持久化。
     */
    private fun allOffSnapshot(): ConfigSnapshot = ConfigSnapshot(
        enabled = false,
        color = ConfigKeys.DEFAULT_COLOR,
        scopeStrategy = false,
        logLevel = ConfigKeys.DEFAULT_LOG_LEVEL,
        stgDirectQuote = false,
        stgQuoteSwipe = false,
        stgSelectAll = false,
        stgStockFilter = false,
        stockBarEntry = false,
        dockClean = false,
        homeBannerHide = false,
        marketBannerHide = false,
        marketSectorCompact = false,
        marketOverviewCompact = false,
        navBarBlend = false,
        ipoDialogClean = false,
        aistareQuickEnabled = false,
        aiStareQuickConfig = ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG,
        aiStareSkipSaveConfirm = false,
        aiStareEmptySave = false,
        aiStareCardDetail = false,
        aiStareSaveBackToStock = false,
        aiStarePickScheme = false,
    )
}
