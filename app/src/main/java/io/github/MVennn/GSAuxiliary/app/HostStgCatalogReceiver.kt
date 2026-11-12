package io.github.MVennn.GSAuxiliary.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.StgCatalogBroadcast

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
        val payload = intent.getStringExtra(StgCatalogBroadcast.EXTRA_PAYLOAD)
        val count = HostStgCatalog.store(context, payload)
        if (count > 0) {
            GLog.i("宿主策略清单：已收到 $count 条")
        } else {
            GLog.w("宿主策略清单：载荷为空或解析不出条目，未覆盖旧缓存")
        }
    }
}
