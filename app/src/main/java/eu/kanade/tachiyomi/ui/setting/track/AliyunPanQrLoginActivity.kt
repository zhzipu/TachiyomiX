package eu.kanade.tachiyomi.ui.setting.track

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.sync.AliyunPanQrLoginScreen
import eu.kanade.tachiyomi.data.sync.service.AliyunPanService
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * QR-code login for Aliyun Pan: shows a QR code that the user scans with the
 * Aliyun Drive mobile app. The obtained refresh token is stored in [SyncPreferences].
 */
class AliyunPanQrLoginActivity : BaseActivity() {

    private val syncPreferences: SyncPreferences = Injekt.get()

    private val aliyunPanService: AliyunPanService = Injekt.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setComposeContent {
            AliyunPanQrLoginScreen(
                onUp = { finish() },
                onLoginSuccess = { refreshToken ->
                    syncPreferences.aliyunPanRefreshToken.set(refreshToken)
                    // Force a fresh access token for the newly logged-in account.
                    syncPreferences.aliyunPanAccessToken.set("")
                    syncPreferences.aliyunPanTokenExpireTime.set(0L)
                    aliyunPanService.resetSession()
                    toast(SYMR.strings.aliyun_pan_login_success, Toast.LENGTH_LONG)
                    setResult(RESULT_OK)
                    finish()
                },
            )
        }
    }

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, AliyunPanQrLoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}
