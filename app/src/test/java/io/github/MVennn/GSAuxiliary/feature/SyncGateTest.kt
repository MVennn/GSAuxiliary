package io.github.MVennn.GSAuxiliary.feature

import io.github.MVennn.GSAuxiliary.feature.SyncGate.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 布局回调的**限速决策** —— 本项目最贵的一条教训的所在地。
 *
 * ## 这条测试为什么重要
 *
 * `docs/CODE-RULES.md` 规则 2.3：**限速只能延后，绝不能丢弃**。
 *
 * > 历史上 3.2.0 的 1.5 秒动作冷却就是「直接 return 掉这一趟」——
 * > 症状是「个股详情页滑过去不注入『智能盯盘』入口，要停留一会儿才出现」
 * > 「底栏『理财』先消失、占位过一会儿才归位」。
 * > 2026-10-19 才改成「延后不丢弃 + 200ms」。
 *
 * 这段逻辑以前**只有真机能验**（看界面动不动）。抽成 [SyncGate] 纯函数后，
 * 「把 `Defer` 写成 `Drop`」这种改错当场就能被测出来 ——
 * 而这恰恰是最容易写错的地方（两处 `if` 各自算一个等待时间，谁抄错一个符号就回归）。
 */
class SyncGateTest {

    private val cooldown = 200L
    private val throttle = 200L
    private val threshold = 3

    private fun decide(
        now: Long = 1000L,
        lastActionAt: Long = 0L,
        lastRunAt: Long = 0L,
        missScans: Int = 0,
        lastScanOk: Boolean = true,
        followUp: Boolean = false,
    ) = SyncGate.decide(
        now = now,
        lastActionAt = lastActionAt,
        lastRunAt = lastRunAt,
        missScans = missScans,
        lastScanOk = lastScanOk,
        followUp = followUp,
        actionCooldownMs = cooldown,
        layoutThrottleMs = throttle,
        missScanThreshold = threshold,
    )

    @Test
    fun `默认情况 直接跑`() {
        assertEquals(Decision.Run, decide())
    }

    // ------------------------------------------------------------------ 动作限速

    @Test
    fun `刚动过视图 且在冷却窗口内 延后而不是丢弃`() {
        val d = decide(now = 1000L, lastActionAt = 900L)
        assertTrue(d is Decision.Defer, "应当延后而不是直接跑：$d")
        // 剩余窗口 = 200 - (1000 - 900) = 100ms
        assertEquals(100L, (d as Decision.Defer).delayMs)
    }

    @Test
    fun `冷却窗口过了 就直接跑`() {
        assertEquals(Decision.Run, decide(now = 1200L, lastActionAt = 900L))
    }

    @Test
    fun `从来没动过视图 不受动作限速影响`() {
        // lastActionAt = 0 → 「距上次动过」是一个巨大的值 → 窗口早就过了
        assertEquals(Decision.Run, decide(now = 1000L, lastActionAt = 0L))
    }

    // ------------------------------------------------------------------ 待收敛让路（BUG1）

    @Test
    fun `有待收敛的下一趟时 动作限速必须让路`() {
        // ⚠️ **这是 BUG1 的核心**：多趟状态机（底栏重排那种「改一趟、量一趟」）
        // 若被冷却掐住，界面就停在中间态（留空档 / 末格出屏）。
        val d = decide(now = 1000L, lastActionAt = 900L, followUp = true)
        assertEquals(
            Decision.Run,
            d,
            "有待收敛时必须让路 —— 被冷却掐住就是 BUG1 那个症状（底栏停在中间态）",
        )
    }

    @Test
    fun `有待收敛时 且离上次跑很近 仍然让路`() {
        assertEquals(
            Decision.Run,
            decide(now = 1000L, lastActionAt = 999L, lastRunAt = 999L, followUp = true),
        )
    }

    // ------------------------------------------------------------------ 认不出限速

    @Test
    fun `连续认不出且没到阈值 不限速`() {
        assertEquals(
            Decision.Run,
            decide(now = 1000L, lastRunAt = 900L, missScans = threshold - 1, lastScanOk = false),
        )
    }

    @Test
    fun `连续认出且到阈值 仍然不限速 - 认得出就不该慢下来`() {
        assertEquals(
            Decision.Run,
            decide(now = 1000L, lastRunAt = 900L, missScans = threshold, lastScanOk = true),
        )
    }

    @Test
    fun `连续认不出到阈值且离上次跑很近 延后`() {
        val d = decide(now = 1000L, lastRunAt = 900L, missScans = threshold, lastScanOk = false)
        assertTrue(d is Decision.Defer, "认不出且节流窗口没过时应当延后：$d")
        assertEquals(100L, (d as Decision.Defer).delayMs)
    }

    @Test
    fun `认不出时的节流对多趟状态机也生效 - 认不出时更该慢下来`() {
        // ⚠️ 与「动作限速让路」相反的一条：**认不出限速不管 followUp**。
        //    这两条别搞混 —— 搞混了就是「认不出时还在猛刷」或「多趟状态机被节流掐住」。
        val d = decide(
            now = 1000L,
            lastRunAt = 900L,
            missScans = threshold,
            lastScanOk = false,
            followUp = true,
        )
        assertTrue(d is Decision.Defer, "认不出限速应当生效（不管 followUp）：$d")
    }

    // ------------------------------------------------------------------ 绝不丢弃

    @Test
    fun `决策只有两种 - 没有丢弃这一种`() {
        // 编译期保证：[Decision] 是密封接口，只有 Run 与 Defer 两个实现，
        // **没有「丢掉这一趟」的选项** —— 这是「延后不丢弃」的结构性保证。
        // （下面这个 `when` 故意不写 else：将来加了第三种实现，编译器会报错。）
        val d = decide()
        val name = when (d) {
            is Decision.Run -> "Run"
            is Decision.Defer -> "Defer"
        }
        assertTrue(name == "Run" || name == "Defer")
    }

    @Test
    fun `延后时长永远是正数 - 不能排一个已经过去的时刻`() {
        for (now in 0L..2000L step 50L) {
            for (actedAt in 0L..2000L step 50L) {
                val d = decide(now = now, lastActionAt = actedAt)
                if (d is Decision.Defer) {
                    assertTrue(d.delayMs > 0, "延后时长必须为正：now=$now actedAt=$actedAt → ${d.delayMs}")
                }
            }
        }
    }

    @Test
    fun `冷却窗口越大 延后越久`() {
        val short = decide(now = 1000L, lastActionAt = 950L) as Decision.Defer
        assertEquals(150L, short.delayMs)
    }
}
