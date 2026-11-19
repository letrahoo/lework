package dev.lework

import dev.lework.protocol.*
import dev.lework.store.ChatTimelineItem
import dev.lework.store.SessionTranscript
import dev.lework.store.reduceTranscript
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolConformanceTest {
    @Test
    fun decodesAndReducesAllEventVariantsAndApprovalFlow() {
        val sampleJson = """
            [
              { "seq": 0, "sessionId": "s_1", "ts": 1700000000000, "type": "session.state", "state": "running" },
              { "seq": 1, "sessionId": "s_1", "ts": 1700000000100, "type": "message.user", "messageId": "m_u1", "text": "Run git status" },
              { "seq": 2, "sessionId": "s_1", "ts": 1700000000200, "type": "reasoning.delta", "messageId": "m_r1", "text": "Checking repo state..." },
              { "seq": 3, "sessionId": "s_1", "ts": 1700000000300, "type": "tool.call", "toolCallId": "tc_1", "title": "Bash: git status", "kind": "execute", "status": "pending", "input": "git status" },
              {
                "seq": 4,
                "sessionId": "s_1",
                "ts": 1700000000400,
                "type": "permission.requested",
                "requestId": "perm_1",
                "toolCallId": "tc_1",
                "title": "Allow running `git status`?",
                "options": [
                  { "id": "allow", "name": "Allow once", "kind": "allow_once" },
                  { "id": "reject", "name": "Reject", "kind": "reject_once" }
                ]
              },
              {
                "seq": 5,
                "sessionId": "s_1",
                "ts": 1700000000500,
                "type": "permission.resolved",
                "requestId": "perm_1",
                "optionId": "allow",
                "outcome": "selected"
              },
              {
                "seq": 6,
                "sessionId": "s_1",
                "ts": 1700000000600,
                "type": "tool.update",
                "toolCallId": "tc_1",
                "status": "completed",
                "output": "On branch main\nnothing to commit"
              },
              { "seq": 7, "sessionId": "s_1", "ts": 1700000000700, "type": "message.assistant.delta", "messageId": "m_a1", "text": "Working tree is " },
              { "seq": 8, "sessionId": "s_1", "ts": 1700000000750, "type": "message.assistant.delta", "messageId": "m_a1", "text": "clean." },
              { "seq": 9, "sessionId": "s_1", "ts": 1700000000900, "type": "turn.done", "turnId": "t_1", "stopReason": "end_turn" }
            ]
        """.trimIndent()

        val events = ProtocolJson.decodeFromString(ListSerializer(AgentEvent.serializer()), sampleJson)
        assertEquals(10, events.size)

        var transcript = SessionTranscript()
        for (ev in events.take(5)) {
            transcript = reduceTranscript(transcript, ev)
        }
        assertEquals(1, transcript.pendingPermissions.size)
        assertEquals("perm_1", transcript.pendingPermissions.first().requestId)

        for (ev in events.drop(5)) {
            transcript = reduceTranscript(transcript, ev)
        }
        assertTrue(transcript.pendingPermissions.isEmpty())
        assertEquals(9, transcript.lastSeq)

        val assistant = transcript.items.filterIsInstance<ChatTimelineItem.Assistant>().single()
        assertEquals("Working tree is clean.", assistant.text)

        val tool = transcript.items.filterIsInstance<ChatTimelineItem.Tool>().single()
        assertEquals(ToolStatus.COMPLETED, tool.status)
        assertEquals("On branch main\nnothing to commit", tool.output)
    }

    @Test
    fun handlesTerminalPermissionPromptWithoutCollidingWithJsonlSeq() {
        var transcript = reduceTranscript(
            SessionTranscript(),
            AgentEvent.ToolCall(
                seq = 12,
                sessionId = "host:claude:demo",
                ts = 1700000001000,
                toolCallId = "tc_bash",
                title = "Bash(git status)",
                kind = ToolKind.EXECUTE,
                status = ToolStatus.IN_PROGRESS,
            ),
        )
        assertEquals(12, transcript.lastSeq)

        // Ephemeral terminal permission emitted at current `lastSeq` (12)
        transcript = reduceTranscript(
            transcript,
            AgentEvent.PermissionRequested(
                seq = 12,
                sessionId = "host:claude:demo",
                ts = 1700000001100,
                requestId = "tty_perm_abc123",
                title = "Bash command\ngit status",
                options = listOf(
                    PermissionOption("tty_key:1", "1. Yes", PermissionOptionKind.ALLOW_ONCE),
                    PermissionOption("tty_key:2", "2. No", PermissionOptionKind.REJECT_ONCE),
                ),
            ),
        )
        assertEquals(12, transcript.lastSeq)
        assertEquals(1, transcript.pendingPermissions.size)
        assertEquals("tty_perm_abc123", transcript.pendingPermissions.single().requestId)

        // Resolved on mobile or terminal -> clears banner while preserving `lastSeq == 12`
        transcript = reduceTranscript(
            transcript,
            AgentEvent.PermissionResolved(
                seq = 12,
                sessionId = "host:claude:demo",
                ts = 1700000001200,
                requestId = "tty_perm_abc123",
                optionId = "tty_key:1",
                outcome = "selected",
            ),
        )
        assertEquals(12, transcript.lastSeq)
        assertTrue(transcript.pendingPermissions.isEmpty())

        // Next .jsonl event (`seq = 13`) streams in without collision
        transcript = reduceTranscript(
            transcript,
            AgentEvent.ToolUpdate(
                seq = 13,
                sessionId = "host:claude:demo",
                ts = 1700000001300,
                toolCallId = "tc_bash",
                status = ToolStatus.COMPLETED,
                output = "clean",
            ),
        )
        assertEquals(13, transcript.lastSeq)
    }
}
