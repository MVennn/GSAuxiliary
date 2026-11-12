package io.github.MVennn.GSAuxiliary.util

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 反射工具。
 *
 * 目标 App 被加固 + 混淆，类名 / 字段名 / 方法名都不能硬编码假设，
 * 所有关键名字都做成「候选数组 + 多级兜底」，靠这里统一处理继承链查找。
 *
 * ## 为什么要缓存
 *
 * 「找不到」这条路是靠 `getDeclaredField` 抛 `NoSuchFieldException` 再吞掉实现的，
 * 而调用点极热：`StrategyMergeTargets.pctOf` 收尾时对上千只股票逐个试 11 个候选字段名，
 * `field() / listField() / write()` 在 120ms 轮询和每行渲染里反复走 ——
 * 不缓存的话一轮收尾能构造上万次异常，卡在「取完最后一个成员 → 界面写回」这段
 * （`StrategyMergeTargets` 的注释里记着真机 ANR）。
 *
 * ## 为什么不用 `java.lang.ClassValue`
 *
 * 2026-11-12 真机实测：MuMu（Android 12）里 `java.lang.ClassValue` 根本不存在，
 * `ClassNotFoundException: Didn't find class "java.lang.ClassValue"` 会让
 * **20 个功能全部装不上**（LSPosed 日志「功能安装完成：0/20」）。所以这里退回
 * 「Class → 名字键 → 成员」的普通 ConcurrentHashMap。宿主换 classloader 之后
 * 旧 Class 会被这张表吊住，但这张表只存 Field/Method 引用，量级是「类个数 × 用到的名字数」，
 * 进程退出即释放，可以接受。
 */
object Reflect {

    /** 缓存里表示「这一层确实没有」的哨兵：ConcurrentHashMap 不能存 null */
    private val NO_HIT = Any()

    /** Class → (槽位前缀 + 名字 → Field/Method/List&lt;Method&gt;/NO_HIT) */
    private val cache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Any>>()

    private fun cacheOf(cls: Class<*>): ConcurrentHashMap<String, Any> {
        cache[cls]?.let { return it }
        val fresh = ConcurrentHashMap<String, Any>()
        val old = cache.putIfAbsent(cls, fresh)
        return old ?: fresh
    }

    /** 用宿主 classLoader 加载类；失败返回 null（App 升级后类名变了也不崩） */
    fun loadClass(name: String, cl: ClassLoader): Class<*>? =
        runCatching { Class.forName(name, false, cl) }.getOrNull()

    /** 沿继承链向上查找字段（兼容字段声明在父类的情况）；命中与未命中都缓存 */
    fun findField(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            val cls = c
            val slot = "f:$name"
            val m = cacheOf(cls)
            val hit = m[slot]
            if (hit != null) {
                if (hit !== NO_HIT) return hit as Field
            } else {
                val f = runCatching { cls.getDeclaredField(name) }.getOrNull()
                if (f != null) {
                    runCatching { f.isAccessible = true }
                    m[slot] = f
                    return f
                }
                m[slot] = NO_HIT
            }
            c = cls.superclass
        }
        return null
    }

    /** 按候选名依次取字段值，返回第一个非 null 的 */
    fun readField(target: Any, vararg candidates: String): Any? {
        for (name in candidates) {
            val f = findField(target.javaClass, name) ?: continue
            val v = runCatching { f.get(target) }.getOrNull()
            if (v != null) return v
        }
        return null
    }

    /** 沿继承链查找方法（先 declared 再 public，覆盖接口/父类实现）；命中与未命中都缓存 */
    fun findMethod(start: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        val slot = "m:" + methodKey(name, paramTypes)
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            val cls = c
            val m = cacheOf(cls)
            val hit = m[slot]
            if (hit != null) {
                if (hit !== NO_HIT) return hit as Method
            } else {
                val found = runCatching { cls.getDeclaredMethod(name, *paramTypes) }.getOrNull()
                    ?: runCatching { cls.getMethod(name, *paramTypes) }.getOrNull()
                if (found != null) {
                    runCatching { found.isAccessible = true }
                    m[slot] = found
                    return found
                }
                m[slot] = NO_HIT
            }
            c = cls.superclass
        }
        return null
    }

    /** 方法缓存的键：名字 + 形参类型（同名重载不能混） */
    private fun methodKey(name: String, paramTypes: Array<out Class<*>>): String {
        if (paramTypes.isEmpty()) return "$name()"
        val sb = StringBuilder(name).append('(')
        for (i in paramTypes.indices) {
            if (i > 0) sb.append(',')
            sb.append(paramTypes[i].name)
        }
        return sb.append(')').toString()
    }

    /**
     * 取某个类上所有「可挂」的同名方法。
     *
     * 现代 API 的 `hook(Executable)` 需要一个具体的 [Method]，
     * 不再有传统 `hookAllMethods(Class, String, ...)` 那种批量写法，
     * 所以这里自己枚举。抽象方法不能挂（LSPlant 会拒绝），过滤掉。
     */
    @Suppress("UNCHECKED_CAST")
    fun methodsNamed(cls: Class<*>, name: String): List<Method> {
        val slot = "n:$name"
        val m = cacheOf(cls)
        (m[slot] as? List<Method>)?.let { return it }
        val out = cls.declaredMethods
            .filter { it.name == name && !Modifier.isAbstract(it.modifiers) }
            .onEach { runCatching { it.isAccessible = true } }
        m[slot] = out
        return out
    }
}
