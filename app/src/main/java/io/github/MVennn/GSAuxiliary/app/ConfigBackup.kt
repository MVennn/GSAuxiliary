package io.github.MVennn.GSAuxiliary.app

import io.github.MVennn.GSAuxiliary.BuildConfig
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.GLog
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置备份 / 恢复。
 *
 * 只处理 [ConfigKeys.ALL_KEYS] 范围内的键，导出为自描述的 JSON，
 * 便于人工查看与版本迁移。恢复时**只认白名单里的键**，
 * 备份文件里多出来的字段一律忽略，避免被塞进无关键。
 */
object ConfigBackup {

    /** 备份文件标识，导入时用于校验 */
    const val FORMAT = "gszx-config"

    /** 备份格式版本；字段不兼容时递增，导入侧按此做兼容 */
    const val FORMAT_VERSION = 1

    /** 导出当前配置为 JSON 文本 */
    fun exportJson(): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("formatVersion", FORMAT_VERSION)
        root.put("moduleVersion", BuildConfig.VERSION_NAME)
        root.put("versionCode", BuildConfig.VERSION_CODE)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))

        val config = JSONObject()
        // 循环外取一次全量快照；循环内逐键 snapshotConfig() 是 28 次全表重建，纯冗余
        val snapshot = ModulePreferences.snapshotConfig()
        for (key in ConfigKeys.ALL_KEYS) {
            val v = snapshot[key] ?: continue
            config.put(key, v)
        }
        root.put("config", config)

        // 同时带上出厂默认值，方便人工对比 / 未来做「只恢复非默认项」
        val defaults = JSONObject()
        for ((k, v) in ConfigKeys.DEFAULTS) defaults.put(k, v)
        root.put("defaults", defaults)

        return root.toString(2)
    }

    /**
     * 从 JSON 文本恢复配置。
     *
     * @return 成功时返回实际写入的键数量；失败返回 [Result.failure]（附可读原因）
     */
    fun importJson(text: String): Result<Int> = runCatching {
        val root = JSONObject(text)

        val format = root.optString("format")
        if (format != FORMAT) {
            throw IllegalArgumentException("不是本模块的备份文件（format=$format）")
        }
        val fv = root.optInt("formatVersion", 0)
        if (fv > FORMAT_VERSION) {
            throw IllegalArgumentException("备份格式版本 $fv 高于当前支持的 $FORMAT_VERSION")
        }

        val config = root.optJSONObject("config")
            ?: throw IllegalArgumentException("备份文件缺少 config 段")

        // 只取白名单内的键，其余忽略
        val values = LinkedHashMap<String, Any?>()
        for (key in ConfigKeys.ALL_KEYS) {
            if (!config.has(key)) continue
            val v = config.opt(key) ?: continue
            when (v) {
                is Boolean, is Int, is Long, is String -> values[key] = v
                is Double -> values[key] = v.toInt()
                else -> GLog.w("忽略备份里类型不支持的键 $key=${v.javaClass.simpleName}")
            }
        }
        if (values.isEmpty()) {
            throw IllegalArgumentException("备份文件里没有任何可识别的配置项")
        }

        if (!ModulePreferences.putAll(values)) {
            throw IllegalStateException("写入配置失败（配置后端不可用）")
        }
        GLog.i("已从备份恢复 ${values.size} 项配置")
        values.size
    }
}
