package io.github.MVennn.GSAuxiliary.feature.stgmerge

import io.github.MVennn.GSAuxiliary.core.MergeMember

/**
 * 标签档位表 + 用户要的四个口径。
 *
 * ## labelId 来源
 *
 * 全部来自宿主自己的标签接口 `/gsnews/pick/queryLabelList/1.0`（返回 227 KB 扁平表，
 * 字段 `labelId/labelName/parentId/levelNum`），是**实测值**不是猜的：
 *  - 量比分组 parentId = 1363923810041987072（行情面）
 *  - 流通市值分组 parentId = 332920492044320768（基本面）
 *
 * 宿主 App 升级后如果档位变了，重跑一次标签接口对着 `labelName` 改这里即可。
 *
 * ## 为什么一个组合只能是「1 个市值档 + 1 个量比档」
 *
 * 宿主标签页里同一标签多档位是 **AND（交集）**，不是 OR —— 实测同时选
 * 流通市值 50-70亿 + 70-100亿 + 100-500亿 得到「选出股票 0 只」。
 * 所以「50-500亿」这种跨档范围只能拆成多个组合，由本插件的聚合并集来表达。
 */
object StrategyComboCatalog {

    // ---- 流通市值 ----
    const val M_UNDER_10 = "332920681815605248"   // 10亿以下
    const val M_10_30 = "332920796135555072"      // 10-30亿
    const val M_30_50 = "332920893225304064"      // 30-50亿
    const val M_50_70 = "332921024326664192"      // 50-70亿
    const val M_70_100 = "332921124713136128"     // 70-100亿
    const val M_100_500 = "332921394713067520"    // 100-500亿
    const val M_500_UP = "332921788629516288"     // 500亿及以上

    // ---- 量比（8 档） ----
    const val R_LE_05 = "1363924533756559360"     // 0.5及以下
    const val R_05_10 = "1363924755333251072"     // 0.5-1.0
    const val R_10_15 = "1363924870479478784"     // 1.0-1.5
    const val R_15_20 = "1363924975269969920"     // 1.5-2.0
    const val R_20_25 = "1363925078818947072"     // 2.0-2.5
    const val R_25_30 = "1363925245672554496"     // 2.5-3.0
    const val R_30_40 = "1363925375171690496"     // 3.0-4.0
    const val R_ABOVE_40 = "1363925509783683072"  // 4.0以上

    /** 创新高20交易日（用户 4 个老策略都带它；新口径里没提，默认不加） */
    const val X_NEW_HIGH_20 = "313635411240943616"

    /** 一个档位：展示名 + labelId */
    data class Tier(val name: String, val labelId: String)

    /** 一个口径：跨若干市值档 × 若干量比档，成员 = 全部两两组合 */
    data class Bucket(val key: String, val name: String, val markets: List<Tier>, val ratios: List<Tier>) {
        /** 组合数量（= 该口径在聚合里需要的成分数） */
        val comboCount: Int get() = markets.size * ratios.size
    }

    private val MID = listOf(
        Tier("50-70亿", M_50_70),
        Tier("70-100亿", M_70_100),
        Tier("100-500亿", M_100_500),
    )

    private val BIG = listOf(Tier("500亿及以上", M_500_UP))

    private val RATIO_15_4 = listOf(
        Tier("1.5-2.0", R_15_20),
        Tier("2.0-2.5", R_20_25),
        Tier("2.5-3.0", R_25_30),
        Tier("3.0-4.0", R_30_40),
    )

    private val RATIO_4P = listOf(Tier("4.0以上", R_ABOVE_40))

    /** 用户 2026-11-12 明确要的四个口径 */
    val BUCKETS = listOf(
        Bucket("mid-15-4", "中盘 1.5-4", MID, RATIO_15_4),
        Bucket("mid-4p", "中盘 4+", MID, RATIO_4P),
        Bucket("big-15-4", "大盘 1.5-4", BIG, RATIO_15_4),
        Bucket("big-4p", "大盘 4+", BIG, RATIO_4P),
    )

    fun bucketOf(key: String): Bucket? = BUCKETS.firstOrNull { it.key == key }

    /** 全部市值档（宿主「基本面 → 流通市值」下的 7 档，顺序与宿主一致） */
    val ALL_MARKETS = listOf(
        Tier("10亿以下", M_UNDER_10),
        Tier("10-30亿", M_10_30),
        Tier("30-50亿", M_30_50),
        Tier("50-70亿", M_50_70),
        Tier("70-100亿", M_70_100),
        Tier("100-500亿", M_100_500),
        Tier("500亿及以上", M_500_UP),
    )

    /** 全部量比档（8 档，顺序与宿主一致） */
    val ALL_RATIOS = listOf(
        Tier("0.5及以下", R_LE_05),
        Tier("0.5-1.0", R_05_10),
        Tier("1.0-1.5", R_10_15),
        Tier("1.5-2.0", R_15_20),
        Tier("2.0-2.5", R_20_25),
        Tier("2.5-3.0", R_25_30),
        Tier("3.0-4.0", R_30_40),
        Tier("4.0以上", R_ABOVE_40),
    )

    /**
     * 一个维度：宿主标签表里的一个标签。
     *
     * 有档位的标签（「流通市值」7 档、「量比」8 档、「创新高」7 档）就是维度，用户勾几档、
     * 这一维就展开成几行；没有档位的标签（「放量」「T字型」）自成维度、只有一档。
     * 组合数 = 各维档位数相乘，也就是要现建多少条临时策略。
     *
     * @param id 维度标识：有档位时是父标签 labelId，无档位标签就是它自己的 labelId
     * @param tagPrefix 宿主标签写法里的前缀：「流通市值」；无档位标签为空（标签名本身就是全名）
     * @param title 界面显示：有档位写父标签名「流通市值」，无档位写标签名「放量」
     */
    data class Dim(
        val id: String,
        val tagPrefix: String,
        val title: String,
        val tiers: List<Tier>,
    ) {
        val count: Int get() = tiers.size

        /** 这一维在标签条上要出的每一条：宿主写法 = 前缀 + 档位名 */
        fun tags(): List<String> = tiers.map { tagPrefix + it.name }

        /** 这一维的一行说明，跨档写成首尾区间（与宿主合并标签的写法一致） */
        fun summary(): String? = spanTag(tagPrefix, tiers)

        /** 档位摘要：全选写「全部 N 档」，只选部分就把档位名列出来 */
        fun bandText(allCount: Int): String =
            if (tiers.size >= allCount) "全部 " + allCount + " 档" else tiers.joinToString("、") { it.name }
    }

    /**
     * 用户自己拼的条件：任意若干维度，每维任意若干档。
     *
     * 与四个固定口径（[BUCKETS]）的关系：口径只是「市值 × 量比」的常用预设，条件才是本体。
     * 用户想「市值 × 创新高」就换成那几个维度，代码不需要再认第二种组合方式。
     */
    data class Condition(val dims: List<Dim>) {

        /** 兼容老写法：只给市值档与量比档，就等于两维条件 */
        constructor(markets: List<Tier>, ratios: List<Tier>) : this(
            buildList {
                if (markets.isNotEmpty()) add(Dim(MARKET_PARENT_ID, MARKET_TAG_PREFIX, MARKET_TAG_PREFIX, markets))
                if (ratios.isNotEmpty()) add(Dim(RATIO_PARENT_ID, RATIO_TAG_PREFIX, RATIO_TAG_PREFIX, ratios))
            },
        )

        /**
         * 展开后要取几次数（宿主同一标签多档是 AND，跨档只能拆成多个组合）。
         *
         * 没有维度就是 0 次：空条件的笛卡尔积在数学上是「一个空组合」，但那个组合
         * 没有任何 labelId，拿去建策略只会被宿主拒掉，还会白占一个临时策略名额。
         */
        val comboCount: Int get() = if (dims.isEmpty()) 0 else dims.fold(1) { acc, d -> acc * d.count }

        /** 市值那一维的档位（老代码与测试按两维写法读） */
        val markets: List<Tier> get() = dims.firstOrNull { it.id == MARKET_PARENT_ID }?.tiers ?: emptyList()

        /** 量比那一维的档位 */
        val ratios: List<Tier> get() = dims.firstOrNull { it.id == RATIO_PARENT_ID }?.tiers ?: emptyList()
    }

    /** 宿主「流通市值」父标签的 labelId（表体里每一档的 parentId） */
    const val MARKET_PARENT_ID = "332920492044320768"

    /** 宿主「量比」父标签的 labelId */
    const val RATIO_PARENT_ID = "1363923810041987072"

    /** 宿主标签表里的一个节点 → 一个维度（档位全部预选，用户再取消不要的） */
    internal fun dimOf(node: StrategyLabelTable.LabelNode): Dim {
        val tiers = if (node.hasBands) {
            node.bands.map { Tier(it.labelName, it.labelId) }
        } else {
            listOf(Tier(node.name, node.labelId))
        }
        return Dim(
            id = node.labelId,
            tagPrefix = if (node.hasBands) node.name else "",
            title = node.name,
            tiers = tiers,
        )
    }

    /** 固定口径 → 两维条件（市值 × 量比）：口径与自选条件走同一套展开逻辑 */
    fun bucketCondition(bucket: Bucket): Condition = Condition(
        listOf(
            Dim(MARKET_PARENT_ID, MARKET_TAG_PREFIX, MARKET_TAG_PREFIX, bucket.markets),
            Dim(RATIO_PARENT_ID, RATIO_TAG_PREFIX, RATIO_TAG_PREFIX, bucket.ratios),
        ),
    )

    /** 条件成员在配置里的名字前缀（与 [COMBO_PREFIX] 一样带冒号，不会和宿主 stId 撞） */
    const val COND_PREFIX = "cond:"

    fun marketTier(name: String): Tier? = ALL_MARKETS.firstOrNull { it.name == name.trim() }

    fun ratioTier(name: String): Tier? = ALL_RATIOS.firstOrNull { it.name == name.trim() }

    /** 条件成员的识别名：`cond:` + 各维档位的 labelId，维内与维间都用逗号隔开（全数字，稳定） */
    fun condKey(c: Condition): String =
        COND_PREFIX + c.dims.flatMap { it.tiers }.joinToString(",") { it.labelId }

    /** 兼容两维写法：`condKey(市值档, 量比档)` */
    fun condKey(markets: List<Tier>, ratios: List<Tier>): String = condKey(Condition(markets, ratios))

    /**
     * 把识别名还原成条件；档位表改过、对不上就返回 null（那一条成员会被跳过，不炸整页）。
     *
     * 两种写法都认：
     *  - 新写法 `cond:332921024326664192,1363924975269969920` —— 纯 labelId，查标签表即得维度；
     *  - 老写法 `cond:50-70亿,70-100亿|1.5-2.0` —— 纯名字，只在市值 × 量比两维里找（历史配置）。
     */
    fun conditionOfKey(key: String): Condition? {
        if (!key.startsWith(COND_PREFIX)) return null
        val body = key.removePrefix(COND_PREFIX).trim()
        if (body.isEmpty()) return null
        val toks = body.split('|', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (toks.isEmpty()) return null
        if (toks.all { t -> t.all { it in '0'..'9' } }) return conditionOfIds(toks)
        return legacyCondition(body)
    }

    /** 老写法的名字式条件：两段 市值|量比 */
    private fun legacyCondition(body: String): Condition? {
        val parts = body.split('|')
        if (parts.size != 2) return null
        val ms = parts[0].split(',').mapNotNull { marketTier(it) }
        val rs = parts[1].split(',').mapNotNull { ratioTier(it) }
        if (ms.isEmpty() || rs.isEmpty()) return null
        return Condition(ms, rs)
    }

    /** 一串 labelId → 条件：同父标签的档位并成一维，维序按首次出现排 */
    private fun conditionOfIds(ids: List<String>): Condition? {
        val dims = ArrayList<Dim>()
        val at = HashMap<String, Int>()
        for (id in ids) {
            val e = StrategyLabelTable.entryOf(id) ?: return null
            val prefix = e.parentName
            val dimId = if (prefix.isEmpty()) e.labelId else e.parentId
            val tier = Tier(e.labelName, e.labelId)
            val i = at[dimId]
            if (i == null) {
                at[dimId] = dims.size
                dims.add(Dim(dimId, prefix, if (prefix.isEmpty()) e.labelName else prefix, listOf(tier)))
            } else {
                val d = dims[i]
                dims[i] = d.copy(tiers = d.tiers + tier)
            }
        }
        return if (dims.isEmpty()) null else Condition(dims)
    }

    /** 一个成员是不是「自定义条件」；填了 stId 就按普通策略走（同 [bucketOfMember] 的判据） */
    fun conditionOfMember(name: String, stId: String): Condition? {
        val nm = name.trim()
        if (stId.trim().isNotEmpty() || !nm.startsWith(COND_PREFIX)) return null
        return conditionOfKey(nm)
    }

    /** 条件在标签条上要显示的档位（每个档位各一条，供并集合并用） */
    fun tagNamesOf(c: Condition): List<String> = c.dims.flatMap { it.tags() }

    /** 条件的一行说明：「流通市值50-500亿 · 量比1.5-4」；每维各出一段，顺序即维度顺序 */
    fun summaryText(c: Condition): String =
        c.dims.mapNotNull { it.summary() }.joinToString(" · ")

    /**
     * 条件展开成取数请求：各维档位做笛卡尔积，一条 = 一个 labelId 列表。
     *
     * key 是逗号连的 labelId（不带 [COMBO_PREFIX]）—— 调用方统一再加前缀当队列 key，
     * 与口径成员的三段式 key 靠「全数字」这条判据区分，见 [idsOf]。
     */
    fun combosOf(c: Condition): List<StrategyComboClient.Combo> {
        if (c.dims.isEmpty()) return emptyList()
        // 只要 labelId：档位名在生成 key / 取数时都不需要（key 就是 labelId 拼接），
        // 早先这一步还顺手拼了个名字列表，纯白分配。
        var partial = listOf<List<String>>(emptyList())
        for (d in c.dims) {
            val next = ArrayList<List<String>>(partial.size * d.count)
            for (p in partial) {
                for (t in d.tiers) {
                    next.add(p + t.labelId)
                }
            }
            partial = next
        }
        val out = ArrayList<StrategyComboClient.Combo>(partial.size)
        for (ids in partial) {
            out.add(StrategyComboClient.Combo(key = ids.joinToString(","), labelIds = ids))
        }
        return out
    }

    /**
     * 一个聚合组在目标进程里到底会排几条取数条目 —— 判定口径的**唯一实现**。
     *
     * 设置页的「N 个成员 · 生效」与列表页入口卡上的「（N 条）」必须是同一个数，
     * 而且必须是**展开后**的数：口径成员（「中盘 1.5-4」这类）本身不是一条宿主策略，
     * 取数时展开成「市值档 × 量比档」若干条。以前列表页写的是配置里的原始成员数，
     * 于是同一个组在列表上显示「（2 条）」、点进详情页变成 13 条，用户以为聚合坏了。
     *
     * 判据与 StrategyMergeFeature 组装成员的循环逐条对齐：
     *  - stId 为空且名字命中口径（中文显示名或 ASCII key）→ 累加 comboCount
     *  - 其余按名字去重（名字为空退 stId），一条算一条；解析不出 stId 的成员
     *    在真机上会被跳过，但这里仍计 1 —— 门槛判断要宽松，别把「暂时没对上号」算成未生效。
     */
    /**
     * 口径成员在标签条上应该显示哪些条件。
     *
     * 口径成员不是宿主的策略，没有原生 tags 可采（列表页采集与详情页 m_tagList 都取不到它），
     * 标签只能按档位表反推。写法与宿主原生标签**逐字一致**（「流通市值50-70亿」「量比1.5-2.0」），
     * 这样 mergeTagNames 的区间并集才认得出它们，合并出的「流通市值50-500亿」也跟宿主同一套格式 ——
     * 否则列表卡与详情页的标签条会缺掉整个市值维度（真机症状：中盘组只显示一条「量比4.0以上」）。
     */
    fun tagNamesOf(bucket: Bucket): List<String> = tagNamesOf(bucketCondition(bucket))

    /**
     * 口径条件合成一行展示文本：「流通市值50-500亿 · 量比1.5-4」。
     *
     * 与 [tagNamesOf] 的分工：那个给**每个档位各出一条**（供标签并集合并用），
     * 这个只给设置页副标题与添加成员面板用 —— 跨档写成首尾区间，一行看得完。
     * 单档口径（大盘 4+）原样输出，不加连字符。
     */
    fun summaryText(bucket: Bucket): String = summaryText(bucketCondition(bucket))

    /** 跨档写成「首档起点-末档终点」：50-70亿 / 70-100亿 / 100-500亿 → 流通市值50-500亿 */
    private fun spanTag(prefix: String, tiers: List<Tier>): String? {
        if (tiers.isEmpty()) return null
        if (tiers.size == 1) return prefix + tiers[0].name
        val first = tiers.first().name
        val last = tiers.last().name
        // 端点保留档位表原文（1.5-4.0，不裁 .0）：卡面 chip 与这一行都走同一套合成口径，
        // 裁了就会出现「设置页写 1.5-4、标签条写 1.5-4.0」的两种写法
        return prefix + first.substringBefore('-').trim() + "-" + last.substringAfter('-', last).trim()
    }

    /** 组合占位 key（`combo:mid-15-4|50-70亿|2.0-2.5@）→ 这个组合自己那两条条件 */
    fun tagNamesOfKey(key: String): List<String> {
        labelIdOf(key)?.let { return listOf(StrategyLabelTable.displayOf(it)) }
        idsOf(key)?.let { ids -> return ids.map { StrategyLabelTable.displayOf(it) } }
        val p = key.removePrefix(COMBO_PREFIX).split('|')
        if (p.size == 2) {
            if (marketTier(p[0]) == null || ratioTier(p[1]) == null) return emptyList()
            return listOf(MARKET_TAG_PREFIX + p[0], RATIO_TAG_PREFIX + p[1])
        }
        if (p.size != 3 || bucketOf(p[0]) == null) return emptyList()
        return listOf(MARKET_TAG_PREFIX + p[1], RATIO_TAG_PREFIX + p[2])
    }

    /**
     * 组合 key 里那串 labelId：`combo:` 前缀可选，逗号分隔、全数字、每一档都得在标签表里。
     *
     * 这是「自选维度组合」与「固定口径三段 key」的分界：三段 key 里带 `|` 与口径 ASCII key，
     * 过不了「全数字 + 查表命中」这两道判据，所以两种 key 不会互相误认。
     */
    fun idsOf(key: String): List<String>? {
        var k = key.trim()
        if (k.startsWith(COMBO_PREFIX)) k = k.removePrefix(COMBO_PREFIX).trim()
        if (k.isEmpty() || k.startsWith(LABEL_PREFIX)) return null
        val toks = k.split(',').map { it.trim() }
        for (t in toks) {
            if (t.isEmpty() || !t.all { it in '0'..'9' }) return null
            if (StrategyLabelTable.entryOf(t) == null) return null
        }
        return toks
    }

    /** 宿主原生标签的写法：「流通市值」+ 档位名 */
    const val MARKET_TAG_PREFIX = "流通市值"

    /** 宿主原生标签的写法：「量比」+ 档位名 */
    const val RATIO_TAG_PREFIX = "量比"

    /**
     * 一个成员是不是「口径成员」，是的话返回它对应的口径。
     *
     * 判据只有一条：**stId 为空**且名字命中口径（中文显示名或 ASCII key）。
     * 填了 stId 说明用户真建了一条同名策略，那条能直接取数，按普通成员走、不展开。
     * 组装成员、算条数、拼标签三处都调它 —— 判据写散了三处就会漂，真机已经踩过一次
     * （列表写「2 条」、详情页 13 条）。
     */
    fun bucketOfMember(name: String, stId: String): Bucket? {
        val nm = name.trim()
        if (stId.trim().isNotEmpty() || nm.isEmpty()) return null
        return BUCKETS.firstOrNull { it.name == nm } ?: BUCKETS.firstOrNull { it.key == nm }
    }

    fun expandedCount(members: List<MergeMember>): Int {
        var n = 0
        val seen = HashSet<String>()
        for (m in members) {
            val name = m.name.trim()
            val stId = m.stId.trim()
            val bucket = bucketOfMember(name, stId)
            if (bucket != null) {
                n += bucket.comboCount
                continue
            }
            val cond = conditionOfMember(name, stId)
            if (cond != null) {
                n += cond.comboCount
                continue
            }
            // 父标签成员（`label:<父labelId>`）还没细分到具体档位，取数时会被跳过，这里就不计数：
            // 计进去会让列表卡上的「（N 条）」和详情页真正拉到的条数对不上。
            val lbl = labelIdOf(name)
            if (lbl != null && StrategyLabelTable.isParentOf(lbl)) continue
            if (!seen.add(name.ifEmpty { stId })) continue
            n += 1
        }
        return n
    }

    /**
     * 组合成员在聚合队列里的 id 前缀。
     *
     * 聚合的队列元素是 `(stId, 显示名)`，普通成员放真 stId；口径成员没有 stId
     * （它压根不是一条宿主策略），用这个前缀占位，取数时现建一条临时策略顶上去。
     * 前缀带冒号，和宿主 stId（纯数字）绝不会撞。
     */
    const val COMBO_PREFIX = "combo:"

    /**
     * 自定义标签成员的识别名前缀：`label:<labelId>`。
     *
     * 一条这样的成员 = 宿主标签表里的**一档**（如「量比1.5-2.0」），取数时现建一条只含这一个
     * labelId 的临时策略。它是「新建成员」那条路的产物：用户从 1119 条标签里点哪一档就是它，
     * 不再限定「市值 × 量比」两维 —— 金太阳有多少标签，成员就能组多少种。
     */
    const val LABEL_PREFIX = "label:"

    /**
     * `label:xxx` → 那段 labelId；不是这类成员返回 null
     *
     * 也认 `combo:label:xxx`：标签成员进聚合队列时统一包了一层 [COMBO_PREFIX]（占位 key 与口径成员同构），
     * 只认裸 `label:` 会让队列里的那一条既显示不出标签、也拼不出 tagNames（真机症状：
     * 「聚合组2」全用标签成员选的，列表卡标签却还留着借来那张卡的「流通市值500亿及以上 / 创新高20交易日」）。
     */
    fun labelIdOf(key: String): String? {
        var k = key.trim()
        if (k.startsWith(COMBO_PREFIX)) k = k.removePrefix(COMBO_PREFIX).trim()
        if (!k.startsWith(LABEL_PREFIX)) return null
        return k.removePrefix(LABEL_PREFIX).trim().ifEmpty { null }
    }

    /** 一个组合的展示名：`中盘 1.5-4 · 50-70亿 × 2.0-2.5` */
    fun displayName(key: String): String {
        labelIdOf(key)?.let { return StrategyLabelTable.displayOf(it) }
        idsOf(key)?.let { ids -> return ids.joinToString(" × ") { StrategyLabelTable.displayOf(it) } }
        val p = key.split('|')
        // 两段 = 老的自选条件组合（`50-70亿|2.0-2.5`），三段 = 固定口径生成的组合
        if (p.size == 2) return p[0] + " × " + p[1]
        if (p.size != 3) return key
        val b = bucketOf(p[0]) ?: return key
        return b.name + " · " + p[1] + " × " + p[2]
    }

    /** 把队列里的占位 key 还原成可取数的组合；解析不出来返回 null（配置被改过、档位表换过） */
    fun comboOfKey(key: String): StrategyComboClient.Combo? {
        labelIdOf(key)?.let { return StrategyComboClient.Combo(key, listOf(it)) }
        // 自选维度组合：key 就是一串 labelId，原样当取数条件
        idsOf(key)?.let { return StrategyComboClient.Combo(key, it) }
        val p = key.split('|')
        if (p.size == 2) {
            // 用户自选条件生成的组合：档位直接查全局档位表，不挂在某个口径下
            val m = marketTier(p[0]) ?: return null
            val r = ratioTier(p[1]) ?: return null
            return StrategyComboClient.Combo(key, listOf(m.labelId, r.labelId))
        }
        if (p.size != 3) return null
        val b = bucketOf(p[0]) ?: return null
        val m = b.markets.firstOrNull { it.name == p[1] } ?: return null
        val r = b.ratios.firstOrNull { it.name == p[2] } ?: return null
        return StrategyComboClient.Combo(key, listOf(m.labelId, r.labelId))
    }

    /**
     * 把一个口径展开成「市值档 × 量比档」的请求列表。
     *
     * key 形如 `mid-15-4|50-70亿|1.5-2.0`，直接映射到 [StrategyComboClient.Combo.key]，
     * 聚合页可以拿它当缓存键，也可以在日志里当人看得懂的行号。
     */
    fun combosOf(bucket: Bucket): List<StrategyComboClient.Combo> {
        val out = ArrayList<StrategyComboClient.Combo>(bucket.comboCount)
        for (m in bucket.markets) {
            for (r in bucket.ratios) {
                out.add(
                    StrategyComboClient.Combo(
                        key = bucket.key + "|" + m.name + "|" + r.name,
                        labelIds = listOf(m.labelId, r.labelId),
                    )
                )
            }
        }
        return out
    }
}
