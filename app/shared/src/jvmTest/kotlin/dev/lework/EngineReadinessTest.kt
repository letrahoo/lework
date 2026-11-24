package dev.lework

import dev.lework.protocol.*
import dev.lework.store.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineReadinessTest {
    private fun engine(id: String, installed: Boolean, ready: Boolean, auth: String? = null) = EngineInfo(
        id, id, EngineKind.CLI, EngineStatus(installed, ready, authStatus = auth),
        EngineCapabilities(false, false, false, false, false, false),
    )

    @Test
    fun taskOptionsBelongToCurrentConnectionAndNeverFallbackToMissingOrMock() {
        val missing = engine("claude", false, false)
        val required = engine("codex", true, false, "required")
        val unknown = engine("agy", true, false, "unknown")
        val mock = engine("mock", true, true)
        val state = ConsoleUiState(connectionStatus = ConnectionStatus.CONNECTED, engines = listOf(missing, required, unknown, mock))
        assertEquals(listOf(required, unknown), state.taskEngines)
        assertFalse(required.canStartTask)
        assertTrue(unknown.canStartTask)
        assertEquals(unknown, state.taskEngines.draftEngine("claude"))
        assertNull(listOf(missing, required, mock).draftEngine("mock"))
        assertTrue(state.copy(connectionStatus = ConnectionStatus.RECONNECTING).taskEngines.isEmpty())
        assertEquals(4, state.engines.size) // Full inventory remains available for history filters.
    }

    @Test
    fun nativeLoginAndProxyRoutesAreIndependent() {
        val proxy = EngineModelSpec("proxy-model", "Proxy model", "cpa_local")
        val native = engine("claude", true, true, "authenticated").copy(models = listOf(proxy))
        assertNull(native.draftDefaultModel()) // Native CLI uses its own default, not an implicit proxy.
        val proxyOnly = native.copy(status = EngineStatus(true, true, authStatus = "required"))
        assertTrue(proxyOnly.canStartTask)
        assertEquals(proxy, proxyOnly.draftDefaultModel())
    }
}
