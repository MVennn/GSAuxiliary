package io.github.MVennn.GSAuxiliary.feature.zxcopy

import android.app.Activity
import android.content.Intent
import android.net.Uri
import io.github.MVennn.GSAuxiliary.core.GLog

/**
 * 系统分享面板。
 *
 * 只做「把已经落盘的东西交给别的 App」：文本走 `EXTRA_TEXT`，图片走
 * `ACTION_SEND_MULTIPLE`，PDF 走单文件 `ACTION_SEND`。所有 `content://` URI
 * 都带 `FLAG_GRANT_READ_URI_PERMISSION`，否则接收方打不开。
 */
internal object ZxCopyShare {

    fun shareText(activity: Activity, text: String): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(send, "分享"))
        true
    }.getOrElse {
        GLog.w("自选分组复制：分享文本失败", it)
        false
    }

    fun shareImages(activity: Activity, uris: List<Uri>): Boolean {
        if (uris.isEmpty()) return false
        return runCatching {
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/png"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(send, "分享"))
            true
        }.getOrElse {
            GLog.w("自选分组复制：分享图片失败", it)
            false
        }
    }

    fun sharePdf(activity: Activity, uri: Uri): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "分享"))
        true
    }.getOrElse {
        GLog.w("自选分组复制：分享 PDF 失败", it)
        false
    }
}
