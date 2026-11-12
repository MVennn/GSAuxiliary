package io.github.MVennn.GSAuxiliary.app

import io.github.MVennn.GSAuxiliary.core.StgCatalogBroadcast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「从金太阳导入」的清单解析。
 *
 * 这个解析函数是宿主与模块 App 两个进程之间唯一的约定：宿主 [StgCatalogBroadcast] 组包、
 * 模块 [HostStgCatalog] 解包。两边写岔（键名、版本号、id/name 必填）时真机表现是
 * 「从金太阳导入」永远显示空清单，日志里只有一句「载荷为空或解析不出条目」——
 * 所以坏载荷、版本不符、缺字段这几种情况必须在这里钉住。
 */
class HostStgCatalogTest {

    @Test
    fun 正常载荷解析出条目与时间() {
        val raw = """{"v":1,"at":1762900000000,"items":[{"id":"1001","name":"量50","tags":["流通市值50-70亿","量比1.5-2.0"]},{"id":"1002","name":"量100","tags":["流通市值100-500亿"]}]}"""
        val s = HostStgCatalog.parse(raw)
        assertEquals(1762900000000L, s.at)
        assertEquals(2, s.items.size)
        assertEquals("1001", s.items[0].stId)
        assertEquals("量50", s.items[0].name)
        assertEquals(listOf("流通市值50-70亿", "量比1.5-2.0"), s.items[0].tags)
    }

    @Test
    fun 版本号不符整份丢弃() {
        val raw = """{"v":2,"at":1,"items":[{"id":"1","name":"A","tags":[]}]}"""
        assertEquals(HostStgCatalog.Snapshot.EMPTY, HostStgCatalog.parse(raw))
    }

    @Test
    fun 空串与坏JSON都当空快照() {
        assertEquals(HostStgCatalog.Snapshot.EMPTY, HostStgCatalog.parse(null))
        assertEquals(HostStgCatalog.Snapshot.EMPTY, HostStgCatalog.parse(""))
        assertEquals(HostStgCatalog.Snapshot.EMPTY, HostStgCatalog.parse("{oops"))
    }

    @Test
    fun 有版本没items时保留时间但零条目() {
        val s = HostStgCatalog.parse("""{"v":1,"at":123}""")
        assertEquals(123L, s.at)
        assertTrue(s.items.isEmpty())
    }

    @Test
    fun id或name缺一半的条目丢弃() {
        val raw = """{"v":1,"at":1,"items":[{"id":"","name":"无名号"},{"id":"1003","name":""},{"id":"1004","name":"留着的"}]}"""
        val s = HostStgCatalog.parse(raw)
        assertEquals(1, s.items.size)
        assertEquals("1004", s.items[0].stId)
    }

    @Test
    fun tags省略或空值不影响其它字段() {
        val raw = """{"v":1,"at":1,"items":[{"id":"1","name":"无标签"},{"id":"2","name":"带空","tags":["", " "],"none":1}]}"""
        val s = HostStgCatalog.parse(raw)
        assertEquals(2, s.items.size)
        assertTrue(s.items[0].tags.isEmpty())
        assertTrue(s.items[1].tags.isEmpty(), "空白标签要丢掉，界面上不能出现空的分隔点")
    }

    @Test
    fun 名称与编号两端空白被裁掉() {
        val raw = """{"v":1,"at":1,"items":[{"id":" 7 ","name":" 量70 ","tags":[" 量比 " ]}]}"""
        val s = HostStgCatalog.parse(raw)
        assertEquals("7", s.items[0].stId)
        assertEquals("量70", s.items[0].name)
        assertEquals(listOf("量比"), s.items[0].tags)
    }

    @Test
    fun 版本常量与宿主组包一致() {
        assertEquals(1, StgCatalogBroadcast.VERSION)
    }
}