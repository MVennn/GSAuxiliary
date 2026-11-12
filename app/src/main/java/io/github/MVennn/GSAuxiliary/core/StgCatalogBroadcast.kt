package io.github.MVennn.GSAuxiliary.core

/**
 * 「宿主策略清单」广播协议：宿主进程 → 模块 App。
 *
 * ## 为什么非得用广播
 *
 * 「从金太阳导入」要列出宿主「我的策略」里的那些策略，可这份数据只在目标进程内存
 * （[io.github.MVennn.GSAuxiliary.feature.stgmerge.StrategyStgIndex]）和宿主的私有
 * SharedPreferences 里 —— 两个进程是不同 UID，模块 App 直接读读不到；
 * LSPosed 的 remote preferences 在目标进程侧又是**只读**的（见 core/ConfigSource 的说明），
 * 宿主写不回去。剩下唯一一条通道就是：宿主进程自己 `sendBroadcast` 到本模块包名。
 *
 * 模块侧用清单里声明的接收器（app/HostStgCatalogReceiver）收下，落进自己的本地
 * SharedPreferences，编辑页再从那里读 —— 于是「宿主读到了什么」和「设置页显示什么」
 * 是两份数据，中间靠这条广播同步。
 *
 * ## 载荷
 *
 * `payload` = `{"v":1,"at":<毫秒时间戳>,"items":[{"id":"<stId>","name":"<策略名>","tags":[...]}]}`
 *
 * `sig` 只是一个固定串：广播是 exported 的，加一层过滤，免得别的 App 随手构造一个同名
 * intent 就把设置页的策略清单换掉。它不是安全边界 —— 这个常量谁都能读到。
 */
object StgCatalogBroadcast {

    /** 宿主 → 模块 App：宿主里读到的策略清单 */
    const val ACTION_CATALOG = "io.github.MVennn.GSAuxiliary.action.HOST_STG_CATALOG"

    /** 模块 App 的包名（宿主发广播时 setPackage 用；隐式广播在 8.0 之后收不到，必须点名） */
    const val MODULE_PACKAGE = "io.github.MVennn.GSAuxiliary"

    const val EXTRA_PAYLOAD = "payload"
    const val EXTRA_SIG = "sig"

    /** 见类注释：只是防误触的固定串，不是权限 */
    const val SIG = "gsaux-stg-catalog-1"

    /** payload 结构版本：解析端只认这个值，字段改了要一起改 */
    const val VERSION = 1
}
