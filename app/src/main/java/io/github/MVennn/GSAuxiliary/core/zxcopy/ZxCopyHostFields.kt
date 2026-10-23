package io.github.MVennn.GSAuxiliary.core.zxcopy

/**
 * 宿主 `StockInfo` 的字段候选名 —— **只放字符串，不依赖 Android / Xposed**。
 *
 * 单独成文件有两个原因：
 *  - [ZxCopyFieldCatalog] 的 `Spec` 直接引用这些数组，字段候选只有一份；
 *  - `tools/ci/check_contract.py` 会扫描本文件里的 `*_FIELDS`，把宿主字段名
 *    登记进 `tools/contract/host-contract.json`。宿主升级改名时，契约校验能提醒
 *    维护者「哪个候选已经不在宿主里了」。
 *
 * 候选名来自 2026-10-22 探针对 `com.guosen.android.quotes.data.StockInfo` 的类 dump；
 * 多候选按顺序取第一个非空。
 */
object ZxCopyHostFields {

    val NAME_FIELDS = arrayOf("stkName", "stockName")
    val CODE_FIELDS = arrayOf("m_stkCode", "stockCode", "codeAndMarket")
    val MARKET_FIELDS = arrayOf("m_stkMarket")

    val PRICE_FIELDS = arrayOf("stockPrice", "mPrice", "zxj", "price")
    val CHANGE_FIELDS = arrayOf("zd")
    val CHANGE_PCT_FIELDS = arrayOf("zdf")
    val SPEED_FIELDS = arrayOf("zs")
    val AMPLITUDE_FIELDS = arrayOf("zf")
    val TURNOVER_FIELDS = arrayOf("hsl")
    val VOLUME_RATIO_FIELDS = arrayOf("lb")
    val AMOUNT_FIELDS = arrayOf("cjje")
    val VOLUME_FIELDS = arrayOf("cjsl")

    val FLOAT_CAP_FIELDS = arrayOf("money_ltsz")
    val TOTAL_CAP_FIELDS = arrayOf("money_zsz")
    val PE_FIELDS = arrayOf("syl", "peTTM", "peStatic")
    val PB_FIELDS = arrayOf("sjl")
    val DIVIDEND_FIELDS = arrayOf("mGxl")

    val OPEN_FIELDS = arrayOf("jrkp")
    val PREV_CLOSE_FIELDS = arrayOf("zrsp")
    val HIGH_FIELDS = arrayOf("highPrice")
    val LOW_FIELDS = arrayOf("lowPrice")
    val AVG_PRICE_FIELDS = arrayOf("mAverage")
    val ZDF3_FIELDS = arrayOf("zdf_3_day")
    val ZDF5_FIELDS = arrayOf("zdf_5_day")
    val ZDF20_FIELDS = arrayOf("zdf_20_day")
    val ZDF60_FIELDS = arrayOf("zdf_60_day")
    val ZDF_YEAR_FIELDS = arrayOf("zdf_year")

    val INDUSTRY_FIELDS = arrayOf("mBelongHyName")
    val LABEL_FIELDS = arrayOf("m_stkLable")
    val ADD_TIME_FIELDS = arrayOf("addTime")
    val PRICE_DATE_FIELDS = arrayOf("priceDate")
    val MAIN_FUND_FIELDS = arrayOf("moneyInout")
    val MAIN_FUND_PCT_FIELDS = arrayOf("moneyInoutZl")
    val NOW_VOL_FIELDS = arrayOf("nowVol")
    val WEI_BI_FIELDS = arrayOf("wb")
}
