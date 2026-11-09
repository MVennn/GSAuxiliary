package io.github.MVennn.GSAuxiliary.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 覆盖安装（应用更新）后重新应用「桌面图标」偏好。
 *
 * [Intent.ACTION_MY_PACKAGE_REPLACED] 只发给被替换的本包，属于 Android 8.0
 * （API 26）隐式广播限制里「sent specifically to them」的豁免场景，可静态注册；
 * 本仓 minSdk = 27，落在豁免范围内。receiver 在 manifest 里声明
 * android:exported="false"，只接收系统发给本包的这一次广播。
 *
 * 覆盖安装会清掉 setComponentEnabledSetting 的组件启用记录，alias 回落到
 * manifest 默认（false）。收到广播后按本地镜像重放：用户关过图标则保持隐藏，
 * 开过则显式恢复。Application.onCreate 里的 applyFromLocal 是双保险，
 * 兜住某些 ROM 没投递本广播、但用户/系统之后又拉起模块 App 的情况。
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        LauncherIcon.applyFromLocal(context)
    }
}
