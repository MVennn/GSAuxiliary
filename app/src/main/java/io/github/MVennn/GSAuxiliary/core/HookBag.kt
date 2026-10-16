package io.github.MVennn.GSAuxiliary.core

import io.github.libxposed.api.XposedInterface
import java.util.Collections

/**
 * 一次安装产生的钩子句柄集合。
 *
 * 现代 API 里 [XposedInterface.hook] 返回 [XposedInterface.HookHandle]，
 * 卸载钩子必须持有句柄 —— 这是相比传统 `XposedBridge` 最大的行为差异之一
 * （旧 API 挂上去就没法摘）。
 *
 * 把句柄集中到 bag 里，热重载 / 关闭功能 / 进程退出时一次性卸载。
 */
class HookBag {

    private val handles = Collections.synchronizedList(mutableListOf<XposedInterface.HookHandle>())

    /** 注册一个句柄，热重载 / 关闭功能时统一卸载 */
    fun add(handle: XposedInterface.HookHandle) {
        handles.add(handle)
    }

    val size: Int get() = handles.size

    /** 幂等：重复调用安全（[XposedInterface.HookHandle.unhook] 本身幂等） */
    fun unhookAll() {
        val snapshot = synchronized(handles) { handles.toList() }
        handles.clear()
        for (h in snapshot) {
            runCatching { h.unhook() }
                .onFailure { GLog.w("卸载钩子失败: ${h.executable}", it) }
        }
    }
}
