package io.github.MVennn.GSAuxiliary.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.StgCatalogBroadcast
import io.github.MVennn.GSAuxiliary.core.TargetApp

/**
 * 接收宿主进程广播过来的策略清单（清单声明见 AndroidManifest.xml，exported）。
 *
 * 宿主跑在 com.guosen.android 进程里，两个进程不同 UID，模块 App 读不到它的数据，
 * 只能由宿主主动发、这里收（协议与取舍见 core/StgCatalogBroadcast）。
 *
 * 收到后只做一件事：落进 [HostStgCatalog] 的本地缓存。编辑页下一次组合时就能看到。
 */
class HostStgCatalogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != StgCatalogBroadcast.ACTION_CATALOG) return
        val sig = intent.getStringExtra(StgCatalogBroadcast.EXTRA_SIG)
        if (sig != StgCatalogBroadcast.SIG) {
            GLog.w("宿主策略清单：广播签名不符，已忽略")
            return
        }
        // 签名是写死在 APK 里的常量，谁都能读出来 —— 它只能挡住「随手乱发」，挡不住伪造。
        // API 34 起系统会告诉我们这条广播是谁发的，直接比宿主 uid：只有宿主进程本身的
        // 清单可信。拿不到宿主 uid（未安装 / 系统查询失败）时退回只认签名，不因此拒收。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hostUid = runCatching {
                context.packageManager.getPackageUid(TargetApp.PACKAGE, 0)
            }.getOrNull()
            if (hostUid != null) {
                @Suppress("NewApi")
                val senderUid = runCatching { sentFromUid }.getOrNull() ?: -1
                if (senderUid != hostUid) {
                    GLog.w("宿主策略清单：发送方 uid=$senderUid 与宿主（$hostUid）不符，已忽略")
                    return
                }
            }
        }
        val payload = intent.getStringExtra(StgCatalogBroadcast.EXTRA_PAYLOAD)
        val count = HostStgCatalog.store(context, payload)
        if (count > 0) {
            GLog.i("宿主策略清单：已收到 $count 条")
        } else {
            GLog.w("宿主策略清单：载荷为空或解析不出条目，未覆盖旧缓存")
        }
    }
}
