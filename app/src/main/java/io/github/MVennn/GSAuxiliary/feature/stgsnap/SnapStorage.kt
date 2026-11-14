package io.github.MVennn.GSAuxiliary.feature.stgsnap

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 截图落盘。
 *
 * - Android 10+：`MediaStore.Images`（`RELATIVE_PATH=Pictures/GSAuxiliary/<run>`），
 *   目标 App 不需要存储权限（2026-10-22 真机已验证）；
 * - Android 8/9：目标 App 未申请 `WRITE_EXTERNAL_STORAGE`，直接落到 App 的
 *   `getExternalFilesDir(Pictures)`，文件管理器可见，但不进相册；
 * - 任何一步失败都回退到 App 目录，绝不把异常抛给截图任务。
 */
internal object SnapStorage {

    data class Saved(val uri: Uri?, val path: String, val displayName: String)

    /** 每次任务一个目录名；时间戳到秒，任务之间不会混 */
    fun newRunFolder(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun save(context: Context, bitmap: Bitmap, fileName: String, runFolder: String): Saved? {
        // 文件名是拼给 MediaStore 与 File(dir, name) 的：带上 `/` 或 `..` 就能跳出
        // Pictures/GSAuxiliary 之外写文件。当前唯一调用方（StgSnapPlan）已经净化过，
        // 但这里是唯一的落盘入口，自己再兜一次，别把安全寄托在调用方。
        val safeName = safeSegment(fileName).ifEmpty { "snap_" + System.currentTimeMillis() + ".png" }
        val safeFolder = safeSegment(runFolder).ifEmpty { newRunFolder() }
        if (Build.VERSION.SDK_INT >= 29) {
            mediaStore(context, bitmap, safeName, safeFolder)?.let { return it }
        }
        return appDir(context, bitmap, safeName, safeFolder)
    }

    /** 只留文件名安全字符：去掉路径分隔符与上跳，压掉控制字符 */
    private fun safeSegment(raw: String): String = raw
        .replace('\\', '_')
        .replace('/', '_')
        .replace("..", "_")
        .filter { it.code >= 0x20 && it.code != 0x7F }
        .take(120)

    private fun mediaStore(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        runFolder: String,
    ): Saved? {
        var uri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/GSAuxiliary/$runFolder",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                GLog.w("截图并添加：MediaStore.insert 返回 null，改用 App 目录")
                return null
            }
            val ok = resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
                GLog.w("截图并添加：MediaStore 写入失败，改用 App 目录")
                return null
            }
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            GLog.d("截图并添加：已写入相册（$fileName）")
            Saved(uri, uri.toString(), fileName)
        } catch (t: Throwable) {
            GLog.w("截图并添加：MediaStore 写入异常，改用 App 目录", t)
            runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
            null
        }
    }

    private fun appDir(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        runFolder: String,
    ): Saved? {
        return try {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: context.filesDir
            val dir = File(base, "GSAuxiliary/$runFolder")
            if (!dir.exists() && !dir.mkdirs()) {
                GLog.w("截图并添加：创建截图目录失败")
                return null
            }
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    GLog.w("截图并添加：PNG 压缩失败")
                    return null
                }
            }
            GLog.d("截图并添加：已写入 App 目录（$fileName）")
            Saved(null, file.absolutePath, fileName)
        } catch (t: Throwable) {
            GLog.w("截图并添加：写 App 目录失败", t)
            null
        }
    }
}
