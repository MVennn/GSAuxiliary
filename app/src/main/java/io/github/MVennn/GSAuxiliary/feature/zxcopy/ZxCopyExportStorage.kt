package io.github.MVennn.GSAuxiliary.feature.zxcopy

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.MVennn.GSAuxiliary.core.GLog
import java.io.File
import java.io.FileOutputStream

/**
 * 导出落盘：图片进相册 / PDF 进下载。
 *
 * - Android 10+：`MediaStore`（图片 `Pictures/GSAuxiliary`、PDF `Downloads/GSAuxiliary`），
 *   目标 App 不需要存储权限（截图功能 2026-10-22 已在同款路径上真机验证）；
 * - Android 8/9：落到 App 的 `getExternalFilesDir`，文件管理器可见但不进相册 / 下载；
 *   这时拿不到可分享的 content URI，调用方会把「分享」降级成「提示路径」。
 *
 * 任何一步失败都回退到 App 目录，绝不把异常抛给点击链路。
 */
internal object ZxCopyExportStorage {

    data class Saved(
        val uri: Uri?,
        val path: String,
        val name: String,
    )

    fun saveImage(context: Context, bitmap: Bitmap, name: String): Saved? {
        if (Build.VERSION.SDK_INT >= 29) {
            mediaStoreImage(context, bitmap, name)?.let { return it }
        }
        return appDirImage(context, bitmap, name)
    }

    fun savePdf(context: Context, bytes: ByteArray, name: String): Saved? {
        if (Build.VERSION.SDK_INT >= 29) {
            mediaStorePdf(context, bytes, name)?.let { return it }
        }
        return appDirPdf(context, bytes, name)
    }

    /** 文件名里不能出现路径分隔符 / 控制字符；中文保留 */
    fun safeName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").take(80).ifBlank { "自选分组" }

    // ------------------------------------------------------------------ 图片

    private fun mediaStoreImage(context: Context, bitmap: Bitmap, name: String): Saved? {
        var uri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GSAuxiliary")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                GLog.w("自选分组复制：图片 MediaStore.insert 返回 null，改用 App 目录")
                return null
            }
            val ok = resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
                GLog.w("自选分组复制：图片 MediaStore 写入失败，改用 App 目录")
                return null
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            GLog.d("自选分组复制：已写入相册（$name）")
            Saved(uri, uri.toString(), name)
        } catch (t: Throwable) {
            GLog.w("自选分组复制：图片 MediaStore 写入异常，改用 App 目录", t)
            runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
            null
        }
    }

    private fun appDirImage(context: Context, bitmap: Bitmap, name: String): Saved? = try {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val dir = File(base, "GSAuxiliary")
        if (!dir.exists() && !dir.mkdirs()) {
            GLog.w("自选分组复制：创建图片目录失败")
            null
        } else {
            val file = File(dir, name)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    GLog.w("自选分组复制：PNG 压缩失败")
                    return null
                }
            }
            GLog.d("自选分组复制：已写入 App 图片目录（$name）")
            Saved(null, file.absolutePath, name)
        }
    } catch (t: Throwable) {
        GLog.w("自选分组复制：写 App 图片目录失败", t)
        null
    }

    // ------------------------------------------------------------------ PDF

    private fun mediaStorePdf(context: Context, bytes: ByteArray, name: String): Saved? {
        var uri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                // ⚠️ Downloads 集合的主目录必须写单数 `Download`；写 `Downloads`
                // 会抛 IllegalArgumentException（真机 2026-10-23 实测）。
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/GSAuxiliary")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                GLog.w("自选分组复制：PDF MediaStore.insert 返回 null，改用 App 目录")
                return null
            }
            val ok = resolver.openOutputStream(uri)?.use { out -> out.write(bytes); true } ?: false
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
                GLog.w("自选分组复制：PDF MediaStore 写入失败，改用 App 目录")
                return null
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            GLog.d("自选分组复制：已写入下载（$name）")
            Saved(uri, uri.toString(), name)
        } catch (t: Throwable) {
            GLog.w("自选分组复制：PDF MediaStore 写入异常，改用 App 目录", t)
            runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
            null
        }
    }

    private fun appDirPdf(context: Context, bytes: ByteArray, name: String): Saved? = try {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val dir = File(base, "GSAuxiliary")
        if (!dir.exists() && !dir.mkdirs()) {
            GLog.w("自选分组复制：创建 PDF 目录失败")
            null
        } else {
            val file = File(dir, name)
            FileOutputStream(file).use { it.write(bytes) }
            GLog.d("自选分组复制：已写入 App PDF 目录（$name）")
            Saved(null, file.absolutePath, name)
        }
    } catch (t: Throwable) {
        GLog.w("自选分组复制：写 App PDF 目录失败", t)
        null
    }
}
