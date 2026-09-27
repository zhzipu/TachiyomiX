package eu.kanade.tachiyomi.extension.util

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.hasMiuiPackageInstaller
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

/**
 * Activity used to install extensions, because we can only receive the result of the installation
 * with [startActivityForResult], which we need to update the UI.
 */
class ExtensionInstallActivity : Activity() {

    // MIUI package installer bug workaround
    private var ignoreUntil = 0L
    private var ignoreResult = false
    private var hasIgnoredResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(intent.data, intent.type)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // SY --> 指定了安装器（如 InstallerX Revived）时就只把意图发给它，不再让系统弹选择框
        val targetPackage = intent.getStringExtra(ExtensionInstaller.EXTRA_TARGET_PACKAGE)
        if (targetPackage != null) {
            installIntent.setPackage(targetPackage)
        }
        // SY <--

        // MIUI 安装器的结果回调 bug 只针对系统安装器，第三方安装器不需要这套规避逻辑
        if (hasMiuiPackageInstaller && targetPackage == null) {
            ignoreResult = true
            ignoreUntil = System.nanoTime() + 1.seconds.inWholeNanoseconds
        }

        try {
            startActivityForResult(installIntent, INSTALL_REQUEST_CODE)
        } catch (error: Exception) {
            // Either install package can't be found (probably bots) or there's a security exception
            // with the download manager. Nothing we can workaround.
            toast(error.message)
            // SY --> 起不来时把状态归位并关掉自己，否则插件会一直停在“安装中”
            checkInstallationResult(RESULT_CANCELED)
            finish()
            // SY <--
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (ignoreResult && System.nanoTime() < ignoreUntil) {
            hasIgnoredResult = true
            return
        }
        if (requestCode == INSTALL_REQUEST_CODE) {
            checkInstallationResult(resultCode)
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        if (hasIgnoredResult) {
            checkInstallationResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        intent.data?.let { contentResolver.delete(it, null, null) }
    }

    private fun checkInstallationResult(resultCode: Int) {
        val downloadId = intent.extras!!.getLong(ExtensionInstaller.EXTRA_DOWNLOAD_ID)
        val extensionManager = Injekt.get<ExtensionManager>()
        val newStep = when (resultCode) {
            RESULT_OK -> InstallStep.Installed
            RESULT_CANCELED -> InstallStep.Idle
            else -> InstallStep.Error
        }
        extensionManager.updateInstallStep(downloadId, newStep)
    }
}

private const val INSTALL_REQUEST_CODE = 500
