package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 备份 / 恢复 / 导出日志这三个长任务的**忙标记与结果**。
 *
 * ## 为什么不住在 [SettingsTab] 里
 *
 * 原先三个标记用 `rememberSaveable` 声明在 `SettingsTab` 内，而 `SettingsTab` 挂在
 * `HomeScaffold` 的 `SaveableStateProvider(tab)` 下 —— 状态注册在**子** registry 里。
 *
 * Compose 的 `SaveableStateHolder` 在**内容离开组合的那一刻**（`onDispose`）
 * 对 registry 做一次 `performSave()` 快照；回到该板块时用这份**快照**新建 registry 还原。
 * 于是：
 *
 * - 切走板块时 `busy` 已经被快照成 `true`；
 * - 任务本身跑在 `hostScope`（挂在 `HomeScaffold` 上，切板块**不取消**）里，
 *   跑完后执行 `busy = false` / `actionResult = msg` ——
 *   但写的是**已被移出组合的旧 `MutableState` 对象**，快照不会更新；
 * - 切回来时还原的是那份旧快照 → `busy` 又是 `true` → **「备份」「恢复」两行永久置灰**，
 *   `actionResult` 又是空串 → **「已备份到 xxx」的结果永远不显示**。
 *
 * 用户只能杀掉 App 进程（或重装）才恢复得过来。导出日志更糟：它要等 root 授权
 * （20 秒超时窗口），**极易命中**切板块。
 *
 * ## 现在的做法
 *
 * 状态对象由 [HomeScaffold] 用 `remember` 持有（那一层不随板块切换 dispose），
 * 通过参数传给 [SettingsTab]。`remember` 的状态对象在**同一个组合作用域**内是同一个实例，
 * 协程写回时写的就是当前还在被观察的那份 —— 写回立即可见。
 *
 * 代价：转屏（Activity 重建）后状态归零。这**是对的** ——
 * `hostScope` 是 `rememberCoroutineScope()`，Activity 一销毁它就被取消，
 * 任务本来就没跑完，「正在备份…」的标记留着反而是骗人。
 */
internal class BackupUiState {

    /** 备份 / 恢复进行中（两者共用一个标记：它们都写同一个配置文件） */
    var busy by mutableStateOf(false)

    /** 备份 / 恢复的结果提示（空串 = 没有结果） */
    var actionResult by mutableStateOf("")

    /**
     * 导出日志进行中。
     *
     * 单独一个标记：它可能挂起几秒等 root 授权，与备份/恢复互不影响。
     */
    var exportingLog by mutableStateOf(false)
}
