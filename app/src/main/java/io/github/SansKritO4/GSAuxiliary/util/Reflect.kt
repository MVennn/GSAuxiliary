package io.github.SansKritO4.GSAuxiliary.util

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 反射工具。
 *
 * 目标 App 被加固 + 混淆，类名 / 字段名 / 方法名都不能硬编码假设，
 * 所有关键名字都做成「候选数组 + 多级兜底」，靠这里统一处理继承链查找。
 */
object Reflect {

    /** 用宿主 classLoader 加载类；失败返回 null（App 升级后类名变了也不崩） */
    fun loadClass(name: String, cl: ClassLoader): Class<*>? =
        runCatching { Class.forName(name, false, cl) }.getOrNull()

    /** 沿继承链向上查找字段（兼容字段声明在父类的情况） */
    fun findField(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            runCatching { c.getDeclaredField(name) }.getOrNull()?.let {
                runCatching { it.isAccessible = true }
                return it
            }
            c = c.superclass
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

    /** 沿继承链查找方法（先 declared 再 public，覆盖接口/父类实现） */
    fun findMethod(start: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            runCatching { c.getDeclaredMethod(name, *paramTypes) }.getOrNull()?.let {
                runCatching { it.isAccessible = true }
                return it
            }
            runCatching { c.getMethod(name, *paramTypes) }.getOrNull()?.let {
                runCatching { it.isAccessible = true }
                return it
            }
            c = c.superclass
        }
        return null
    }

    /**
     * 取某个类上所有「可挂」的同名方法。
     *
     * 现代 API 的 `hook(Executable)` 需要一个具体的 [Method]，
     * 不再有传统 `hookAllMethods(Class, String, ...)` 那种批量写法，
     * 所以这里自己枚举。抽象方法不能挂（LSPlant 会拒绝），过滤掉。
     */
    fun methodsNamed(cls: Class<*>, name: String): List<Method> =
        cls.declaredMethods
            .filter { it.name == name && !Modifier.isAbstract(it.modifiers) }
            .onEach { runCatching { it.isAccessible = true } }
}
