package io.github.MVennn.GSAuxiliary.feature.stgsnap

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
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
 *        → 隐藏进度条 → PixelCopy → 后台写相册 → 下一页
 *   → 完成：移除进度条，回调结果，finish() 回策略页
 * ```
 *
 * **数据没就绪的一页不截图**：超时后只记失败、直接翻下一页，绝不保存半张图。
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

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)

    private var finished = false
    private var finishing = false
    private var pager: View? = null
    private var pagerAttempts = 0
    private var klineMode = false
    private var overlay: SnapProgressOverlay? = null
    private var saved = 0
    private var failed = 0
    private var titleSeenAt = 0L
    private val startedAt = System.currentTimeMillis()
    private val runFolder = SnapStorage.newRunFolder()

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
        worker.shutdown()
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
        worker.shutdown()
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
        overlay?.update(index + 1, items.size, stock.name)
        val switched = runCatching {
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
        if (!switched) {
            skipPage(index, stock, "切页失败")
            return
        }
        val timeout = if (index == 0) {
            StgSnapAddTargets.FIRST_READY_TIMEOUT_MS
        } else {
            StgSnapAddTargets.READY_TIMEOUT_MS
        }
        main.postDelayed(
            { waitReady(index, stock, System.currentTimeMillis() + timeout) },
            StgSnapAddTargets.PAGE_SETTLE_MS,
        )
    }

    private fun waitReady(index: Int, stock: SelectedStock, deadline: Long) {
        if (cancelled.get() || finished) return
        if (activity.isFinishing) {
            cancelExternal("详情页已结束")
            return
        }
        // 切后台 / 用户回到桌面：页面还在但窗口没焦点，继续截只会拿到旧帧
        if (System.currentTimeMillis() - startedAt > 2000 && !activity.hasWindowFocus()) {
            cancelExternal("详情页失去焦点")
            return
        }
        val p = pager ?: run {
            fail("分页器丢了")
            return
        }
        val now = System.currentTimeMillis()
        val current = runCatching {
            p.javaClass.getMethod("getCurrentItem").invoke(p) as? Int
        }.getOrNull()
        val onPage = current == index
        val titleOk = onPage && SnapReadiness.titleMatches(activity, stock.code)
        if (titleOk && titleSeenAt == 0L) titleSeenAt = now
        val chartOk = if (klineMode) {
            titleOk && SnapReadiness.klineReady(activity, env)
        } else {
            titleOk && titleSeenAt > 0L &&
                now - titleSeenAt >= StgSnapAddTargets.TITLE_SETTLE_MS
        }

        if (titleOk && chartOk) {
            captureNow(index, stock)
            return
        }
        if (now >= deadline) {
            // 数据没有全部拉出来：只记失败，不截图
            val why = buildString {
                if (!onPage) append("pager 当前页=$current，")
                if (!titleOk) append("标题未命中 ${stock.code}，")
                if (titleOk && !chartOk) append("K 线数据未就绪")
            }.trimEnd('，')
            skipPage(index, stock, why.ifEmpty { "数据未就绪" })
            return
        }
        main.postDelayed(
            { waitReady(index, stock, deadline) },
            StgSnapAddTargets.READY_POLL_MS,
        )
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
        overlay?.hideForCapture()
        main.postDelayed(
            { runCatching { pixelCopy(index, stock) }.onFailure { onCaptureFailed(index, stock, it) } },
            StgSnapAddTargets.CAPTURE_DELAY_MS,
        )
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
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(window, bitmap, { result ->
                overlay?.restoreAfterCapture()
                if (cancelled.get() || finished) {
                    bitmap.recycle()
                } else if (result == PixelCopy.SUCCESS) {
                    saveBitmap(bitmap, index, stock)
                } else {
                    GLog.w("截图并添加：PixelCopy 失败 result=$result，尝试 decorView.draw")
                    fallbackDraw(bitmap, index, stock) { onCaptureFailed(index, stock, it) }
                }
            }, main)
        }.onFailure {
            bitmap.recycle()
            onCaptureFailed(index, stock, it)
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
            saveBitmap(bitmap, index, stock)
        }.onFailure {
            bitmap.recycle()
            onFail(it)
        }
    }

    private fun onCaptureFailed(index: Int, stock: SelectedStock, t: Throwable) {
        if (finished) return
        overlay?.restoreAfterCapture()
        GLog.w("截图并添加：第 ${index + 1}/${items.size} 只截屏失败，不保存", t)
        failed++
        next(index + 1)
    }

    // ------------------------------------------------------------ 存盘

    private fun saveBitmap(bitmap: Bitmap, index: Int, stock: SelectedStock) {
        if (cancelled.get() || finished) {
            bitmap.recycle()
            return
        }
        val fileName = StgSnapPlan.fileName(index + 1, stock.name, stock.code)
        val appContext = activity.applicationContext
        try {
            worker.execute {
                val savedFile = SnapStorage.save(appContext, bitmap, fileName, runFolder)
                bitmap.recycle()
                main.post {
                    if (finished) return@post
                    if (savedFile != null) {
                        saved++
                    } else {
                        failed++
                        GLog.w("截图并添加：第 ${index + 1} 张保存失败")
                    }
                    next(index + 1)
                }
            }
        } catch (t: Throwable) {
            bitmap.recycle()
            if (!finished) {
                GLog.w("截图并添加：保存任务提交失败", t)
                failed++
                next(index + 1)
            }
        }
    }

    private fun next(index: Int) {
        if (cancelled.get() || finished) return
        if (index >= items.size) {
            finishNormally()
        } else {
            capturePage(index)
        }
    }

    // ------------------------------------------------------------ 收尾

    private fun finishNormally() {
        if (finished) return
        finished = true
        finishing = true
        overlay?.remove()
        worker.shutdown()
        GLog.i("截图并添加：完成 ${items.size} 只，成功 $saved，未就绪/失败 $failed")
        onFinished(Result(items.size, saved, failed, cancelled = false))
        runCatching { activity.finish() }
    }

    private fun fail(message: String, t: Throwable? = null) {
        if (finished) return
        finished = true
        finishing = true
        overlay?.remove()
        worker.shutdown()
        if (t != null) GLog.w("截图并添加：$message", t) else GLog.w("截图并添加：$message")
        onFinished(Result(items.size, saved, failed, cancelled = false, error = message))
        runCatching { activity.finish() }
    }
}
