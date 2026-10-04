package space.megaworld.claudeusage.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import space.megaworld.claudeusage.data.*
import java.io.IOException

/** Exercises the production Activity, ViewModel, foreground service and encrypted pending store.
 * Only the remote account API is replaced. Never packaged in a release build. */
class OpenAiLoginLifecyclePreviewActivity : OpenAiLoginActivity() {
    override fun loginViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OpenAiLoginViewModel(application, LoginLifecycleFixture.get(application, intent.getBooleanExtra("reset", false))) as T
    }
    override fun startLoginKeeper() {
        ContextCompat.startForegroundService(this, Intent(this, OpenAiLoginLifecyclePreviewService::class.java))
    }
    override fun openLoginBrowser(url: String) {
        // An external Activity reproduces leaving the app without signing in to a real account.
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

class OpenAiLoginLifecyclePreviewService : OpenAiLoginService() {
    internal override fun loginCoordinator() = LoginLifecycleFixture.get(application)
}

private object LoginLifecycleFixture {
    private var coordinator: OpenAiLoginCoordinator? = null
    fun get(application: Application, reset: Boolean = false): OpenAiLoginCoordinator = coordinator ?: run {
        val prefs = application.getSharedPreferences("login_lifecycle_fixture", 0)
        val store = CredentialStore(application, "login_lifecycle_fixture")
        var needsReset = reset
        val vault = object : OpenAiLoginVault {
            override suspend fun load(): PendingOpenAiLogin? {
                if (needsReset) {
                    needsReset = false
                    prefs.edit().clear().commit()
                    store.clearPendingLogin()
                }
                return store.loadPendingLogin()
            }
            override suspend fun save(login: PendingOpenAiLogin) = store.savePendingLogin(login)
            override suspend fun clear() = store.clearPendingLogin()
        }
        val api = object : OpenAiLoginApi {
            override suspend fun startDeviceLogin(): DeviceAuthorization {
                val count = prefs.getInt("starts", 0) + 1
                prefs.edit().putInt("starts", count).commit()
                return DeviceAuthorization("fixture-device-$count", "LIFE-000$count", 1000, System.currentTimeMillis() + 900_000)
            }
            override suspend fun pollDeviceLogin(session: DeviceAuthorization): DevicePoll = throw IOException("Simulated connection interruption")
            override suspend fun exchangeBrowserLogin(session: BrowserAuthorization): Credentials {
                check(session.authorizationCode == "fixture-approved-code")
                return Credentials("fixture-access", "fixture-account", 0, "fixture-refresh")
            }
        }
        OpenAiLoginCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), api, vault,
            connect = { prefs.edit().putBoolean("connected", true).commit() },
            browserFactory = { BrowserAuthorization.create(it).copy(state = "fixture-state") }
        ).also { coordinator = it }
    }
}
