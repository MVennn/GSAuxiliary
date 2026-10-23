package io.github.MVennn.GSAuxiliary.feature.strategyselect

/**
 * 「全选 / 反选」的方向判定与待点击行 —— 纯逻辑，便于单测。
 *
 * ## 方向判据
 *
 * **只要还有一行没勾上，这次就是「全选」**；全部已勾才是「反选」。
 *
 * 不能用底部「已选 N」当判据（`StgSelectAllFeature#toggleAll` 的注释里有完整原因）：
 *  - 筛选生效时，计数覆盖的是宿主的选中模型（含被筛掉的行），与列表里的行数不是一回事；
 *  - 计数与屏上勾选框可能不同步（合成点击后宿主不重画部分勾选框）。
 *
 * ## 为什么「已符合目标的行」不能再点
 *
 * 行点击是**取反**（`AbsListView.performItemClick` 按 position 取反写 checked states，
 * 宿主的行点击监听也按「当前是否已选」做增删）。对已经勾上的行再点一次 = 取消勾选。
 * 2026-10-21 真机现象「做过一次全选后，下次全选部分选不中」就是这么来的：
 * 上一轮把行勾上了，屏上却没画出来，下一轮读屏上状态以为没勾，又点了一次。
 */
internal object StgSelectAllPlan {

    /** 「这一行的状态从哪来」 */
    enum class StateSource {
        /** 宿主计数为 0：一行都没选中，不必逐行问 */
        NONE_CHECKED,

        /** 框架的 checked states（与「确认添加」读的是同一份表） */
        TABLE,

        /** 逐行现绑探针视图（checked states 不可用或与宿主计数矛盾） */
        PROBE,
    }

    /**
     * 状态来源的优先级。
     *
     * @param hostCount 底部「已选 N」（宿主维护的全量选中数；读不到传 -1）
     * @param tableChecked checked states 里勾着的行数；宿主不用多选模式时传 null
     * @param filtered 当前是否在筛选（有筛选时宿主计数还包含被筛掉的行，不能要求两者相等）
     * @param retry 是不是「补点」轮（首轮之后再核对一遍）。
     *        补点轮以 **checked states 为准**：那张表是我们自己刚预置、刚点过的，
     *        拿它核对才不会把已勾上的行再点一遍；宿主计数在此刻可能还没跟上
     *        （合成点击写得比宿主刷新快），拿它判会把整单选中的行点成未选。
     *        **例外**：没有筛选时，表与宿主计数对不上说明宿主模型和表已经分叉
     *        （真机上「宿主吞掉某次点击」就是这样），这时退回探针按宿主模型补 ——
     *        只信表的话，表里已经被 `performItemClick` 写成目标态，永远发现不了漏选。
     *        没有表（`CHOICE_MODE_NONE`）时返回 PROBE —— 调用方在这种情况下不做补点。
     */
    fun stateSource(
        hostCount: Int,
        tableChecked: Int?,
        filtered: Boolean,
        retry: Boolean = false,
    ): StateSource = when {
        // 计数为 0 优先于 checked states：换适配器 / 页面重建后，按 position 存的表
        // 可能留着上一份的残值；而宿主计数是它每次点击都会重写的。若反过来信表，
        // 会把「其实一行没选」判成已选，于是点「全选」变成对已勾行再点一次 = 取消。
        // ⚠️ 只对**首轮**成立：补点轮里计数可能滞后，此时表才是刚写过的现场。
        hostCount == 0 && !retry -> StateSource.NONE_CHECKED
        tableChecked == null -> StateSource.PROBE
        // 没有筛选时两套坐标是同一套：表与计数对不上 = 宿主模型与表分叉，按模型补。
        // 有筛选时计数含被筛掉的行，对不上是常态，不能据此判。
        retry && !filtered && tableChecked != hostCount -> StateSource.PROBE
        retry -> StateSource.TABLE
        filtered || tableChecked == hostCount -> StateSource.TABLE
        else -> StateSource.PROBE
    }

    /** 这次点击要落到哪个状态：还有没勾上的行 → 全部勾上；全勾上了 → 全部取消 */
    fun wantChecked(before: BooleanArray): Boolean = before.any { !it }

    /** 需要点击的行下标（状态与目标不同的行），保序 */
    fun rowsToClick(before: BooleanArray, wantChecked: Boolean): IntArray {
        var n = 0
        for (b in before) if (b != wantChecked) n++
        val out = IntArray(n)
        var k = 0
        for (i in before.indices) if (before[i] != wantChecked) out[k++] = i
        return out
    }

    // ---------------------------------------------------------- 宿主选中模型

    /**
     * 用宿主自己的选中模型（`AIStkStgDetailAct.checkCodes`）算每一行的状态。
     *
     * 为什么需要它（2026-10-23 用户反馈）：合成点击走的是
     * `AbsListView.performItemClick` —— 它在调到宿主监听器之前就按取反把
     * `mCheckStates` 写好了（框架 `AbsListView.java:1231`）。宿主监听器要是静默忽略
     * 某次点击，表里仍是目标态，补点轮永远发现不了「宿主模型里其实没这一行」；
     * 反过来，表被宿主刷新清掉时，表又会把已经勾上的行判成没勾。
     * `checkCodes` 是宿主自己维护的那份 List（底部计数 / 确认添加都以它为准），
     * 用它核对才能看到真实结果。
     *
     * @param rowCodes 每一行的股票代码（读不到的行传 null）
     * @param selected 宿主模型里的代码（可能带市场后缀，如 `600000.1`）
     * @param filtered 当前是否在筛选（筛选时计数含被筛掉的行，不能拿计数判分叉）
     * @param hostCount 底部「已选 N」（读不到传 -1）
     * @return 每行是否选中；**任何一行代码读不到就返回 null** —— 调用方必须退回
     *         checked states / 探针，绝不能把「读不到」当成「没选」。
     *         没有筛选时「计数 > 0 但模型空表 / 一只都对不上」也返回 null：
     *         那说明两份数据已经分叉，拿模型判会把「已勾」当「没勾」→ 反向点击。
     */
    fun statesFromCodes(
        rowCodes: List<String?>,
        selected: Collection<String>,
        filtered: Boolean = false,
        hostCount: Int = -1,
    ): BooleanArray? {
        if (rowCodes.isEmpty()) return BooleanArray(0)
        if (rowCodes.any { it == null }) return null
        val set = selected.mapTo(HashSet()) { normalizeCode(it) }
        val out = BooleanArray(rowCodes.size) { set.contains(normalizeCode(rowCodes[it]!!)) }
        if (!filtered && hostCount > 0 && (selected.isEmpty() || out.none { it })) return null
        return out
    }

    /** 代码归一化：去掉市场后缀（`600000.1` → `600000`）与空白 */
    fun normalizeCode(code: String): String = code.substringBefore('.').trim()

    /** 是不是「股票代码」形态（A 股 6 位 / 港股 5 位）；用来识别宿主模型结构没变 */
    fun isCodeLike(code: String): Boolean = CODE_RE.matches(normalizeCode(code))

    /**
     * 重复代码（按第一次重复出现的顺序）。
     *
     * 宿主模型是 `List`，行点击处理若按视图状态决定「加/删」，一次方向判错就会把
     * 同一只股票 add 两次 —— 底部计数 +1、但并没有多选出一只。这里只用于日志，
     * 不直接改宿主 List（改完计数控件不会同步，反而更难收拾）。
     */
    fun duplicateCodes(codes: List<String>): List<String> {
        val seen = HashSet<String>()
        val dup = LinkedHashSet<String>()
        for (c in codes) {
            val n = normalizeCode(c)
            if (!seen.add(n)) dup.add(n)
        }
        return dup.toList()
    }

    private val CODE_RE = Regex("\\d{5,6}")
}
