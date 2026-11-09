package mihon.core.firebase

import android.content.Context

/**
 * Debug 构建的空实现。
 *
 * 本工程已移除 Firebase（`release` / `foss` / `benchmark` 三个 build type 下均为同样内容的空实现），
 * 但 `debug` 源集在初始导入时漏掉了这个文件，导致 `assembleDebug` 编译不过
 * （`App.kt` 无条件引用 `mihon.core.firebase.FirebaseConfig`）。
 * 这里补齐，保持与其它 build type 一致。
 */
object FirebaseConfig {
    fun init(context: Context) = Unit

    fun setAnalyticsEnabled(enabled: Boolean) = Unit

    fun setCrashlyticsEnabled(enabled: Boolean) = Unit
}
