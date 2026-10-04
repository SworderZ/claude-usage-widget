package space.megaworld.claudeusage

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import space.megaworld.claudeusage.data.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiLoginCoordinatorTest {
    private class Vault : OpenAiLoginVault {
        var saved: String? = null
        override suspend fun load() = saved?.let { Json.decodeFromString<PendingOpenAiLogin>(it) }
        override suspend fun save(login: PendingOpenAiLogin) { saved = Json.encodeToString(PendingOpenAiLogin.serializer(), login) }
        override suspend fun clear() { saved = null }
    }
    private class Api : OpenAiLoginApi {
        var starts = 0
        var polls = 0
        var result: DevicePoll = DevicePoll.Pending
        var failure: IOException? = null
        override suspend fun startDeviceLogin(): DeviceAuthorization {
            starts++
            return DeviceAuthorization("device-$starts", "CODE-$starts", 1000, 900_000)
        }
        override suspend fun pollDeviceLogin(session: DeviceAuthorization): DevicePoll {
            polls++
            failure?.let { throw it }
            return result
        }
        override suspend fun exchangeBrowserLogin(session: BrowserAuthorization): Credentials = error("Not a browser test")
    }

    @Test fun `network interruption keeps the same code and eventually connects`() = runTest {
        val api = Api()
        val vault = Vault()
        var connected: Credentials? = null
        val coordinator = OpenAiLoginCoordinator(backgroundScope, api, vault, { connected = it }, { testScheduler.currentTime })
        runCurrent()
        coordinator.startDevice()
        runCurrent()
        val original = coordinator.state.value.pending
        api.failure = IOException("Offline")
        advanceTimeBy(1000); runCurrent()
        assertEquals(original, coordinator.state.value.pending)
        assertEquals(original, vault.load())
        assertEquals(1, api.starts)
        assertTrue(coordinator.state.value.error!!.contains("сохранён"))
        api.failure = null
        api.result = DevicePoll.Authorized(Credentials("access", "account", 0, "refresh"))
        advanceTimeBy(6000); runCurrent()
        assertEquals("refresh", connected!!.refreshToken)
        assertTrue(coordinator.state.value.connected)
        assertNull(vault.load())
    }

    @Test fun `a new process restores its stored attempt without requesting a new code`() = runTest {
        val vault = Vault()
        val api = Api()
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = OpenAiLoginCoordinator(firstScope, api, vault, {}, { testScheduler.currentTime })
        runCurrent(); first.startDevice(); runCurrent()
        val original = first.state.value.pending
        firstScope.cancel(); runCurrent()
        val second = OpenAiLoginCoordinator(backgroundScope, api, vault, {}, { testScheduler.currentTime })
        runCurrent()
        assertEquals(original, second.state.value.pending)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, api.starts)
        assertEquals(1, api.polls)
        assertEquals(original, vault.load())
    }

    @Test fun `only explicit cancellation clears a pending attempt and permits a fresh code`() = runTest {
        val api = Api()
        val vault = Vault()
        val coordinator = OpenAiLoginCoordinator(backgroundScope, api, vault, {}, { testScheduler.currentTime })
        runCurrent(); coordinator.startDevice(); runCurrent()
        coordinator.startDevice(); runCurrent()
        assertEquals(1, api.starts)
        coordinator.cancel(); runCurrent()
        assertNull(vault.load())
        assertFalse(coordinator.state.value.busy)
        coordinator.startDevice(); runCurrent()
        assertEquals("CODE-2", coordinator.state.value.pending!!.device!!.userCode)
    }

    @Test fun `expired saved code is discarded without a network request`() = runTest {
        val api = Api()
        val vault = Vault()
        vault.save(PendingOpenAiLogin(device = DeviceAuthorization("old", "old", 1000, 100)))
        val coordinator = OpenAiLoginCoordinator(backgroundScope, api, vault, {}, { 100 })
        runCurrent()
        assertNull(vault.load())
        assertFalse(coordinator.state.value.busy)
        assertEquals(0, api.starts + api.polls)
    }

    @Test fun `rejected attempt ends instead of repeatedly polling an invalid code`() = runTest {
        val api = Api().apply { failure = OpenAiAuthException("Expired", terminal = true) }
        val vault = Vault()
        val coordinator = OpenAiLoginCoordinator(backgroundScope, api, vault, {}, { testScheduler.currentTime })
        runCurrent(); coordinator.startDevice(); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertFalse(coordinator.state.value.busy)
        assertNull(vault.load())
        assertEquals("Expired", coordinator.state.value.error)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, api.polls)
    }

    @Test fun `an approved browser grant restores and retries without creating another attempt`() = runTest {
        val vault = Vault()
        val approved = BrowserAuthorization.create(0).copy(authorizationCode = "saved-grant")
        vault.save(PendingOpenAiLogin(browser = approved))
        var requests = 0
        var saved: Credentials? = null
        val api = object : OpenAiLoginApi {
            override suspend fun startDeviceLogin(): DeviceAuthorization = error("New login requested")
            override suspend fun pollDeviceLogin(session: DeviceAuthorization): DevicePoll = error("Wrong flow")
            override suspend fun exchangeBrowserLogin(session: BrowserAuthorization): Credentials {
                assertEquals(approved, session)
                if (++requests == 1) throw IOException("Offline")
                return Credentials("access", "account", 0, "refresh")
            }
        }
        val coordinator = OpenAiLoginCoordinator(backgroundScope, api, vault, { saved = it }, { testScheduler.currentTime })
        runCurrent()
        assertEquals(approved, coordinator.state.value.pending!!.browser)
        assertEquals(approved, vault.load()!!.browser)
        advanceTimeBy(5000); runCurrent()
        assertEquals(2, requests)
        assertEquals("refresh", saved!!.refreshToken)
        assertNull(vault.load())
        assertTrue(coordinator.state.value.connected)
    }
}
