package io.github.SansKritO4.GSAuxiliary.feature

import android.os.Handler
import android.os.Looper
import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.core.HookBag
import io.github.SansKritO4.GSAuxiliary.feature.dock.DockCleanFeature
import io.github.SansKritO4.GSAuxiliary.feature.home.HomeBannerFeature
import io.github.SansKritO4.GSAuxiliary.feature.market.MarketBannerFeature
import io.github.SansKritO4.GSAuxiliary.feature.market.MarketOverviewCompactFeature
import io.github.SansKritO4.GSAuxiliary.feature.market.MarketSectorCompactFeature
import io.github.SansKritO4.GSAuxiliary.feature.stgfilter.StgStockFilterFeature
import io.github.SansKritO4.GSAuxiliary.feature.strategyquote.StgDirectQuoteFeature
import io.github.SansKritO4.GSAuxiliary.feature.strategyselect.StgSelectAllFeature
import io.github.SansKritO4.GSAuxiliary.feature.stockbar.StockDetailBarFeature
import io.github.SansKritO4.GSAuxiliary.feature.watchlist.WatchlistFeature

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

    /** 卸载全部钩子（热重载 / 进程结束前） */
    fun uninstallAll() {
        GLog.i("卸载全部钩子（共 ${bag.size} 个）")
        bag.unhookAll()
    }
}
