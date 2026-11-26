package dev.lework.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForwarderDiagnosticTest {
    private val prefix = "LEWORK_FWD_DIAG "
    private val stage = "port=65535 event=dial.other ms=0"
    private val snapshot = "port=1234 event=dial.snapshot status_age_ms=12400 backend=Running peer=found " +
        "handshake=recent path_hint=relay_region have_v4=true have_v6=unknown"

    @Test
    fun acceptsOnlyTheWireVocabulary() {
        for (event in listOf("preamble.accept", "preamble.reject", "dial.start", "dial.ok", "dial.deadline", "dial.refused", "dial.other")) {
            val body = "port=1 event=$event ms=12001"
            assertEquals(body, forwarderDiagnosticMessage(prefix + body))
        }
        assertEquals(stage, forwarderDiagnosticMessage(prefix + stage))
        assertEquals(snapshot, forwarderDiagnosticMessage(prefix + snapshot))
    }

    @Test
    fun neverForwardsArbitraryStderrOrContaminatedFields() {
        val secret = "Bearer secret-token owner@example.invalid http://private-host/ 100.64.0.20"
        for (bad in listOf(
            secret, stage, " $prefix$stage", "$prefix$stage $secret", "$prefix$stage\n$secret",
            "$prefix$stage\r", prefix + stage.replace("65535", "65536"), prefix + stage.replace("65535", "0"),
            prefix + stage.replace("dial.other", "dialXother"), prefix + stage.replace("ms=0", "ms=-1"),
            prefix + stage.replace("ms=0", "ms=12345678901234"), prefix + snapshot.replace("Running", secret),
            prefix + snapshot.replace("found", "private-host"), prefix + snapshot.replace("recent", "12345"),
            prefix + snapshot.replace("relay_region", "100.64.0.20"), prefix + snapshot.replace("true", "secret-token"),
        )) {
            assertNull(forwarderDiagnosticMessage(bad))
        }
    }
}
