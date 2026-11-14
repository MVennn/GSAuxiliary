package io.github.MVennn.GSAuxiliary.core.zxcopy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 宿主 tab 文案 ↔ 绑定键：认不出的必须返回 null，不能猜 */
class ZxCopyGroupsTest {

    @Test
    fun `三个 tab 文案认得出`() {
        assertEquals(ZxCopyGroups.ALL, ZxCopyGroups.keyOfHostTitle("全部"))
        assertEquals(ZxCopyGroups.SELF, ZxCopyGroups.keyOfHostTitle("自选"))
        assertEquals(ZxCopyGroups.HOLDING, ZxCopyGroups.keyOfHostTitle("持仓股"))
    }

    @Test
    fun `首尾空白不影响`() {
        assertEquals(ZxCopyGroups.SELF, ZxCopyGroups.keyOfHostTitle(" 自选 "))
    }

    @Test
    fun `认不出的一律 null`() {
        assertNull(ZxCopyGroups.keyOfHostTitle(null))
        assertNull(ZxCopyGroups.keyOfHostTitle(""))
        assertNull(ZxCopyGroups.keyOfHostTitle("   "))
        assertNull(ZxCopyGroups.keyOfHostTitle("我的分组"))
        assertNull(ZxCopyGroups.keyOfHostTitle("自选股"))
    }

    @Test
    fun `normalize 去未知去重并按 tab 顺序`() {
        assertEquals(
            listOf(ZxCopyGroups.ALL, ZxCopyGroups.HOLDING),
            ZxCopyGroups.normalize(
                listOf(ZxCopyGroups.HOLDING, "gone", ZxCopyGroups.ALL, ZxCopyGroups.HOLDING),
            ),
        )
    }

    @Test
    fun `normalize 空集仍是空集`() {
        assertEquals(emptyList(), ZxCopyGroups.normalize(emptyList()))
    }
}
