package io.github.MVennn.GSAuxiliary.feature

import android.app.Activity
import android.os.Handler
import android.os.Looper
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.util.Reflect
import io.github.MVennn.GSAuxiliary.feature.aistare.AiStareCardDetailFeature
import io.github.MVennn.GSAuxiliary.feature.aistare.AiStareQuickConfigFeature
import io.github.MVennn.GSAuxiliary.feature.dock.DockCleanFeature
import io.github.MVennn.GSAuxiliary.feature.home.HomeBannerFeature
import io.github.MVennn.GSAuxiliary.feature.ipoclean.IpoDialogCleanFeature
import io.github.MVennn.GSAuxiliary.feature.market.MarketBannerFeature
import io.github.MVennn.GSAuxiliary.feature.market.MarketFilterFeature
import io.github.MVennn.GSAuxiliary.feature.market.MarketIndexHideFeature
import io.github.MVennn.GSAuxiliary.feature.market.MarketOverviewCompactFeature
import io.github.MVennn.GSAuxiliary.feature.market.MarketSectorCompactFeature
import io.github.MVennn.GSAuxiliary.feature.navbar.NavBarBlendFeature
import io.github.MVennn.GSAuxiliary.feature.stgfilter.StgStockFilterFeature
import io.github.MVennn.GSAuxiliary.feature.stgsnap.StgSnapAddFeature
import io.github.MVennn.GSAuxiliary.feature.strategyquote.StgDirectQuoteFeature
import io.github.MVennn.GSAuxiliary.feature.strategyselect.StgSelectAllFeature
import io.github.MVennn.GSAuxiliary.feature.stockbar.StockDetailBarFeature
import io.github.MVennn.GSAuxiliary.feature.watchlist.WatchlistFeature

/**
 * 功能注册表。
 *
 * 生命周期：
 *   `onPackageReady` → [installAll]
 *   配置变化         → [onConfigChanged]（切到主线程后分发给各功能；
 *                       宿主存活期间新打开的开关就地补装，见方法注释）
 *   热重载 / 退出     → [uninstallAll]
 */
class FeatureManager(private val env: FeatureEnv) {

    private val bag = HookBag()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 已完成 [Feature.install] 的功能 id（installAll 与运行中补装共用） */
    private val installedIds = HashSet<String>()

    /**
     * 全部已登记的功能。
     *
     * 新增功能 = 在这里加一行。这是唯一的接入点。
     */
    val features: List<Feature> = listOf(
        WatchlistFeature(),
        StgDirectQuoteFeature(),
        StgSelectAllFeature(),
        StgSnapAddFeature(),
        StgStockFilterFeature(),
        StockDetailBarFeature(),
        DockCleanFeature(),
        HomeBannerFeature(),
        MarketBannerFeature(),
        MarketSectorCompactFeature(),
        MarketOverviewCompactFeature(),
        MarketFilterFeature(),
        MarketIndexHideFeature(),
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
                synchronized(installedIds) { installedIds.add(f.id) }
                installed++
                GLog.i("已安装功能 [${f.id}] ${f.title}")
            } catch (t: Throwable) {
                GLog.e("安装功能 [${f.id}] 失败", t)
            }
        }
        GLog.i("功能安装完成：$installed/${features.size}，共 ${bag.size} 个钩子")
        // 页面销毁时的统一回收点：与功能开关无关，装了就要有，否则页面级记账只能等
        // 关开关 / 热重载才释放（值→key 环会让弱表永远不生效）。
        hookActivityDestroy(env, bag)
    }

    /**
     * 挂 `Activity.onDestroy`，把「这一页结束了」分发给所有功能。
     *
     * 只用于释放按页面记账的引用（见 [Feature.onActivityDestroyed]），
     * **不在销毁路径上还原宿主现场**。必须走主线程：各功能要碰
     * ViewTreeObserver / 视图表，`onDestroy` 正常就在主线程，但框架回调不保证。
     */
    private fun hookActivityDestroy(env: FeatureEnv, bag: HookBag) {
        val cls = Reflect.loadClass("android.app.Activity", env.classLoader)
        if (cls == null) {
            GLog.w("页面销毁清理：找不到 android.app.Activity，按页释放不会生效")
            return
        }
        var hooked = 0
        for (m in Reflect.methodsNamed(cls, "onDestroy")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("lifecycle:onDestroy")
                    .intercept { chain ->
                        // 在宿主自己的 onDestroy 之前清理：此刻 decorView / window 一定还在，
                        // 按身份删监听和记账才找得到目标；清完再放行宿主拆页面。
                        runCatching { dispatchActivityDestroyed(chain.thisObject as? Activity) }
                            .onFailure { GLog.w("页面销毁清理：分发失败", it) }
                        chain.proceed()
                    }
            )
            hooked++
        }
        if (hooked == 0) GLog.w("页面销毁清理：没挂上 Activity.onDestroy") else
            GLog.i("页面销毁清理：已挂 $hooked 个 Activity.onDestroy")
    }

    /** 主线程分发；单个功能清理失败不影响其它功能，也不影响宿主 */
    private fun dispatchActivityDestroyed(activity: Activity?) {
        if (activity == null) return
        val task = Runnable {
            for (f in features) {
                runCatching { f.onActivityDestroyed(activity) }
                    .onFailure { GLog.w("页面销毁清理：功能 [${f.id}] 失败", it) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else mainHandler.post(task)
    }

    /**
     * 配置变化：更新快照并分发给各功能（主线程）。
     *
     * 审查 2026-10-17 高-2：启动时未启用的功能连钩子都没装，此前宿主存活期间
     * 新打开的开关永远不生效，只能重启宿主。现在分发前先补装——「没装过 &&
     * 本轮已启用」的功能就地 [Feature.install]；失败则从已装表移除，
     * 下次配置变化还有机会重试。
     */
    fun onConfigChanged(cfg: ConfigSnapshot) {
        env.config = cfg
        mainHandler.post {
            for (f in features) {
                val newlyInstalled = f.isEnabled(cfg) &&
                    synchronized(installedIds) { installedIds.add(f.id) }
                if (!newlyInstalled) continue
                try {
                    f.install(env, bag)
                    GLog.i("运行中补装功能 [${f.id}] ${f.title}")
                } catch (t: Throwable) {
                    synchronized(installedIds) { installedIds.remove(f.id) }
                    GLog.e("运行中补装功能 [${f.id}] 失败", t)
                }
            }
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
        stgSnapAdd = false,
        stgStockFilter = false,
        stockBarEntry = false,
        dockClean = false,
        homeBannerHide = false,
        marketBannerHide = false,
        marketSectorCompact = false,
        marketOverviewCompact = false,
        marketStockFilter = false,
        marketIndexHide = false,
        watchlistBold = false,
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
