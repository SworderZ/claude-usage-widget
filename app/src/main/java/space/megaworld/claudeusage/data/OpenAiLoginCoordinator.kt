package space.megaworld.claudeusage.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

internal interface OpenAiLoginVault {
    suspend fun load(): PendingOpenAiLogin?
    suspend fun save(login: PendingOpenAiLogin)
    suspend fun clear()
}

internal fun CredentialStore.loginVault(): OpenAiLoginVault = object : OpenAiLoginVault {
    override suspend fun load() = loadPendingLogin()
    override suspend fun save(login: PendingOpenAiLogin) = savePendingLogin(login)
    override suspend fun clear() = clearPendingLogin()
}

internal data class OpenAiLoginState(
    val pending: PendingOpenAiLogin? = null,
    val restoring: Boolean = true,
    val starting: Boolean = false,
    val completing: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
) {
    val busy: Boolean get() = restoring || starting || completing || pending != null
}

/** Owns authentication independently of any Activity or ViewModel. Only explicit cancellation,
 * successful completion, or an expired/rejected attempt removes the encrypted pending record. */
internal class OpenAiLoginCoordinator(
    private val scope: CoroutineScope,
    private val api: OpenAiLoginApi,
    private val vault: OpenAiLoginVault,
    private val connect: suspend (Credentials) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val browserFactory: (Long) -> BrowserAuthorization = { BrowserAuthorization.create(it) },
) {
    private val mutableState = MutableStateFlow(OpenAiLoginState())
    val state: StateFlow<OpenAiLoginState> = mutableState.asStateFlow()
    private var operation: Job? = null
    @Volatile private var callbackServer: OpenAiCallbackServer? = null

    init { restore() }

    private fun restore() {
        if (operation?.isActive == true) return
        mutableState.value = mutableState.value.copy(restoring = true)
        operation = scope.launch {
            try {
                val pending = vault.load()
                if (pending == null || pending.expiresAtMillis <= now()) {
                    vault.clear()
                    mutableState.value = OpenAiLoginState(restoring = false)
                } else {
                    mutableState.value = OpenAiLoginState(pending = pending, restoring = false)
                    continueLogin(pending)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutableState.value = OpenAiLoginState(restoring = false,
                error = "Не удалось восстановить вход. Попробуйте ещё раз.") }
            finally { closeCallbackServer() }
        }
    }

    fun startBrowser() = start { PendingOpenAiLogin(browser = browserFactory(now())) }
    fun startDevice() = start { PendingOpenAiLogin(device = api.startDeviceLogin()) }

    private fun start(create: suspend () -> PendingOpenAiLogin) {
        if (state.value.busy) return
        mutableState.value = OpenAiLoginState(restoring = false, starting = true)
        operation = scope.launch {
            try {
                val pending = create()
                // Bind before the UI is allowed to launch the browser.
                pending.browser?.let { openCallbackServer(it) }
                vault.save(pending)
                mutableState.value = OpenAiLoginState(pending = pending, restoring = false)
                continueLogin(pending)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableState.value = OpenAiLoginState(restoring = false, error =
                    (e as? OpenAiAuthException)?.message ?: "Не удалось начать вход. Проверьте подключение или выберите вход по коду.")
            } finally { closeCallbackServer() }
        }
    }

    private suspend fun continueLogin(initial: PendingOpenAiLogin) {
        var pending = initial
        while (now() < pending.expiresAtMillis) {
            try {
                val credentials = pending.browser?.let { browser ->
                    if (browser.authorizationCode == null) {
                        val server = callbackServer ?: openCallbackServer(browser)
                        val code = server.awaitCode(now)
                        pending = PendingOpenAiLogin(browser = browser.copy(authorizationCode = code))
                        vault.save(pending)
                        mutableState.value = state.value.copy(pending = pending)
                        closeCallbackServer()
                    }
                    api.exchangeBrowserLogin(pending.browser!!)
                } ?: run {
                    delay(pending.device!!.intervalMillis)
                    when (val result = api.pollDeviceLogin(pending.device!!)) {
                        DevicePoll.Pending -> null
                        is DevicePoll.Authorized -> result.credentials
                    }
                }
                currentCoroutineContext().ensureActive()
                if (credentials != null) {
                    mutableState.value = state.value.copy(completing = true, error = null)
                    // Once the server has issued tokens, finish storing the independent session.
                    withContext(NonCancellable) { connect(credentials); vault.clear() }
                    mutableState.value = OpenAiLoginState(restoring = false, connected = true)
                    return
                }
                mutableState.value = state.value.copy(error = null)
            } catch (e: CancellationException) { throw e
            } catch (e: OpenAiAuthException) {
                if (e.terminal) {
                    vault.clear()
                    mutableState.value = OpenAiLoginState(restoring = false, error = e.message)
                    return
                }
                mutableState.value = state.value.copy(error = e.message, completing = false)
                delay(5000)
            } catch (e: IOException) {
                mutableState.value = state.value.copy(error = "Связь временно прервалась. Вход сохранён, повторяем подключение…", completing = false)
                delay(5000)
            } catch (e: Exception) {
                mutableState.value = state.value.copy(error = "Не удалось завершить вход. Попытка сохранена; повторяем подключение…", completing = false)
                delay(5000)
            }
        }
        vault.clear()
        mutableState.value = OpenAiLoginState(restoring = false, error = "Время входа закончилось. Начните вход заново.")
    }

    fun cancel() {
        if (state.value.completing) return
        val previous = operation
        previous?.cancel()
        closeCallbackServer()
        mutableState.value = state.value.copy(starting = true)
        operation = scope.launch {
            previous?.join()
            vault.clear()
            mutableState.value = OpenAiLoginState(restoring = false)
        }
    }

    fun acknowledgeConnection() {
        mutableState.value = state.value.copy(connected = false)
    }

    private suspend fun openCallbackServer(browser: BrowserAuthorization): OpenAiCallbackServer = withContext(Dispatchers.IO) {
        OpenAiCallbackServer(browser).also { callbackServer = it }
    }

    private fun closeCallbackServer() {
        callbackServer?.let { runCatching { it.close() } }
        callbackServer = null
    }
}
