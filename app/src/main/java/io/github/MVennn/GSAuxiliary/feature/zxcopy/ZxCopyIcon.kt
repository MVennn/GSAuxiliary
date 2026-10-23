package io.github.MVennn.GSAuxiliary.feature.zxcopy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics

/**
 * 自绘「复制」图标。
 *
 * 不引图标库、不依赖模块资源：目标进程里画一张白描边的双矩形位图即可。
 * 顶栏是品牌红底、宿主那颗铅笔也是白色线稿，所以这里固定白色。
 *
 * 后层矩形在「前层矩形 + 描边」的区域被裁掉（`clipOutPath`），看起来才像
 * 「两张纸叠在一起」，而不是两个互相穿插的方框。
 */
object ZxCopyIcon {

    private const val WHITE = Color.WHITE

    fun create(metrics: DisplayMetrics, sizePx: Int): Drawable {
        val size = sizePx.coerceAtLeast((24 * metrics.density).toInt()).coerceAtMost(256)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // 位图按屏幕密度标注，避免 BitmapDrawable 的 intrinsic size 被二次缩放
        bmp.density = metrics.densityDpi

        val c = Canvas(bmp)
        val stroke = size * 0.07f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val radius = size * 0.09f
        val back = RectF(size * 0.30f, size * 0.16f, size * 0.84f, size * 0.68f)
        val front = RectF(size * 0.16f, size * 0.32f, size * 0.70f, size * 0.84f)

        // 后层先画，再用「前层外扩半个描边」的路径把被前层盖住的部分裁掉
        val mask = Path().apply {
            addRoundRect(
                RectF(
                    front.left - stroke / 2,
                    front.top - stroke / 2,
                    front.right + stroke / 2,
                    front.bottom + stroke / 2,
                ),
                radius + stroke / 2,
                radius + stroke / 2,
                Path.Direction.CW,
            )
        }
        c.save()
        c.clipOutPath(mask)
        c.drawRoundRect(back, radius, radius, paint)
        c.restore()

        c.drawRoundRect(front, radius, radius, paint)
        return BitmapDrawable(null, bmp)
    }
}
