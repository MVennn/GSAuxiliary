package io.github.MVennn.GSAuxiliary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * 分档配色的**迁移口径**与编解码往返。
 *
 * 钉住五件事：
 *
 *  1. 键没写过（空串 / null）时三档全部回落老键 `color` —— 老用户升级后颜色必须一模一样；
 *  2. 写过之后**只认新键**，老键不再参与（否则改一档、另两档还跟着老色跑）；
 *  3. 坏 JSON / 版本不符 / 认不得的颜色码一律回落老色 + warn，绝不抛异常
 *     （目标进程每行取色都调 decode，抛一次就把绘制链路打断）；
 *  4. `encode` 按涨 → 跌 → 平固定输出，**只写给出的档**（缺的档由读取侧回落）；
 *  5. 六位 `#RRGGBB` 要补成不透明，否则真机上选完颜色是透明的。
 *
 * 断言一律走 [JSONObject] 取字段，不比整串 —— JVM 版 org.json 的键序与 Android 不同。
 */
class WatchlistColorStoreTest {

    private val legacy = 0xFFFF8000.toInt()

    @Test
    fun 空配置三档都回落老颜色() {
        assertEquals(WatchlistColorStore.allOf(legacy), WatchlistColorStore.decode("", legacy))
        assertEquals(WatchlistColorStore.allOf(legacy), WatchlistColorStore.decode(null, legacy))
        assertEquals(WatchlistColorStore.allOf(legacy), WatchlistColorStore.decode("   ", legacy))
    }

    @Test
    fun 写到哪一档只改那一档_其余仍是老颜色() {
        val json = "{\"v\":1,\"up\":\"#FF00FF00\"}"
        val colors = WatchlistColorStore.decode(json, legacy)
        assertEquals(0xFF00FF00.toInt(), colors[WatchlistTier.UP])
        assertEquals(legacy, colors[WatchlistTier.DOWN])
        assertEquals(legacy, colors[WatchlistTier.FLAT])
    }

    @Test
    fun 六位颜色码补成不透明() {
        val json = "{\"v\":1,\"up\":\"#FF8000\",\"down\":\"#1AAE6A\"}"
        val colors = WatchlistColorStore.decode(json, legacy)
        assertEquals(0xFFFF8000.toInt(), colors[WatchlistTier.UP])
        assertEquals(0xFF1AAE6A.toInt(), colors[WatchlistTier.DOWN])
    }

    @Test
    fun 八位颜色码保留原有Alpha() {
        val json = "{\"v\":1,\"flat\":\"#331AAE6A\"}"
        assertEquals(0x331AAE6A, WatchlistColorStore.decode(json, legacy)[WatchlistTier.FLAT])
    }

    @Test
    fun 认不得的颜色码回落老颜色() {
        val json = "{\"v\":1,\"up\":\"not-a-color\",\"down\":\"#12345\"}"
        val colors = WatchlistColorStore.decode(json, legacy)
        assertEquals(legacy, colors[WatchlistTier.UP])
        assertEquals(legacy, colors[WatchlistTier.DOWN])
    }

    @Test
    fun 坏JSON回落老颜色() {
        assertEquals(
            WatchlistColorStore.allOf(legacy),
            WatchlistColorStore.decode("{\"v\":1,\"up\":", legacy),
        )
    }

    @Test
    fun 版本不符回落老颜色() {
        val json = "{\"v\":2,\"up\":\"#FF00FF00\",\"down\":\"#FF0000FF\"}"
        assertEquals(WatchlistColorStore.allOf(legacy), WatchlistColorStore.decode(json, legacy))
    }

    @Test
    fun 缺v字段按当前版本处理() {
        val json = "{\"up\":\"#FF00FF00\"}"
        assertEquals(0xFF00FF00.toInt(), WatchlistColorStore.decode(json, legacy)[WatchlistTier.UP])
    }

    @Test
    fun 三档同色的往返一致() {
        val encoded = WatchlistColorStore.encode(WatchlistColorStore.allOf(legacy))
        assertEquals(WatchlistColorStore.allOf(legacy), WatchlistColorStore.decode(encoded, 0))
    }

    @Test
    fun 三档各不相同的往返一致() {
        val source = mapOf(
            WatchlistTier.UP to 0xFFFF3B30.toInt(),
            WatchlistTier.DOWN to 0xFF1AAE6A.toInt(),
            WatchlistTier.FLAT to 0xFF3482FF.toInt(),
        )
        val encoded = WatchlistColorStore.encode(source)
        assertEquals(source, WatchlistColorStore.decode(encoded, legacy))
        assertEquals(3, WatchlistColorStore.distinctCount(encoded, legacy))
    }

    @Test
    fun encode按涨跌平顺序只写给出的档() {
        val obj = JSONObject(WatchlistColorStore.encode(mapOf(WatchlistTier.DOWN to 0xFF1AAE6A.toInt())))
        assertEquals(1, obj.getInt("v"))
        assertTrue(obj.has("down"))
        assertTrue(!obj.has("up"), "没给的档不该写进 JSON：$obj")
        assertTrue(!obj.has("flat"), "没给的档不该写进 JSON：$obj")
    }

    @Test
    fun 识别几种不同颜色() {
        assertEquals(1, WatchlistColorStore.distinctCount("", legacy))
        assertEquals(2, WatchlistColorStore.distinctCount(
            "{\"v\":1,\"up\":\"#FFFF8000\",\"down\":\"#FF1AAE6A\"}", legacy,
        ))
        assertEquals(3, WatchlistColorStore.distinctCount(
            "{\"v\":1,\"up\":\"#FFFF8000\",\"down\":\"#FF1AAE6A\",\"flat\":\"#FF3482FF\"}",
            legacy,
        ))
    }

    @Test
    fun 取单档颜色_认不得的档回落老颜色() {
        val json = "{\"v\":1,\"down\":\"#FF1AAE6A\"}"
        assertEquals(0xFF1AAE6A.toInt(), WatchlistColorStore.colorFor(json, legacy, WatchlistTier.DOWN))
        assertEquals(legacy, WatchlistColorStore.colorFor(json, legacy, "not_a_tier"))
    }

    @Test
    fun 出厂默认串解码为三档老颜色() {
        assertEquals(
            WatchlistColorStore.allOf(legacy),
            WatchlistColorStore.decode(WatchlistColorStore.defaultJson(legacy), legacy),
        )
    }
}
