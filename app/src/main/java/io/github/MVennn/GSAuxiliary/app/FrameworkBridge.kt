package io.github.MVennn.GSAuxiliary.app

import android.os.Handler
import android.os.Looper
import io.github.MVennn.GSAuxiliary.core.GLog
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
        // 回调一次当前状态，避免错过已经发生的 bind。
        //
        // ⚠️ 必须 **post** 而不是同步调用（2.17.3）：订阅方往往在 Application.onCreate
        // 里 addListener，随后才做自己的收尾初始化。若在这里同步回调，
        // ModulePreferences 回调里打的「配置后端 = LOCAL…」会抢在订阅方初始化之前落进
        // 日志，启动期的日志次序会乱（2.17.3 修的是同一类问题的实锤：当时的订阅方
        // GszxApp 要应用「日志输出等级」，同步回调会让等级晚于这几条日志生效 ——
        // 那项配置已在 3.2.0 删除，但「回调不插队」这条约定照旧）。
        // post 一轮之后，调用方的 onCreate 已经跑完，回调与订阅方初始化次序稳定。
        mainHandler.post { runCatching { l.onServiceChanged(service) } }
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
