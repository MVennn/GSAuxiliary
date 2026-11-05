package io.github.MVennn.GSAuxiliary.feature.stgsnap

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次「截图并添加」的详情页任务。
 *
 * 主线程状态机：
 *
 * ```
 * start → 找 vp_stocks → 校验 adapter.count == 选中数
 *   → 点「日K」→ 逐页：setCurrentItem(i,false)
 *        → 等就绪（标题代码匹配 + K 线 m_kxChartItems 非空）
 *        → 隐藏进度条 → PixelCopy → 交给后台写相册 → **立刻翻下一页**
 *   → 完成：等写相册全部落地，移除进度条，回调结果，finish() 回策略页
 * ```
 *
 * **数据没就绪的一页不截图**：超时后只记失败、直接翻下一页，绝不保存半张图。
 *
 * **存盘不挡翻页**：PNG 压缩与 MediaStore 写入是整条链路里最贵的一段，却和下一页的
 * 数据加载无关。PixelCopy 拿到像素后就把位图交给单线程 worker，主线程马上去切下一页；
 * 在途存盘最多 [StgSnapAddTargets.MAX_INFLIGHT_SAVES] 张（挡住「宿主页瞬间就绪」时
 * 位图无限排队），满了就暂停翻页，存完一张再续。正常路径下每页只多等一次宿主加载，
 * 不再串上编码与落盘。位图按页复用（池 [POOL_SIZE] 张），减少 10MB 大对象的分配抖动。
 *
 * **截图期间用户可以做别的事（2026-11-05）**：任务一开始就给宿主窗口挂上
 * `FLAG_KEEP_SCREEN_ON`（屏幕不因超时熄灭），并且**不再把「失去焦点 / 切到后台」当成中止**
 * —— 用户可以开小窗、分屏、切去别的 App。窗口仍然可见时（小窗 / 分屏）照常逐页截图；
 * 窗口真的退到后台、surface 不再更新时，任务**挂起**：不跳页、不记失败、不计超时，
 * 等窗口回来立刻接着截。唯一能打断任务的是进度条上的「取消」（以及页面销毁 / 关开关）。
 *
 * 所有 View / Pager / Bitmap 操作都在主线程；只有 PNG 压缩与写盘在单线程 worker。
 */
internal class SnapCaptureJob(
    private val env: FeatureEnv,
    private val activity: Activity,
    private val items: List<SelectedStock>,
    private val onFinished: (Result) -> Unit,
) {

    data class Result(
        val total: Int,
        val saved: Int,
        val failed: Int,
        val cancelled: Boolean,
        val error: String? = null,
    )

    /**
     * 一页的分阶段耗时（毫秒）。[saveMs] 在存盘完成时回填 —— 并行之后它和下一页
     * 的加载重叠，看的是「这一张真正写完要多久」，不是墙钟等待。
     */
    private class PageTiming(
        val index: Int,
        /** setCurrentItem → 标题命中 */
        val switchMs: Long,
        /** 标题命中 → 就绪门禁通过 */
        val readyMs: Long,
        /** 隐藏进度条 → PixelCopy 回调 */
        val copyMs: Long,
        /** 提交存盘 → PNG + 相册写入完成 */
        var saveMs: Long = 0L,
    ) {
        var polls = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)

    private var finished = false
    private var finishing = false
    private var pager: View? = null
    private var pagerAttempts = 0
    private var klineMode = false
    private var overlay: SnapProgressOverlay? = null

    /** 当前页的等待计时器：挂起时长不计入就绪超时 */
    private var gate: SnapPauseWindow? = null

    /** 我们自己加上的 FLAG_KEEP_SCREEN_ON（宿主本来就有的话不碰它的 flags） */
    private var keepScreenOnApplied = false

    /** 同一页「被用户滑走又拉回来」的次数，防死循环 */
    private var resyncCount = 0
    private var lastResyncAt = 0L

    private var saved = 0
    private var failed = 0
    private var titleSeenAt = 0L
    private val runFolder = SnapStorage.newRunFolder()

    /** 已提交、还没写完的存盘数量（只在主线程读写） */
    private var inflight = 0

    /** 在途到上限时记下「存完后要继续的下标」；-1 = 没暂停 */
    private var resumeAt = -1

    /** 所有页都处理过一遍（含跳过 / 失败），在途存盘清零后才收尾 */
    private var captureDone = false

    /** 复用位图：池里只放「已存完、当前无人使用」的；只有主线程碰它 */
    private val bitmapPool = ArrayDeque<Bitmap>()

    /** 逐页计时，收尾时对比前 5 / 后 5 页定位「越截越慢」 */
    private val timings = ArrayList<PageTiming>(16)

    private var pageStartedAt = 0L
    private var titleAt = 0L
    private var readyAt = 0L
    private var polls = 0
    private val jobStartedAt = SystemClock.elapsedRealtime()

    /** 我们自己发起的 finish/cancel；Activity.onPause 看到它时不要再取消一次 */
    val finishingNormally: Boolean get() = finishing

    fun start() {
        main.postDelayed({ runCatching { begin() }.onFailure { fail("启动截图任务异常", it) } }, 400)
    }

    /** 用户点进度条上的「取消」 */
    fun cancelByUser() {
        if (finished) return
        cancelled.set(true)
        finishing = true
        overlay?.remove()
        releaseKeepScreenOn()
        worker.shutdown()
        dropBitmapPool()
        finished = true
        onFinished(Result(items.size, saved, failed, cancelled = true))
        runCatching { activity.finish() }
    }

    /** 页面被返回/切后台/销毁；不主动 finish（系统已经在收页面） */
    fun cancelExternal(reason: String) {
        if (finished) return
        cancelled.set(true)
        finishing = true
        overlay?.remove()
        releaseKeepScreenOn()
        worker.shutdown()
        dropBitmapPool()
        finished = true
        GLog.i("截图并添加：任务中止（$reason）")
        onFinished(Result(items.size, saved, failed, cancelled = true))
    }

    // ------------------------------------------------------------ 启动

    private fun begin() {
        if (cancelled.get()) return
        val p = pager ?: findPager()
        if (p == null) {
            if (pagerAttempts++ < 12) {
                main.postDelayed({ begin() }, 200)
                return
            }
            fail("找不到详情页分页器 vp_stocks")
            return
        }
        pager = p
        val count = adapterCount(p)
        if (count < 0) {
            fail("读不到详情页分页器数量")
            return
        }
        if (count != items.size) {
            fail("详情页分页数量 $count ≠ 选中 ${items.size}（可能列表被宿主重建）")
            return
        }

        // 连续截图可能几十秒到几分钟：屏幕一灭窗口就不可见，任务只能挂起
        acquireKeepScreenOn()
        overlay = SnapProgressOverlay(activity).also { it.onCancel = { cancelByUser() } }
        overlay?.show()
        overlay?.update(1, items.size, items.firstOrNull()?.name.orEmpty())
        klineMode = SnapReadiness.forceKlineTab(activity)
        GLog.i("截图并添加：开始 ${items.size} 只（日K=${klineMode}）")
        capturePage(0)
    }

    private fun findPager(): View? {
        for (name in StgSnapAddTargets.PAGER_ID_CANDIDATES) {
            val id = runCatching {
                activity.resources.getIdentifier(name, "id", StgSnapAddTargets.TARGET_PKG)
            }.getOrDefault(0)
            if (id != 0) {
                activity.findViewById<View>(id)?.let { return it }
            }
        }
        return null
    }

    private fun adapterCount(pager: View): Int {
        val adapter = runCatching {
            pager.javaClass.getMethod("getAdapter").invoke(pager)
        }.getOrNull() ?: return -1
        val count = runCatching {
            adapter.javaClass.getMethod("getCount").invoke(adapter) as? Int
        }.getOrNull() ?: runCatching {
            adapter.javaClass.getMethod("getItemCount").invoke(adapter) as? Int
        }.getOrNull()
        return count ?: -1
    }

    // ------------------------------------------------------------ 逐页

    private fun capturePage(index: Int) {
        if (cancelled.get() || finished) return
        if (index >= items.size) {
            finishNormally()
            return
        }
        val stock = items[index]
        val p = pager ?: run {
            fail("分页器丢了")
            return
        }
        titleSeenAt = 0L
        titleAt = 0L
        readyAt = 0L
        polls = 0
        resyncCount = 0
        lastResyncAt = 0L
        pageStartedAt = SystemClock.elapsedRealtime()
        overlay?.update(index + 1, items.size, stock.name)
        val timeout = if (index == 0) {
            StgSnapAddTargets.FIRST_READY_TIMEOUT_MS
        } else {
            StgSnapAddTargets.READY_TIMEOUT_MS
        }
        gate = SnapPauseWindow(timeout).also { it.start(pageStartedAt) }
        if (!switchTo(index, p)) {
            skipPage(index, stock, "切页失败")
            return
        }
        main.postDelayed({ waitReady(index, stock) }, StgSnapAddTargets.PAGE_SETTLE_MS)
    }

    /** 程序化切页；宿主换实现时返回 false */
    private fun switchTo(index: Int, p: View): Boolean = runCatching {
        p.javaClass
            .getMethod(
                "setCurrentItem",
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            )
            .invoke(p, index, false)
        true
    }.getOrElse {
        GLog.w("截图并添加：setCurrentItem($index) 失败", it)
        false
    }

    private fun waitReady(index: Int, stock: SelectedStock) {
        if (cancelled.get() || finished) return
        polls++
        if (activity.isFinishing || activity.isDestroyed) {
            cancelExternal("详情页已结束")
            return
        }
        val timer = gate ?: run {
            fail("等待计时器丢了")
            return
        }
        val now = SystemClock.elapsedRealtime()
        // 窗口不可截（退到后台 / 熄屏 / 小窗被收起）：**挂起等待**。不跳页、不记失败、
        // 不计超时 —— 用户随时可以回来接着截；能打断任务的只有进度条上的「取消」。
        if (!canCapture()) {
            if (timer.pause(now)) {
                overlay?.updatePaused(index + 1, items.size, stock.name)
                GLog.i(
                    "截图并添加：窗口不可截，任务挂起（第 ${index + 1}/${items.size} 页，" +
                        "等窗口回来继续）"
                )
            }
            main.postDelayed({ waitReady(index, stock) }, StgSnapAddTargets.PAUSE_POLL_MS)
            return
        }
        val pausedMs = timer.resume(now)
        if (pausedMs > 0) {
            GLog.i(
                "截图并添加：窗口回来，挂起 ${pausedMs}ms 后继续第 ${index + 1}/${items.size} 页"
            )
            overlay?.update(index + 1, items.size, stock.name)
        }
        val p = pager ?: run {
            fail("分页器丢了")
            return
        }
        val current = runCatching {
            p.javaClass.getMethod("getCurrentItem").invoke(p) as? Int
        }.getOrNull()
        // 用户在小窗里手动滑到别的页：拉回本页再等，别把这一页判成失败
        if (current != index && resyncPage(index, p, now)) {
            main.postDelayed({ waitReady(index, stock) }, StgSnapAddTargets.PAGE_SETTLE_MS)
            return
        }
        val onPage = current == index
        val titleOk = onPage && SnapReadiness.titleMatches(activity, stock.code)
        if (titleOk && titleSeenAt == 0L) {
            titleSeenAt = now
            titleAt = now
        }
        val chartOk = if (klineMode) {
            titleOk && SnapReadiness.klineReady(activity, env)
        } else {
            titleOk && titleSeenAt > 0L &&
                now - titleSeenAt >= StgSnapAddTargets.TITLE_SETTLE_MS
        }

        if (titleOk && chartOk) {
            readyAt = now
            captureNow(index, stock)
            return
        }
        if (timer.expired(now)) {
            // 数据没有全部拉出来：只记失败，不截图
            val why = buildString {
                if (!onPage) append("pager 当前页=$current，")
                if (!titleOk) append("标题未命中 ${stock.code}，")
                if (titleOk && !chartOk) append("K 线数据未就绪")
            }.trimEnd('，')
            skipPage(index, stock, why.ifEmpty { "数据未就绪" })
            return
        }
        main.postDelayed({ waitReady(index, stock) }, StgSnapAddTargets.READY_POLL_MS)
    }

    /** 用户手动滑到别的页：拉回本页（限次）；返回 true 表示已重新排队等待 */
    private fun resyncPage(index: Int, p: View, now: Long): Boolean {
        if (resyncCount >= StgSnapAddTargets.MAX_PAGE_RESYNC) return false
        if (now - lastResyncAt < StgSnapAddTargets.PAGE_RESYNC_IDLE_MS) return false
        resyncCount++
        lastResyncAt = now
        GLog.i("截图并添加：页面被滑到别处，拉回第 ${index + 1}/${items.size} 页（第 $resyncCount 次）")
        return switchTo(index, p)
    }

    private fun skipPage(index: Int, stock: SelectedStock, why: String) {
        if (finished) return
        failed++
        GLog.w("截图并添加：第 ${index + 1}/${items.size} 只数据未就绪（$why），不截图")
        overlay?.restoreAfterCapture()
        next(index + 1)
    }

    // ------------------------------------------------------------ 截屏

    private fun captureNow(index: Int, stock: SelectedStock) {
        if (cancelled.get() || finished) return
        if (!canCapture()) {
            // 就绪到拍照之间窗口走了：绝不保存旧帧，退回等待
            resumeWait(index, stock, "拍照前窗口已不可截")
            return
        }
        overlay?.hideForCapture()
        main.postDelayed(
            {
                if (cancelled.get() || finished) return@postDelayed
                if (!canCapture()) {
                    overlay?.restoreAfterCapture()
                    resumeWait(index, stock, "拍照前窗口已不可截")
                    return@postDelayed
                }
                runCatching { pixelCopy(index, stock) }
                    .onFailure { onCaptureFailed(index, stock, it) }
            },
            StgSnapAddTargets.CAPTURE_DELAY_MS,
        )
    }

    /**
     * 窗口现在可不可截。
     *
     * 只要求**窗口仍然可见**：小窗 / 分屏下宿主可能没有焦点，但 surface 仍在更新，
     * PixelCopy 拿到的就是当页内容 —— 这正是「截图期间可以去做别的事」的前提。
     * 退到后台时 `windowVisibility` 变成 INVISIBLE，surface 不再更新，此时截下去
     * 只会拿到上一张的旧帧，必须挂起等待。
     */
    private fun canCapture(): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val decor = activity.window?.decorView ?: return false
        if (!decor.isAttachedToWindow) return false
        return decor.windowVisibility == View.VISIBLE
    }

    /** 挂起当前页并重新排队等待（不记失败、不计超时） */
    private fun resumeWait(index: Int, stock: SelectedStock, why: String) {
        val timer = gate ?: return
        overlay?.restoreAfterCapture()
        if (timer.pause(SystemClock.elapsedRealtime())) {
            overlay?.updatePaused(index + 1, items.size, stock.name)
            GLog.i("截图并添加：$why，挂起等待（第 ${index + 1}/${items.size} 页）")
        }
        main.postDelayed({ waitReady(index, stock) }, StgSnapAddTargets.PAUSE_POLL_MS)
    }

    /**
     * 连续截图期间屏幕常亮。
     *
     * 宿主自己已经开着常亮（flags 里带 FLAG_KEEP_SCREEN_ON）时不动它，收尾也不清 ——
     * 那是宿主的状态，不是我们加的。
     */
    private fun acquireKeepScreenOn() {
        val window = activity.window ?: return
        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) {
            GLog.i("截图并添加：宿主已开屏幕常亮，任务结束后不动它的 flags")
            return
        }
        runCatching {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepScreenOnApplied = true
            GLog.i("截图并添加：已开启屏幕常亮（截图期间不熄屏）")
        }.onFailure { GLog.w("截图并添加：开启屏幕常亮失败", it) }
    }

    private fun releaseKeepScreenOn() {
        if (!keepScreenOnApplied) return
        keepScreenOnApplied = false
        runCatching {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }.onFailure { GLog.w("截图并添加：关闭屏幕常亮失败", it) }
    }

    private fun pixelCopy(index: Int, stock: SelectedStock) {
        val window = activity.window ?: run {
            onCaptureFailed(index, stock, IllegalStateException("window=null"))
            return
        }
        val decor = window.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) {
            onCaptureFailed(index, stock, IllegalStateException("decor $w x $h"))
            return
        }
        val bitmap = acquireBitmap(w, h)
        // 复用位图时先把上一张的内容清掉：PixelCopy 正常会铺满整张，但源窗口若被
        // 系统裁过（分屏/刘海），没铺到的地方不应该残留上一页的画面
        bitmap.eraseColor(0)
        runCatching {
            PixelCopy.request(window, bitmap, { result ->
                overlay?.restoreAfterCapture()
                if (cancelled.get() || finished) {
                    releaseBitmap(bitmap)
                } else if (result == PixelCopy.SUCCESS) {
                    onCaptured(index, stock, bitmap)
                } else if (!canCapture()) {
                    // 窗口就在这几帧里退到后台：PixelCopy 拿不到新帧，等窗口回来再截这一页
                    releaseBitmap(bitmap)
                    resumeWait(index, stock, "PixelCopy 失败 result=$result 且窗口已不可截")
                } else {
                    GLog.w("截图并添加：PixelCopy 失败 result=$result，尝试 decorView.draw")
                    fallbackDraw(bitmap, index, stock) { t ->
                        if (canCapture()) onCaptureFailed(index, stock, t)
                        else resumeWait(index, stock, "截图失败且窗口已不可截（${t.javaClass.simpleName}）")
                    }
                }
            }, main)
        }.onFailure {
            releaseBitmap(bitmap)
            if (canCapture()) {
                onCaptureFailed(index, stock, it)
            } else {
                resumeWait(index, stock, "PixelCopy 请求异常且窗口已不可截（${it.javaClass.simpleName}）")
            }
        }
    }

    private fun fallbackDraw(
        bitmap: Bitmap,
        index: Int,
        stock: SelectedStock,
        onFail: (Throwable) -> Unit,
    ) {
        runCatching {
            bitmap.eraseColor(0)
            activity.window.decorView.draw(Canvas(bitmap))
            onCaptured(index, stock, bitmap)
        }.onFailure {
            releaseBitmap(bitmap)
            onFail(it)
        }
    }

    /**
     * PixelCopy 拿到像素：记一次计时，把位图交给后台存盘，**主线程立刻翻下一页**。
     *
     * 这是「越截越慢」整改的核心：PNG 压缩与相册写入不再挡在翻页前面。翻页只受
     * [StgSnapAddTargets.MAX_INFLIGHT_SAVES] 约束（位图不能无限排队）；存完一张由
     * [afterSaveStep] 接上被暂停的翻页。
     */
    private fun onCaptured(index: Int, stock: SelectedStock, bitmap: Bitmap) {
        if (cancelled.get() || finished) {
            releaseBitmap(bitmap)
            return
        }
        val timing = PageTiming(
            index = index,
            switchMs = (titleAt - pageStartedAt).coerceAtLeast(0),
            readyMs = (readyAt - titleAt).coerceAtLeast(0),
            copyMs = (SystemClock.elapsedRealtime() - readyAt).coerceAtLeast(0),
        ).apply { polls = this@SnapCaptureJob.polls }
        timings += timing
        overlay?.restoreAfterCapture()
        submitSave(bitmap, index, stock, timing)
        if (inflight < StgSnapAddTargets.MAX_INFLIGHT_SAVES) {
            next(index + 1)
        } else {
            resumeAt = index + 1
        }
    }

    private fun onCaptureFailed(index: Int, stock: SelectedStock, t: Throwable) {
        if (finished) return
        overlay?.restoreAfterCapture()
        GLog.w("截图并添加：第 ${index + 1}/${items.size} 只截屏失败，不保存", t)
        failed++
        next(index + 1)
    }

    // ------------------------------------------------------------ 存盘（与翻页并行）

    private fun submitSave(
        bitmap: Bitmap,
        index: Int,
        stock: SelectedStock,
        timing: PageTiming,
    ) {
        val fileName = StgSnapPlan.fileName(index + 1, stock.name, stock.code)
        val appContext = activity.applicationContext
        val submittedAt = SystemClock.elapsedRealtime()
        inflight++
        try {
            worker.execute {
                // SnapStorage 自己吞异常返回 null；这里再包一层，保证「无论发生什么
                // 都要回到主线程把在途计数减掉」，否则整批永远收不了尾
                val savedFile = runCatching {
                    SnapStorage.save(appContext, bitmap, fileName, runFolder)
                }.getOrNull()
                main.post {
                    inflight--
                    if (!finished) {
                        if (savedFile != null) {
                            saved++
                        } else {
                            failed++
                            GLog.w("截图并添加：第 ${index + 1} 张保存失败")
                        }
                    }
                    releaseBitmap(bitmap)
                    timing.saveMs = (SystemClock.elapsedRealtime() - submittedAt).coerceAtLeast(0)
                    logPageTiming(timing)
                    afterSaveStep()
                }
            }
        } catch (t: Throwable) {
            // 提交失败等价于「这一张存完但没落盘」：计数、计时都按一次保存收尾，
            // 翻页继续由 onCaptured 统一推进（这里不再调 afterSaveStep，避免双重推进）
            inflight--
            releaseBitmap(bitmap)
            timing.saveMs = (SystemClock.elapsedRealtime() - submittedAt).coerceAtLeast(0)
            logPageTiming(timing)
            if (!finished) {
                GLog.w("截图并添加：保存任务提交失败", t)
                failed++
            }
        }
    }

    /** 一张存完：先接上被「在途上限」暂停的翻页，再看整批是否已经可以收尾 */
    private fun afterSaveStep() {
        if (finished) return
        val pending = resumeAt
        if (pending >= 0) {
            resumeAt = -1
            next(pending)
        } else {
            maybeFinish()
        }
    }

    private fun next(index: Int) {
        if (cancelled.get() || finished) return
        if (index >= items.size) {
            captureDone = true
            maybeFinish()
        } else {
            capturePage(index)
        }
    }

    /** 翻完 + 存盘清零才收尾；提前 finish 会把还没写完的图丢掉 */
    private fun maybeFinish() {
        if (finished || !captureDone || inflight > 0) return
        finishNormally()
    }

    // ------------------------------------------------------------ 位图池

    private fun acquireBitmap(w: Int, h: Int): Bitmap {
        while (bitmapPool.isNotEmpty()) {
            val reused = bitmapPool.removeLast()
            if (!reused.isRecycled && reused.width == w && reused.height == h) return reused
            runCatching { reused.recycle() }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    /** 位图交还：只在「存盘已返回」或「PixelCopy 回调已到」之后调用 */
    private fun releaseBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        if (finished) {
            runCatching { bitmap.recycle() }
            return
        }
        if (bitmapPool.size < POOL_SIZE) {
            bitmapPool.addLast(bitmap)
        } else {
            runCatching { bitmap.recycle() }
        }
    }

    private fun dropBitmapPool() {
        while (bitmapPool.isNotEmpty()) runCatching { bitmapPool.removeLast().recycle() }
    }

    // ------------------------------------------------------------ 收尾

    private fun finishNormally() {
        if (finished) return
        finished = true
        finishing = true
        overlay?.remove()
        releaseKeepScreenOn()
        worker.shutdown()
        dropBitmapPool()
        val totalMs = SystemClock.elapsedRealtime() - jobStartedAt
        GLog.i(
            "截图并添加：完成 ${items.size} 只，成功 $saved，未就绪/失败 $failed；" +
                "总耗时 ${totalMs}ms（页均 ${if (items.isEmpty()) 0 else totalMs / items.size}ms）" +
                timingSummary() +
                "；内存 native ${mb(Debug.getNativeHeapAllocatedSize())}MB" +
                "/java ${mb(Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })}MB"
        )
        onFinished(Result(items.size, saved, failed, cancelled = false))
        runCatching { activity.finish() }
    }

    private fun fail(message: String, t: Throwable? = null) {
        if (finished) return
        finished = true
        finishing = true
        overlay?.remove()
        releaseKeepScreenOn()
        worker.shutdown()
        dropBitmapPool()
        if (t != null) GLog.w("截图并添加：$message", t) else GLog.w("截图并添加：$message")
        onFinished(Result(items.size, saved, failed, cancelled = false, error = message))
        runCatching { activity.finish() }
    }

    // ------------------------------------------------------------ 计时（排障）

    private fun logPageTiming(timing: PageTiming) {
        if (!GLog.isDebug) return
        GLog.d(
            "截图并添加：第 ${timing.index + 1}/${items.size} 只 用时" +
                " 切页 ${timing.switchMs}ms / 就绪 ${timing.readyMs}ms（轮询 ${timing.polls} 次）" +
                " / 拷贝 ${timing.copyMs}ms / 存盘 ${timing.saveMs}ms" +
                " / native ${mb(Debug.getNativeHeapAllocatedSize())}MB" +
                " java ${mb(Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })}MB"
        )
    }

    /**
     * 前 5 页 / 后 5 页的分阶段均值 —— 判读「越截越慢」拐在哪一段：
     *  - 就绪变慢 → 宿主行情请求 / 页面数据累积；
     *  - 存盘变慢 → MediaProvider / GC；
     *  - 四段一起变慢 → 主线程被宿主拖慢或热降频。
     */
    private fun timingSummary(): String {
        if (timings.isEmpty()) return "；无可计时页"
        fun avg(list: List<PageTiming>, pick: (PageTiming) -> Long): Long =
            if (list.isEmpty()) 0L else list.sumOf(pick) / list.size
        fun stages(list: List<PageTiming>): String = listOf(
            avg(list) { it.switchMs },
            avg(list) { it.readyMs },
            avg(list) { it.copyMs },
            avg(list) { it.saveMs },
        ).joinToString("/")
        return "；切页/就绪/拷贝/存盘 ms 页均=" + stages(timings) +
            "，前5页=" + stages(timings.take(5)) +
            "，后5页=" + stages(timings.takeLast(5))
    }

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)

    private companion object {
        /** 复用位图张数：与在途存盘上限对齐，多出来的只是白占 10MB/张 */
        const val POOL_SIZE = 2
    }
}
