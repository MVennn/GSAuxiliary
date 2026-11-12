package io.github.MVennn.GSAuxiliary.feature.stgmerge

import android.app.Activity
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 聚合页里「宿主自己那份股票列表」的**临时填充**。
 *
 * ## 为什么需要
 *
 * 真机证据（2026-11-12 22:19:36，宿主 8.7.1，聚合 630 只 / 筛后 285 只）：
 *
 * ```
 * 筛选股票：行点击 position=1 header=1 subset=0 mapped=1 adapter=AIStkAdapter
 * 筛选股票：转发行点击失败
 * java.lang.IndexOutOfBoundsException: Index: 0, Size: 0
 *   at java.util.ArrayList.get(ArrayList.java:437)
 *   at com.guosen.quotation.ui.aistk.AIStkStgDetailAct.onItemClick(AIStkStgDetailAct.java:196)
 * ```
 *
 * 宿主 `onItemClick` 是拿**它自己那份全量列表**（`get(position - header)`）取值，
 * 不是 `adapter.data`。单策略时两份是同一份，看不出差别；聚合时我们只写
 * `adapter.data`（并集）、那份列表被宿主的重取流程清成 0 只，于是**每一下点击**
 * 都在第 196 行越界，异常被行点击包装器吞掉 —— 表现就是「点了没反应」。
 *
 * ## 做法
 *
 * 点下去的那一刻，把 Activity 与其 model 上那些「装的是同一种股票」的 List 字段
 * **临时**换成并集副本，宿主 `onItemClick` 读完立刻换回去。
 * 全程不发通知、不触发 layout，可视状态零变化；只多一次字段读写。
 *
 * 不硬编码字段名：宿主改版后字段改名/换位置也能覆盖到 ——
 * 判据是「List 且元素类型与并集一致」（[holdsSameType]），正是那份取不到值的列表；
 * 同页那些 `List<String>` / `List<BroadcastReceiver>` 一律不碰。
 */
internal object MergeClickFill {

    private const val LOG = "策略聚合："

    /** 一次填充的撤销凭据 */
    class Restore internal constructor(
        private val items: List<Fill>,
    ) {
        fun undo() {
            for (it in items) {
                // 只还原「字段里还是我们塞进去的那份」的情况。
                // 宿主 onItemClick 自己也会重写这个字段（点进详情 → 触发一轮重取 → 换新列表），
                // 那时候无条件写回旧引用，等于把宿主刚设好的新数据丢掉，下一次点击或返回就错乱。
                val cur = runCatching { it.field.get(it.holder) }.getOrNull()
                if (cur !== it.filled) continue
                runCatching { it.field.set(it.holder, it.old) }
            }
        }
    }

    /** 一次填充：换到哪个对象上、哪个字段、原值是什么、我们塞进去的那一份 */
    internal class Fill(val holder: Any, val field: Field, val old: Any?, val filled: Any)

    /** 上一次记过日志的字段组合 —— 同组合只记一次（每次点击都刷会淹掉别的日志） */
    private var noted = ""

    /** 「显示列表与并集不一致」这条日志的专用标记，避免和字段组合共用去重槽 */
    private const val MISMATCH = "\u0000mismatch"

    /**
     * 把 [merged] 临时灌进 [act]（及其 model）上所有「装同种元素」的 List 字段。
     *
     * @return 撤销凭据；没有可灌的字段时返回 null（宿主本来就取得到值，不用管）
     */
    fun fill(act: Activity, merged: List<Any?>, visible: List<*>? = null): Restore? {
        if (merged.isEmpty()) return null
        // 屏幕上显示的不是这份并集时一律不补（宿主刚把列表换回单策略、筛选正在改口径）。
        // 补上会让「用户看到的行号」和「我们灌进去的列表」错位 —— 点 A 股票进 B 的详情，
        // 比「点了没反应」更坏。读不到 data（visible == null）时才按原样补。
        if (visible != null && !sameContents(visible, merged)) {
            if (noted != MISMATCH) {
                noted = MISMATCH
                GLog.i("$LOG 行点击跳过补值：屏幕列表 " + visible.size + " 只与并集 " + merged.size + " 只不一致")
            }
            return null
        }
        val holders = ArrayList<Any>(4)
        for (name in StrategyMergeTargets.ACT_FIELD_MODEL) {
            val m = Reflect.readField(act, name) ?: continue
            if (m !== act) holders.add(m)
        }
        holders.add(act)

        val sample = merged.firstOrNull { it != null }?.javaClass
        val done = ArrayList<Fill>(4)
        val names = StringBuilder()
        for (holder in holders) {
            for (f in listFields(holder.javaClass)) {
                val cur = runCatching { f.get(holder) }.getOrNull() as? List<*> ?: continue
                // 只补「装得下并集那些股票」的列表：宿主上还有一堆 String / Integer /
                // BroadcastReceiver 的 List（checkCodes / checkPrices / m_bcList…），
                // 灌错了会在点击那一瞬把别处的数据搞坏。
                if (!holdsSameType(cur, f, sample)) continue
                // 这里**确实要补** model.m_stkList：宿主 onItemClick（AIStkStgDetailAct.java:196）
                // 拿 adapter.getCount() 做范围校验、却用它自己那份 m_stkList 取值，聚合后那份被清空
                // 才导致每一下点击都越界（docs/FEATURE-STRATEGY-MERGE.md:241-243）。
                // 它只在这一下同步调用期间存在，undo 立刻还回去；宿主空态适配器渲染须等消息轮回合，
                // 这里插不进去 —— 上面 visible 与并集一致那条判据已经挡掉了「界面不是并集」的时刻。
                val copy = ArrayList<Any?>(merged.size)
                copy.addAll(merged)
                if (!runCatching { f.set(holder, copy) }.isSuccess) continue
                done.add(Fill(holder, f, cur, copy))
                if (names.isNotEmpty()) names.append(" / ")
                names.append(holder.javaClass.simpleName).append('.').append(f.name)
                names.append("(").append(cur.size).append("→").append(copy.size).append(")")
            }
        }
        if (done.isEmpty()) return null
        val signature = names.toString()
        if (signature != noted) {
            noted = signature
            GLog.i("$LOG 行点击前临时补上宿主取值列表：$signature")
        }
        return Restore(done)
    }

    /**
     * 这个字段装的是不是「和并集同一种东西」。
     *
     * 两条判据，能中就够：
     * 1. 列表非空 —— 首元素与并集元素同类/父子类；
     * 2. 列表为空 —— 泛型实参里出现并集元素的类名（混淆不改泛型实参，宿主字段就是
     *    `List<StockInfo> m_stkList`，所以这条能把 `List<String> checkCodes`、
     *    `List<BroadcastReceiver> m_bcList` 全都挡在外面）。
     */
    private fun holdsSameType(cur: List<*>, f: Field, sample: Class<*>?): Boolean {
        if (sample == null) return false
        val first = cur.firstOrNull { it != null }?.javaClass
        if (first != null) return first.isAssignableFrom(sample) || sample.isAssignableFrom(first)
        // 空列表只能靠泛型实参判：直接比 Class，不做字符串包含 ——
        // `List<String>`、`List<BroadcastReceiver>` 的 simpleName 是常见短词，
        // contains 会把 `Data`、`Info`、混淆后的单字母类名误判成同类，灌进去就改了别处的数据。
        val arg = runCatching {
            (f.genericType as? java.lang.reflect.ParameterizedType)?.actualTypeArguments?.getOrNull(0)
        }.getOrNull() as? Class<*> ?: return false
        return arg.isAssignableFrom(sample) || sample.isAssignableFrom(arg)
    }

    /** 两份列表是不是同一批条目（长度相同且逐项同一实例） */
    private fun sameContents(a: List<*>, b: List<*>): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }

    /** 沿继承链收集「可写的 List 类型实例字段」（静态 / final 跳过） */
    private fun listFields(cls: Class<*>): List<Field> {
        val out = ArrayList<Field>(4)
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && c != Any::class.java && depth < 8) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (Modifier.isFinal(f.modifiers)) continue
                if (!List::class.java.isAssignableFrom(f.type)) continue
                runCatching { f.isAccessible = true }
                out.add(f)
            }
            c = c.superclass
            depth++
        }
        return out
    }
}
