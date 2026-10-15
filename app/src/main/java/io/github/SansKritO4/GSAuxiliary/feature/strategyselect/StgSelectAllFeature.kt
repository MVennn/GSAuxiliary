package io.github.SansKritO4.GSAuxiliary.feature.strategyselect

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.core.HookBag
import io.github.SansKritO4.GSAuxiliary.feature.Feature
import io.github.SansKritO4.GSAuxiliary.feature.FeatureEnv
import io.github.SansKritO4.GSAuxiliary.util.Reflect
import java.util.Collections
import java.util.WeakHashMap

/**
 * 功能：策略详情页「多选栏」里，在「取消」按钮**左侧**注入一个「全选 / 反选」按钮，
 * 一键勾选 / 取消勾选该页全部股票。**LSPosed 运行时视图注入，不改目标 App 源码。**
 *
 * ## 实测契约（详见 [StgSelectAllTargets]）
 *
 *  - 那颗按钮是 `#tv_stg_add_zxg`：正常态文案「勾选加入自选股」，进入多选态后
 *    文案变「取消」（同一颗 View，只是改字）。
 *  - 它位于 ListView `#lvStks` 的 **header** 里一个横向 `LinearLayout` 中。
 *  - 每行的勾选框是 `#ct_item`（`CheckedTextView`）；**点击整行**即切换勾选，
 *    底部「已选 N」会同步 —— 所以「全选 / 反选」= 对各行触发一次行点击即可，
 *    走的是和用户手动点行**完全同一条**代码路径，无需知道宿主的选中数据模型。
 *
 * ## 文案随状态切换（全选 / 反选）
 *
 *  - 会重算文案的时机：① 进入多选态注入时；② 每次行点击（包裹宿主 `OnItemClickListener`）；
 *    ③ 本按钮被点击后。
 *  - 判定：可见行的勾选数 == 适配器总行数 → 文案「反选」（点击取消全部），
 *    否则「全选」（点击勾选全部）。
 *
 * ## 触发方式：给「取消」按钮挂 TextWatcher（而不是全局 Hook `TextView.setText`）
 *
 * 进入 / 退出多选态都会把该按钮文案在「勾选加入自选股」↔「取消」之间切换。
 * 直接 Hook `TextView.setText` 是全 App（尤其行情页实时报价）的**超热路径**，
 * 会带来卡顿风险；所以只盯这一颗按钮挂 `TextWatcher`，零全局开销。
 *
 * ## 「样式与取消按钮保持一致」怎么保证
 *
 * 「全选」按钮由「取消」按钮**克隆**而来：背景（含状态）、文字色、字号、字体、
 * 内边距、对齐、行数、最小尺寸、LayoutParams（含 margin）逐项复制。
 * 与「取消」之间的间距**只加在注入按钮自己的 `rightMargin` 上**，不改「取消」自身任何属性。
 */
class StgSelectAllFeature : Feature {

    override val id = "stg_select_all"
    override val title = "策略详情页多选全选"

    /** 「取消」按钮资源 id 缓存（0 = 还没解析过） */
    @Volatile private var cancelIdCache = 0

    @Volatile private var listIdCache = 0

    @Volatile private var checkboxIdCache = 0

    @Volatile private var countIdCache = 0

    /** 已挂过 TextWatcher 的按钮（弱引用，避免泄漏） */
    private val watchedButtons =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<TextView, Boolean>()))

    /** 已包裹过行点击监听的 ListView */
    private val wrappedLists =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<ListView, Boolean>()))

    /** 「取消」按钮 → 我们注入的「全选 / 反选」按钮（弱引用） */
    private val injected =
        Collections.synchronizedMap(WeakHashMap<TextView, TextView>())

    /** ListView → 当前的注入按钮（行点击时据此刷新文案；弱引用） */
    private val buttonByList =
        Collections.synchronizedMap(WeakHashMap<ListView, TextView>())

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.stgSelectAll

    // ------------------------------------------------------------------ 安装

    override fun install(env: FeatureEnv, bag: HookBag) {
        watchActivities(env, bag)
    }

    /** 挂 `Activity.onResume`，只对策略详情页做「挂 watcher + 同步」这件事 */
    private fun watchActivities(env: FeatureEnv, bag: HookBag) {
        val cls = Reflect.loadClass("android.app.Activity", env.classLoader)
        if (cls == null) {
            GLog.e("全选：找不到 android.app.Activity，功能不会生效")
            return
        }
        var hooked = 0
        for (m in Reflect.methodsNamed(cls, "onResume")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("stgselectall:onResume")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val self = chain.thisObject
                            if (self is Activity) onActivityResumed(env, self)
                        }.onFailure { GLog.w("全选：onResume 处理异常", it) }
                        result
                    }
            )
            hooked++
        }
        if (hooked == 0) GLog.e("全选：没挂上 Activity.onResume") else
            GLog.i("策略全选：已挂 $hooked 个 Activity.onResume")
    }

    private fun onActivityResumed(env: FeatureEnv, activity: Activity) {
        if (!StgSelectAllTargets.isWatchActivity(activity.javaClass.name)) return

        val cancelId = cancelButtonId(activity)
        if (cancelId == 0) {
            GLog.w("全选：找不到 ${StgSelectAllTargets.CANCEL_BUTTON_ID_CANDIDATES.joinToString()}")
            return
        }
        val btn = activity.findViewById<View>(cancelId) as? TextView ?: return

        ensureWatcher(env, btn)
        // 页面可能带着多选态回来（少见），进来先同步一次，保证注入状态与当前态一致
        sync(env, btn)
    }

    /** 给「取消」按钮挂上（幂等的）TextWatcher，观察多选态切换 */
    private fun ensureWatcher(env: FeatureEnv, btn: TextView) {
        if (!watchedButtons.add(btn)) return
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runCatching { sync(env, btn) }
                    .onFailure { GLog.w("全选：同步注入状态异常", it) }
            }
        }
        btn.addTextChangedListener(watcher)
        GLog.i("策略全选：已观察多选按钮的文案变化")
    }

    // ------------------------------------------------------------------ 核心

    /** 根据当前是否处于多选态，注入 / 移除「全选 / 反选」按钮 */
    private fun sync(env: FeatureEnv, btn: TextView) {
        val cfg = env.config
        if (!cfg.stgSelectAll) {
            // 功能被关掉：撤掉当前这颗锚点对应的按钮
            removeInjected(btn)
            return
        }
        val text = btn.text?.toString().orEmpty()
        if (text == StgSelectAllTargets.CANCEL_TEXT) {
            injectSelectAll(env, btn)
        } else {
            removeInjected(btn)
        }
    }

    /** 幂等地把「全选 / 反选」按钮插到「取消」左侧 */
    private fun injectSelectAll(env: FeatureEnv, anchor: TextView) {
        val existing = injected[anchor]
        if (existing != null && existing.parent === anchor.parent) {
            // 已注入：只刷新一下文案（例如从别处切回多选态）
            findListView(anchor)?.let { updateLabel(env, it, existing) }
            return
        }
        if (existing != null) removeInjected(anchor)   // 视图被换了，先清旧的

        val parent = anchor.parent as? ViewGroup ?: return
        val button = runCatching { buildSelectAll(env, anchor) }.getOrNull() ?: return

        val index = parent.indexOfChild(anchor)
        runCatching {
            if (index in 0..parent.childCount) {
                parent.addView(button, index)
            } else {
                parent.addView(button)
            }
        }.onFailure {
            GLog.w("全选：插入按钮失败", it)
            return
        }
        injected[anchor] = button

        // 行点击时也要刷新「全选 / 反选」文案，所以包裹一次 ListView 的行点击监听
        val lv = findListView(anchor)
        if (lv != null) {
            buttonByList[lv] = button
            ensureRowClickWatcher(env, lv)
            updateLabel(env, lv, button)
        }
        GLog.i("策略全选：已在多选栏注入「全选」按钮")
    }

    /** 从「取消」按钮克隆出一颗样式一致、但**独立 id** 的「全选 / 反选」按钮 */
    private fun buildSelectAll(env: FeatureEnv, src: TextView): TextView {
        val btn = TextView(src.context)
        btn.text = StgSelectAllTargets.SELECT_ALL_TEXT
        copyStyle(src, btn)
        runCatching { btn.layoutParams = buildLayoutParams(src) }
        // 独立 id：避免与原「取消」按钮共用 NO_ID/同 id 造成 findViewById 歧义，
        // 也便于我们准确定位自己注入的这颗按钮
        runCatching { btn.id = View.generateViewId() }
        // minWidth =「全选」「反选」里较宽者所需宽度（文字宽 + 左右内边距），
        // 保证文案切换不抖动、也不会把「取消」挤出。
        // 用 paint 量文字而**不是**取 src.width —— 后者在「勾选加入自选股 → 取消」
        // 文案切换途中会拿到旧（5 字、更宽）文案的宽度，导致按钮被撑得过宽。
        runCatching {
            val paint = src.paint
            val textPx = maxOf(
                paint.measureText(StgSelectAllTargets.SELECT_ALL_TEXT),
                paint.measureText(StgSelectAllTargets.INVERT_TEXT),
            ).toInt()
            btn.minWidth = src.paddingLeft + src.paddingRight + textPx
        }

        btn.isClickable = true
        btn.isFocusable = true

        btn.setOnClickListener { v ->
            runCatching { toggleAll(env, v) }
                .onFailure { GLog.w("全选：勾选/取消全部股票异常", it) }
        }
        return btn
    }

    private fun removeInjected(anchor: TextView) {
        val btn = injected.remove(anchor) ?: return
        runCatching { (btn.parent as? ViewGroup)?.removeView(btn) }
        val lv = buttonByList.entries.firstOrNull { it.value === btn }?.key
        if (lv != null) buttonByList.remove(lv)
    }

    /**
     * 「全选 / 反选」的实际动作：
     *  - 未全选 → 让每只都勾上（勾选全部）；
     *  - 已全选 → 让每只都取消（取消全部）。
     *
     * 走宿主自己的 `OnItemClickListener`（`performItemClick`）—— 与用户手动点行完全同一条
     * 路径，宿主的选中模型与底部「已选 N」都会自然同步。
     *
     * **必须覆盖屏外行**（不能只看 ListView 的可见子 View）：屏外行没有已绑定的子 View，
     * 这里用 `Adapter.getView(pos, null, lv)` 现造一个「探针 View」——它会按宿主选中模型把
     * `#ct_item` 绑定到正确状态，于是能读到该行**真实**的勾选态；再把这个 View 传给
     * `performItemClick`（宿主用 `position` 定位，不依赖 View 是否在屏上）。
     *
     * **必须分帧**：宿主每次切换勾选后会刷新列表（`notifyDataSetChanged`），同一帧里继续拿
     * 旧的子 View 会拿到已失效的视图（真机实测「反选」只清一行的根因）。
     *
     * 「是否已全选」以宿主维护的底部计数 `#tv_bottom_zxg_num` 为准（它含屏外行）。
     */
    private fun toggleAll(env: FeatureEnv, v: View) {
        val lv = findListView(v)
        if (lv == null) {
            GLog.w("全选：找不到 ${StgSelectAllTargets.LIST_ID_CANDIDATES.joinToString()} 列表")
            return
        }
        val realAdapter = unwrapHeaderAdapter(lv.adapter) ?: return
        val ctId = checkboxId(v)
        if (ctId == 0) {
            // 勾选框 id 解析不到就无法判断每行状态，盲目点击会把「全选」点成「反选」→ 保守放弃
            GLog.w("全选：#${StgSelectAllTargets.CHECKBOX_ID_CANDIDATES.joinToString()} 解析失败，放弃本次操作")
            return
        }
        val headerCount = lv.headerViewsCount
        val total = realAdapter.count
        if (total <= 0) return

        val cur = selectedCount(v)
        val allChecked = cur == total
        val wantChecked = !allChecked
        // 计数明确（一个没选 / 全选）时无需逐行探状态，直接全量点击，省掉屏外行的 getView 开销
        val needProbe = cur > 0 && cur < total
        val button = v as? TextView

        GLog.i("策略全选：${if (allChecked) "反选" else "全选"} 开始（共 $total 只，当前 $cur，needProbe=$needProbe）")
        stepToggle(env, lv, realAdapter, button, ctId, headerCount, total, wantChecked, needProbe, 0)
    }

    /** 处理第 [index] 只股票（含屏外），然后 `postDelayed` 到下一帧处理 index+1 */
    private fun stepToggle(
        env: FeatureEnv,
        lv: ListView,
        adapter: android.widget.ListAdapter,
        button: TextView?,
        ctId: Int,
        headerCount: Int,
        total: Int,
        wantChecked: Boolean,
        needProbe: Boolean,
        index: Int,
    ) {
        if (index >= total) {
            if (button != null) updateLabel(env, lv, button)
            return
        }
        runCatching {
            val pos = headerCount + index           // ListView 里的位置（含 header 偏移）
            val childIdx = pos - lv.firstVisiblePosition
            val visible = childIdx in 0 until lv.childCount
            // 可见行用已绑定的真实子 View；屏外行现造探针 View（getView 会按模型绑定勾选态）
            val row: View? = if (visible) {
                lv.getChildAt(childIdx)
            } else {
                runCatching { adapter.getView(index, null, lv) }.getOrNull()
            }
            val isChecked = (row?.findViewById<View>(ctId) as? CheckedTextView)?.isChecked == true
            val shouldClick = !needProbe || (isChecked != wantChecked)
            if (shouldClick) {
                lv.performItemClick(row, pos, lv.getItemIdAtPosition(pos))
            }
        }.onFailure { GLog.w("全选：触发行点击失败 index=$index", it) }

        lv.postDelayed(
            { runCatching { stepToggle(env, lv, adapter, button, ctId, headerCount, total, wantChecked, needProbe, index + 1) } },
            TOGGLE_STEP_MS,
        )
    }

    /** 按当前选中状态刷新按钮文案：已全选 → 「反选」，否则 → 「全选」 */
    private fun updateLabel(env: FeatureEnv, lv: ListView, button: TextView) {
        val realAdapter = unwrapHeaderAdapter(lv.adapter) ?: return
        val total = realAdapter.count
        val cur = selectedCount(button)
        val label = if (total > 0 && cur == total) {
            StgSelectAllTargets.INVERT_TEXT
        } else {
            StgSelectAllTargets.SELECT_ALL_TEXT
        }
        runCatching {
            if (button.text?.toString() != label) button.text = label
        }
    }

    /** 读底部「已选 N」的计数（宿主维护，含屏外行）；读不到返回 -1 */
    private fun selectedCount(v: View): Int {
        val id = countId(v)
        if (id == 0) return -1
        val root = v.rootView ?: return -1
        val tv = root.findViewById<View>(id) as? TextView ?: return -1
        return runCatching { tv.text?.toString()?.trim()?.toIntOrNull() ?: -1 }.getOrDefault(-1)
    }

    /** 包裹宿主 ListView 的行点击监听：手动点行后也刷新「全选 / 反选」文案 */
    private fun ensureRowClickWatcher(env: FeatureEnv, lv: ListView) {
        if (!wrappedLists.add(lv)) return
        val original = itemClickListener(lv)
        val wrapper = AdapterView.OnItemClickListener { parent, view, position, id ->
            runCatching { original?.onItemClick(parent, view, position, id) }
            runCatching {
                val b = buttonByList[lv] ?: return@runCatching
                updateLabel(env, lv, b)
            }
        }
        runCatching { lv.onItemClickListener = wrapper }
            .onFailure { GLog.w("全选：包裹行点击监听失败", it) }
    }

    /** 读宿主原本挂在 ListView 上的 `OnItemClickListener`（`AdapterView.getOnItemClickListener` 是隐藏 API） */
    private fun itemClickListener(lv: ListView): AdapterView.OnItemClickListener? =
        runCatching {
            val f = AdapterView::class.java.getDeclaredField("mOnItemClickListener")
            f.isAccessible = true
            f.get(lv) as? AdapterView.OnItemClickListener
        }.getOrNull()

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        // 关掉功能时，把所有已注入的按钮撤掉
        if (cfg.stgSelectAll) return
        val anchors = synchronized(injected) { injected.keys.toList() }
        for (a in anchors) removeInjected(a)
    }

    // ------------------------------------------------------------ 样式克隆

    /** 把源按钮的可见样式逐项复制到目标按钮上，确保「样式一致」 */
    private fun copyStyle(src: TextView, dst: TextView) {
        runCatching {
            dst.setBackground(src.background?.constantState?.newDrawable()?.mutate())
        }
        runCatching { dst.backgroundTintList = src.backgroundTintList }
        runCatching { dst.setTextColor(src.textColors) }
        runCatching { src.typeface?.let { dst.typeface = it } }
        runCatching { dst.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.textSize) }
        runCatching { dst.textScaleX = src.textScaleX }
        runCatching { dst.letterSpacing = src.letterSpacing }
        runCatching {
            dst.setPadding(src.paddingLeft, src.paddingTop, src.paddingRight, src.paddingBottom)
        }
        runCatching {
            val d = src.compoundDrawables
            dst.setCompoundDrawables(d.getOrNull(0), d.getOrNull(1), d.getOrNull(2), d.getOrNull(3))
            dst.compoundDrawablePadding = src.compoundDrawablePadding
        }
        runCatching { dst.gravity = src.gravity }
        runCatching { dst.includeFontPadding = src.includeFontPadding }
        runCatching { dst.ellipsize = src.ellipsize }
        runCatching { dst.maxLines = src.maxLines }
        runCatching { dst.maxWidth = src.maxWidth }
        runCatching { dst.minWidth = src.minWidth }
        runCatching { dst.minHeight = src.minHeight }
        runCatching { dst.foreground = src.foreground?.constantState?.newDrawable()?.mutate() }
        runCatching { dst.setAllCaps(false) }
    }

    /**
     * 复制「取消」按钮的 LayoutParams（含 margin / weight / gravity），
     * 并**只在复制品自己的 rightMargin 上**加上与「取消」的间距 ——
     * 不改「取消」任何属性，保证「与取消样式一致」这条验收依然成立。
     */
    private fun buildLayoutParams(src: View): ViewGroup.LayoutParams {
        val lp = src.layoutParams
        val copy: ViewGroup.LayoutParams = when (lp) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp)
            is ViewGroup.LayoutParams -> ViewGroup.LayoutParams(lp)
            else -> ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        if (copy is ViewGroup.MarginLayoutParams) {
            val density = src.context.resources.displayMetrics.density
            copy.rightMargin = (GAP_DP * density + 0.5f).toInt()
        }
        return copy
    }

    // ------------------------------------------------------------ 反射与查找

    /** 取出真正的业务适配器（ListView 带 header 时外面套了一层 HeaderViewListAdapter） */
    private fun unwrapHeaderAdapter(a: Any?): android.widget.ListAdapter? {
        if (a == null) return null
        if (a.javaClass.name == "android.widget.HeaderViewListAdapter") {
            return runCatching {
                a.javaClass.getMethod("getWrappedAdapter").invoke(a)
            }.getOrNull() as? android.widget.ListAdapter
        }
        return a as? android.widget.ListAdapter
    }

    private fun findListView(v: View): ListView? {
        val root = v.rootView ?: return null
        val id = listViewId(v)
        if (id == 0) return null
        return root.findViewById<View>(id) as? ListView
    }

    private fun cancelButtonId(activity: Activity): Int {
        var id = cancelIdCache
        if (id != 0) return id
        for (name in StgSelectAllTargets.CANCEL_BUTTON_ID_CANDIDATES) {
            val r = resolveId(activity.resources, name)
            if (r != 0) {
                id = r
                break
            }
        }
        if (id != 0) cancelIdCache = id
        return id
    }

    private fun listViewId(v: View): Int {
        var id = listIdCache
        if (id != 0) return id
        for (name in StgSelectAllTargets.LIST_ID_CANDIDATES) {
            val r = resolveId(v.resources, name)
            if (r != 0) {
                id = r
                break
            }
        }
        if (id != 0) listIdCache = id
        return id
    }

    private fun checkboxId(v: View): Int {
        var id = checkboxIdCache
        if (id != 0) return id
        for (name in StgSelectAllTargets.CHECKBOX_ID_CANDIDATES) {
            val r = resolveId(v.resources, name)
            if (r != 0) {
                id = r
                break
            }
        }
        if (id != 0) checkboxIdCache = id
        return id
    }

    private fun countId(v: View): Int {
        var id = countIdCache
        if (id != 0) return id
        for (name in StgSelectAllTargets.COUNT_ID_CANDIDATES) {
            val r = resolveId(v.resources, name)
            if (r != 0) {
                id = r
                break
            }
        }
        if (id != 0) countIdCache = id
        return id
    }

    private fun resolveId(res: android.content.res.Resources, name: String): Int =
        runCatching { res.getIdentifier(name, "id", StgSelectAllTargets.TARGET_PKG) }
            .getOrDefault(0)

    private companion object {
        /** 注入按钮与「取消」按钮之间的间距（dp） */
        const val GAP_DP = 16f

        /**
         * 逐行触发勾选时，每一行之间间隔的毫秒数。
         * 给宿主列表一帧时间完成刷新，避免同一帧里拿到失效的子 View。
         */
        const val TOGGLE_STEP_MS = 16L
    }
}
