package io.github.MVennn.GSAuxiliary.feature.stgmerge

import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HostSession
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 按「标签组合」向宿主服务端现取成分股。
 *
 * ## 为什么不能用宿主策略
 *
 * 宿主账号**最多 10 条策略**（`createStrategy` 返回 `code 4100「策略数不能超过10个」`，
 * 与名字无关，纯字母名照样被拒）。而用户要的口径拆成「市值档 × 量比档」是
 * 3×4 + 3 + 4 + 1 = 20 个组合 —— 装不下。宿主标签页的同一标签多档位是 **AND(交集)**，
 * 也没法用一个策略表达「50-70亿 OR 70-100亿」。
 *
 * 所以这里走「临时建一条 → 取数 → 立刻删掉」的轮转：任何时刻只占 1 个名额，
 * 只要账号还有 1 个空位就能跑完任意多个组合。
 *
 * ## 依赖
 *
 *  - [HostSession]：session / userCode / 签名 / 公共参数块，由网络探针从宿主
 *    自己的请求体里截获。没截到时 [HostSession.isReady] 为 false，本类直接返回空。
 *  - 服务端**不校验签名与参数绑定**（实测复用旧 `openapi_sign` 发全新参数照样 code=0），
 *    所以不需要复现签名算法。
 *
 * ## 时序约束
 *
 * 含量比条件的组合**只在交易时段有数据**：量比是实时字段，收盘后服务端返回 0 只。
 * 拿到空列表不等于失败，调用方要按「这轮没数据」处理，不要误清空已有聚合结果。
 */
object StrategyComboClient {

    /** 统一网关：与 `210.22.5.146:1445` 同源，用域名是为了让证书校验能过 */
    private const val BASE = "https://goldsunft.guosen.com.cn:1445"

    private const val PATH_CREATE = "/gsnews/strategy/createStrategy/1.0"
    private const val PATH_QUERY = "/gsnews/strategy/queryStrategyQuoteDetail/1.0"
    private const val PATH_DELETE = "/gsnews/strategy/deleteStrategy/1.0"
    private const val PATH_UPDATE = "/gsnews/strategy/updateStrategy/1.0"
    private const val PATH_LIST = "/gsnews/strategy/queryStrategy/1.0"

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 15000

    /** 一条组合：展示名 + 两个标签 id（市值档、量比档，均可再加第三个） */
    data class Combo(val key: String, val labelIds: List<String>) {
        val bizLabelId: String get() = labelIds.joinToString(",")
    }

    /** 服务端返回的一只成分股（字段是原样字符串，不做数值转换） */
    data class Stock(
        val code: String,
        val name: String,
        /** 涨跌幅，形如 `11.02`（百分数，无 % 号） */
        val zdf: String,
        /** 最新价 */
        val price: String,
    )

    /**
     * 临时策略名字前缀。
     *
     * 名额满（code 4100）时要认出「哪些是自己上一轮漏删的」并清掉，只能靠名字：
     * 用户自己的策略叫「量50」「50e」这类，不会撞这个前缀。
     */
    const val TEMP_PREFIX = "GSZtmp"

    private val seq = AtomicLong(0L)

    /**
     * 本进程**正在用**的临时策略 id。
     *
     * 名额满时的补救是「把自己漏删的临时策略全清掉再重试」，但两个详情页同时聚合时，
     * A 清遗留会把 B 手上那条正在取数的临时策略删掉 —— B 后面「换标签 → 取数」就查不到数据，
     * 表现为「聚合突然少了一大块」。所以清遗留在 [purgeLeftovers] 里显式跳过这些 id。
     */
    private val liveIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 本进程是否已经扫过一遍遗留临时策略。
     *
     * 以前只在「建临时策略撞上名额满（code 4100）」时才清（见 [createOrFreeSlot]），
     * 于是进程被杀 / 用户直接退出时留下的 `GSZtmpXXXXXX` 会一直挂在「我的策略」里，
     * 名字还是一串英文，用户只能自己去删。现在第一次聚合前顺手扫一遍。
     */
    @Volatile
    private var scannedLeftovers = false

    /**
     * 每进程最多扫一次的遗留临时策略清理（[purgeLeftovers] 的薄包装）。
     *
     * **必须在后台线程调用**（内部要发一次列表请求）。清失败只记日志：
     * 清不掉最多是列表里多一条，不影响这一轮取数。
     */
    fun purgeLeftoversOnce() {
        if (scannedLeftovers) return
        scannedLeftovers = true
        val n = runCatching { purgeLeftovers() }.getOrElse {
            GLog.w("组合取数：扫遗留临时策略失败（不影响取数）", it)
            0
        }
        if (n > 0) GLog.i("组合取数：本轮扫掉 " + n + " 条遗留临时策略")
    }

    /** 结果容器：成功 / 失败原因 / 拿到的股票 */
    data class Result(val stocks: List<Stock>, val error: String) {
        val ok: Boolean get() = error.isEmpty()
    }

    /**
     * 跑一条组合：建 → 取 → 删。**必须在后台线程调用**（内部会阻塞网络）。
     *
     * @return 股票列表；任何一步失败都返回 [Result.ok] = false，且不留残策略
     */
    fun fetchOne(combo: Combo): Result {
        if (!HostSession.isReady()) return Result(emptyList(), "还没有截到宿主会话（先在宿主里点一次「我的策略」，让它发一次请求）")
        val stId = try {
            createTemp(combo).also { liveIds.add(it) }
        } catch (e: Exception) {
            return Result(emptyList(), "建临时策略失败：" + msgOf(e))
        }
        try {
            return Result(query(stId), "")
        } catch (e: Exception) {
            return Result(emptyList(), "取成分股失败：" + msgOf(e))
        } finally {
            // 无论成败都要删掉：名额只剩 1 个的时代，漏删一次就把后续全堵死
            dropTemp(stId)
        }
    }

    /**
     * 建一条**临时策略**并返回它的 stId，调用方用完必须 [dropTemp]。
     *
     * 这条路径给聚合用：把返回的 stId 写进宿主的 `m_stg.id` 再发取数命令，
     * 宿主就会按它自己的逻辑取这一组合的成分股 —— 拿到的每个元素都是宿主自己的
     * `StockInfo` 实例（去重、价位、点击全靠这个身份，自建包装类会全部失效）。
     *
     * 名额很紧：宿主账号上限 10 条，用户已占 8 条，任何时刻只能多挂 2 条。
     * 所以必须**取一个删一个**，不能先把 12 条都建出来。
     */
    fun createTemp(combo: Combo): String {
        if (!HostSession.isReady()) {
            throw IOException("还没有截到宿主会话（先在宿主里点一次「我的策略」，让它发一次请求）")
        }
        // 名字只当去重键用，11 字符上限也不管：接口侧没有长度校验，实测纯 ASCII 短名全部通过
        val tmpName = TEMP_PREFIX + System.currentTimeMillis().toString().takeLast(6)
        return createOrFreeSlot(tmpName, combo.bizLabelId)
    }

    /** 删掉临时策略（吞掉异常：删不掉只是少一个名额，不该打断聚合） */
    fun dropTemp(stId: String) {
        liveIds.remove(stId)
        if (!runCatching { delete(stId) }.getOrDefault(false)) {
            GLog.w("组合取数：删临时策略没删掉 " + stId + "（下次聚合或名额满时会再清）")
        }
    }

    /** 串行跑完一批组合，顺序与入参一致 */
    fun fetchAll(combos: List<Combo>, onEach: ((Combo, Result) -> Unit)? = null): Map<String, Result> {
        val out = LinkedHashMap<String, Result>()
        for (c in combos) {
            val r = fetchOne(c)
            out[c.key] = r
            onEach?.invoke(c, r)
        }
        return out
    }

    // ---- 三个接口 ----

    /** 建临时策略，返回新 stId */
    private fun create(name: String, labelIds: String): String {
        val text = post(PATH_CREATE, "stName=" + enc(name) + "&labelId=" + enc(labelIds))
        val root = JSONObject(text)
        ensureOk(root, "建策略")
        val data = root.optJSONArray("data")
        val first = data?.optJSONObject(0)
        val direct = first?.optString("stId").orEmpty()
        if (direct.isNotEmpty()) return direct
        // 兜底：data[0].member 是「全部策略」的 JSON 字符串，按名字找刚建那条
        val member = first?.optString("member").orEmpty()
        if (member.isNotEmpty()) {
            val arr = org.json.JSONArray(member)
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (e.optString("stName") == name) {
                    val id = e.optString("stId")
                    if (id.isNotEmpty()) return id
                }
            }
        }
        throw IOException("建策略成功但没拿到 stId：" + text.take(300))
    }

    /** 取一条策略的全部成分股（该接口不分页，一次返回全量） */
    private fun query(stId: String): List<Stock> {
        val text = post(PATH_QUERY, "stId=" + enc(stId))
        val root = JSONObject(text)
        ensureOk(root, "取成分股")
        val arr = root.optJSONArray("stocks") ?: return emptyList()
        val out = ArrayList<Stock>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val code = e.optString("code")
            if (code.isEmpty()) continue
            out.add(Stock(code, e.optString("name"), e.optString("zdf"), e.optString("zjcj")))
        }
        return out
    }

    /**
     * 删一条策略。
     *
     * 返回值必须当回事：服务端偶尔回 `code != "0"`（旧版本这里用 `runCatching` 把拒绝吞掉，
     * 于是 `purgeLeftovers` 把「没删掉」也记成「清掉了」，列表里那条临时策略一直挂着）。
     *
     * @return true = 服务端确认删除
     */
    private fun delete(stId: String): Boolean {
        val text = post(PATH_DELETE, "stId=" + enc(stId))
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root == null) {
            GLog.w("组合取数：删临时策略响应不是 JSON：" + text.take(200))
            return false
        }
        return try {
            // 业务码在 result[0].code 里，不是顶层；判定统一交给 [ensureOk]，别自己读字段
            ensureOk(root, "删临时策略")
            true
        } catch (e: Exception) {
            GLog.w("组合取数：删临时策略没删掉：" + msgOf(e) + "；响应 " + text.take(200))
            false
        }
    }

    /**
     * 把一条已有策略的标签整体换成 [labelIds]（等价于宿主「修改策略标签」点「完成」）。
     *
     * 名字不动：临时策略名字保持恒定，宿主列表里那张卡不会跟着每格换名字跳来跳去。
     */
    fun update(stId: String, labelIds: String) {
        val text = post(PATH_UPDATE, "stId=" + enc(stId) + "&labelId=" + enc(labelIds))
        ensureOk(JSONObject(text), "改策略标签")
    }

    /** 账号下的一条策略（列表接口只给 stId / 名字 / 标签） */
    data class StrategyRef(val stId: String, val name: String, val labelIds: String)

    /**
     * 列出账号下全部策略。
     *
     * 服务端把「所有策略」塞在 `data[0].member` 这个字符串化 JSON 里（没有分页语义，
     * `pageNo/pageSize` 传什么都一样），所以这里逐层摊平。
     */
    fun listStrategies(): List<StrategyRef> {
        val text = post(PATH_LIST, "pageNo=1&pageSize=50")
        val root = JSONObject(text)
        ensureOk(root, "查策略列表")
        val out = ArrayList<StrategyRef>()
        val data = root.optJSONArray("data") ?: return out
        val addOne = { id: String, name: String, ids: String ->
            if (id.isNotEmpty()) out.add(StrategyRef(id, name, ids))
        }
        for (i in 0 until data.length()) {
            val d = data.optJSONObject(i) ?: continue
            val member = d.optString("member")
            if (member.isEmpty()) {
                addOne(d.optString("stId"), d.optString("stName"), "")
                continue
            }
            val arr = runCatching { org.json.JSONArray(member) }.getOrNull() ?: continue
            for (j in 0 until arr.length()) {
                val e = arr.optJSONObject(j) ?: continue
                val labels = e.optJSONArray("labels")
                val ids = ArrayList<String>()
                if (labels != null) {
                    for (k in 0 until labels.length()) {
                        val l = labels.optJSONObject(k)?.optString("labelId").orEmpty()
                        if (l.isNotEmpty()) ids.add(l)
                    }
                }
                addOne(e.optString("stId"), e.optString("stName"), ids.joinToString(","))
            }
        }
        return out
    }

    /**
     * 清掉自己漏删的临时策略（名字带 [TEMP_PREFIX]，或早期版本留下的 `Z` + 8 位数字）。
     *
     * @return 删掉的条数
     */
    fun purgeLeftovers(): Int {
        val list = runCatching { listStrategies() }.getOrElse {
            GLog.w("组合取数：列策略失败，没法清理遗留临时策略", it)
            return 0
        }
        var n = 0
        for (s in list) {
            // 正在用的那条不能删：另一个页面可能正拿它取数（见 [liveIds]）
            if (s.stId in liveIds) continue
            val legacy = s.name.length == 9 && s.name[0] == 'Z' && s.name.drop(1).all { it.isDigit() }
            if (!s.name.startsWith(TEMP_PREFIX) && !legacy) continue
            if (runCatching { delete(s.stId) }.getOrDefault(false)) {
                n++
                GLog.i("组合取数：清掉漏删的临时策略 " + s.name + "（" + s.stId + "）")
            } else {
                GLog.w("组合取数：遗留临时策略没删掉 " + s.name + "（" + s.stId + "）")
            }
        }
        return n
    }

    /**
     * 建一条策略；名额满（code 4100）时先清掉自己漏删的临时策略再重试一次。
     *
     * 宿主账号最多 10 条策略，用户自己就能占满 —— 真机上出现过 24 个成员全部
     * 「建策略被拒（code 4100）：策略数不能超过10个」，聚合页只剩本页那一只。
     */
    private fun createOrFreeSlot(name: String, labelIds: String): String {
        try {
            return create(name, labelIds)
        } catch (e: Exception) {
            if (e.message?.contains("4100") != true) throw e
            GLog.w("组合取数：策略名额满了（" + msgOf(e) + "），先清自己漏删的临时策略")
            val freed = purgeLeftovers()
            if (freed <= 0) throw e
            GLog.i("组合取数：清掉 " + freed + " 条，重试建临时策略")
            return create(name, labelIds)
        }
    }

    /**
     * 一整轮聚合复用同一条临时策略：建一次、每格只换标签、结束删掉。
     *
     * 以前是「每个成员建一条 → 取数 → 删一条」，24 个成员就是 24 次建删；
     * 名额一紧（用户自己占了 10 条）就整轮全废。现在任何时刻只占 1 个名额，
     * 而且省掉 23 次建删的网络往返。
     */
    class TempSlot {
        private var id = ""
        private var lastLabels = ""

        val stId: String get() = id

        /** 第一次调用时建出来，之后原样返回；[combo] 只用来给新策略填初始标签 */
        fun ensure(combo: Combo): String {
            if (id.isNotEmpty()) return id
            // 进程被杀时上一轮的临时策略会留在「我的策略」列表里，第一次聚合前顺手清掉
            purgeLeftoversOnce()
            val name = TEMP_PREFIX + System.currentTimeMillis().toString().takeLast(6)
            id = createOrFreeSlot(name, combo.bizLabelId)
            liveIds.add(id)
            lastLabels = combo.bizLabelId
            GLog.i("组合取数：建了一条整轮复用的临时策略 " + name + "（" + id + "）")
            return id
        }

        /** 把临时策略的标签换成 [combo] 的；标签没变就不发请求 */
        fun apply(combo: Combo) {
            if (id.isEmpty()) throw IOException("临时策略还没建出来")
            if (combo.bizLabelId == lastLabels) return
            update(id, combo.bizLabelId)
            lastLabels = combo.bizLabelId
        }

        /** 删掉临时策略；可以重复调 */
        fun release() {
            val dead = id
            id = ""
            lastLabels = ""
            if (dead.isEmpty()) return
            liveIds.remove(dead)
            if (!runCatching { delete(dead) }.getOrDefault(false)) {
                GLog.w("组合取数：这一轮的临时策略没删掉 " + dead + "（下次聚合或名额满时会再清）")
            }
        }
    }

    // ---- 传输 ----

    private fun post(path: String, biz: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        val body = HostSession.buildBody(biz, seq.incrementAndGet(), stamp)
        val bytes = body.toByteArray(Charsets.UTF_8)
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            // 必须带 charset：不带时服务端按别的编码解 body，中文策略名会变成乱码（实测「量50」→「é50」）
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw IOException("HTTP " + code + " " + text.take(200))
            return text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 响应统一是 `{"result":[{"code":0,"msg":"请求成功"}],"data":…}`；code≠0 就是业务失败 */
    private fun ensureOk(root: JSONObject, what: String) {
        var code = 0
        var msg = ""
        val r = root.optJSONArray("result")
        if (r != null && r.length() > 0) {
            val e = r.optJSONObject(0)
            if (e != null) {
                code = e.optInt("code", 0)
                msg = e.optString("msg")
            }
        } else {
            code = root.optInt("code", 0)
            msg = root.optString("msg")
        }
        if (code != 0) throw IOException(what + "被拒（code " + code + "）：" + msg)
    }

    /** 表单值编码：只放过 URL 安全字符，其余按 UTF-8 百分号编码 */
    private fun enc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s.toByteArray(Charsets.UTF_8)) {
            val b = ch.toInt() and 0xFF
            if ((b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x5A) || (b >= 0x61 && b <= 0x7A) ||
                b == 0x2D || b == 0x5F || b == 0x2E || b == 0x7E
            ) sb.append(b.toChar()) else sb.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    private fun msgOf(e: Throwable): String {
        val m = e.message
        return if (m.isNullOrEmpty()) e.javaClass.simpleName else m
    }
}
