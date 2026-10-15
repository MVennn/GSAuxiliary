package io.github.SansKritO4.GSAuxiliary.app

import android.os.Handler
import android.os.Looper
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 与 LSPosed 框架服务的连接。
 *
 * 现代 API 里，模块 App（跑在自己的进程）通过框架注入的 binder 与 LSPosed 通信：
 *
 *   LSPosed 守护进程 --call()--> 模块的 XposedProvider --onBinderReceived--> [XposedServiceHelper]
 *                                                                                    ↓
 *                                                                             [FrameworkBridge]
 *
 * `XposedProvider` 由 `io.github.libxposed:service` 的 AAR 清单自动合并进来
 * （authority = `<applicationId>.XposedService`），我们只需要注册监听。
 *
 * 服务可用时，配置读写走框架的 remote preferences；
 * 不可用时（框架未启用 / 未勾选模块）自动退回本地 SharedPreferences。
 */
object FrameworkBridge {

    fun interface Listener {
        /** service 为 null 表示未连接或已断开 */
        fun onServiceChanged(service: XposedService?)
    }

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var registered = false

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** 服务回调来自 Binder 线程；统一切到主线程再分发，方便 UI 直接订阅 */
    private val mainHandler = Handler(Looper.getMainLooper())

    val isConnected: Boolean get() = service != null

    fun service(): XposedService? = service

    /** 幂等；应在 Application.onCreate 里调用 */
    fun init() {
        if (registered) return
        registered = true
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    val desc = runCatching {
                        "${service.frameworkName} ${service.frameworkVersion}" +
                                " (api ${service.apiVersion})"
                    }.getOrDefault("(信息不可读)")
                    GLog.i("已连接框架服务：$desc")
                    FrameworkBridge.service = service
                    notifyListeners(service)
                }

                override fun onServiceDied(service: XposedService) {
                    GLog.w("框架服务断开")
                    if (FrameworkBridge.service === service) {
                        FrameworkBridge.service = null
                        notifyListeners(null)
                    }
                }
            })
        } catch (t: Throwable) {
            GLog.e("注册框架服务监听失败", t)
        }
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        // 立即回调一次当前状态，避免错过已经发生的 bind
        runCatching { l.onServiceChanged(service) }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyListeners(s: XposedService?) {
        val deliver = Runnable {
            for (l in listeners) {
                runCatching { l.onServiceChanged(s) }
                    .onFailure { GLog.w("服务状态回调异常", it) }
            }
        }
        if (Looper.myLooper() === Looper.getMainLooper()) deliver.run() else mainHandler.post(deliver)
    }
}
