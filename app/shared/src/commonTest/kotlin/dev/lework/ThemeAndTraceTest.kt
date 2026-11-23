package dev.lework

import androidx.compose.ui.graphics.Color
import dev.lework.protocol.ToolKind
import dev.lework.protocol.ToolStatus
import dev.lework.store.ChatTimelineItem
import dev.lework.ui.CondensedStep
import dev.lework.ui.Everforest
import dev.lework.ui.MdBlock
import dev.lework.ui.MdTableAlign
import dev.lework.ui.condenseTraceSteps
import dev.lework.ui.highlightEverforestCode
import dev.lework.ui.parseCliTool
import dev.lework.ui.parseEverforestInline
import dev.lework.ui.parseMarkdownBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeAndTraceTest {

    @Test
    fun `T7_1 enforces low-contrast Shell-aligned Everforest palette with zero bright yellow or green glare`() {
        val allowedInlineColors = setOf(
            Color.Unspecified,
            Everforest.Fg,
            Everforest.BoldFg,
            Everforest.HeadingWarm,
            Everforest.CommentGrey,
            Everforest.InlineCode,
        )

        val inlineSample = """
            普通正文保持沉静米白，包含 **核心重点结论** 与 `inline_code()`，
            还有链接 [文档说明](https://example.com/docs) 与裸链接 https://example.org/test ，
            以及 ~~废弃方案~~ 和 *斜体补充*。
        """.trimIndent()

        val parsedInline = parseEverforestInline(inlineSample)
        val usedInlineColors = parsedInline.spanStyles.map { it.item.color }.toSet()
        for (c in usedInlineColors) {
            assertTrue(
                c in allowedInlineColors,
                "Unexpected loud inline color detected: $c",
            )
        }
        // Bold MUST use subtle warm-white BoldFg (#E5DCC8, NOT loud yellow BoldGold),
        // and Inline code MUST use muted slate-blue InlineCode (#9AB0C1, NOT green CodeGreen)!
        assertTrue(parsedInline.spanStyles.any { it.item.color == Everforest.BoldFg })
        assertTrue(parsedInline.spanStyles.any { it.item.color == Everforest.InlineCode })
        assertFalse(parsedInline.spanStyles.any { it.item.color == Everforest.BoldGold })
        assertFalse(parsedInline.spanStyles.any { it.item.color == Everforest.CodeGreen })
        assertFalse(parsedInline.spanStyles.any { it.item.color == Everforest.Purple })
        assertFalse(parsedInline.spanStyles.any { it.item.color == Everforest.Red })

        // Shell code: low-contrast SoftSand + SoftSage, zero bright yellow/green/red/purple noise
        val shellCode = """
            # Deploy service
            FOO=1 launchctl kickstart -k "gui/501/dev.lework.hub" | grep ok
        """.trimIndent()
        val highlightedShell = highlightEverforestCode(shellCode, "zsh")
        val allowedCodeColors = setOf(
            Color.Unspecified,
            Everforest.Fg,
            Everforest.Grey2,
            Everforest.CommentGrey,
            Everforest.SoftSand,
            Everforest.SoftSage,
        )
        for (span in highlightedShell.spanStyles) {
            assertTrue(
                span.item.color in allowedCodeColors,
                "Unexpected noisy shell syntax color: ${span.item.color}",
            )
        }

        // General Kotlin/TS code: numbers, types, operators stay calm; only keywords/strings/comments gently accented
        val kotlinCode = """
            // Check version
            suspend fun verify(count: Int = 42): Boolean {
                val label = "lework"
                return count > 0 && true
            }
        """.trimIndent()
        val highlightedKotlin = highlightEverforestCode(kotlinCode, "kotlin")
        for (span in highlightedKotlin.spanStyles) {
            assertTrue(
                span.item.color in allowedCodeColors,
                "Unexpected noisy code syntax color: ${span.item.color}",
            )
        }

        // Diff deletions/insertions use softened SoftRed / SoftSage
        val diffCode = """
            @@ -1,2 +1,2 @@
            -old broken line
            +new clean line
        """.trimIndent()
        val highlightedDiff = highlightEverforestCode(diffCode, "diff")
        assertTrue(highlightedDiff.spanStyles.any { it.item.color == Everforest.SoftRed })
        assertTrue(highlightedDiff.spanStyles.any { it.item.color == Everforest.SoftSage })
    }

    @Test
    fun `T7_2 condenses consecutive thoughts and clusters quiet read-only tools while keeping Bash and Skill distinct`() {
        val steps = listOf(
            ChatTimelineItem.Reasoning(id = "r1", text = "先梳理全局架构"),
            ChatTimelineItem.Reasoning(id = "r2", text = "再定位具体文件"),
            ChatTimelineItem.Tool(
                id = "t_read_1",
                title = "Read(server.ts)",
                kind = ToolKind.READ,
                status = ToolStatus.COMPLETED,
                input = """{"path":"hub/src/server.ts"}""",
            ),
            ChatTimelineItem.Tool(
                id = "t_grep_2",
                title = "Grep(queuedPrompts)",
                kind = ToolKind.SEARCH,
                status = ToolStatus.COMPLETED,
                input = """{"pattern":"queuedPrompts"}""",
            ),
            ChatTimelineItem.Tool(
                id = "t_skill_3",
                title = "Skill(cloud-docs)",
                kind = ToolKind.OTHER,
                status = ToolStatus.COMPLETED,
                input = """{"skill":"cloud-docs"}""",
            ),
            ChatTimelineItem.Tool(
                id = "t_bash_4",
                title = "Bash(pnpm test)",
                kind = ToolKind.EXECUTE,
                status = ToolStatus.COMPLETED,
                input = """{"command":"pnpm test","description":"Run Hub unit tests"}""",
                output = "15 passed",
            ),
        )

        val condensed = condenseTraceSteps(steps)
        assertEquals(4, condensed.size)
        assertTrue(condensed[0] is CondensedStep.Thought)
        assertTrue((condensed[0] as CondensedStep.Thought).combinedText.contains("先梳理全局架构"))
        assertTrue((condensed[0] as CondensedStep.Thought).combinedText.contains("再定位具体文件"))

        assertTrue(condensed[1] is CondensedStep.ReadCluster)
        assertEquals(2, (condensed[1] as CondensedStep.ReadCluster).entries.size)

        assertTrue(condensed[2] is CondensedStep.SingleTool)
        assertTrue(condensed[3] is CondensedStep.SingleTool)
    }

    @Test
    fun `T7_3 synthesizes unified diff and line counts for Edit and Write tool calls`() {
        val editTool = ChatTimelineItem.Tool(
            id = "t_edit_1",
            title = "Update",
            kind = ToolKind.EDIT,
            status = ToolStatus.COMPLETED,
            input = """{"file_path":"/repo/src/server.ts","old_string":"const port = 8080\nstart(port)","new_string":"const port = 64450\nstart(port)"}""",
        )
        val parsed = parseCliTool(editTool)
        assertEquals("+1 / -1", parsed.description)
        assertTrue(parsed.conciseLabel.contains("src/server.ts (+1 / -1)"))
        val detail = parsed.fullCommandOrDetail.orEmpty()
        assertTrue(detail.contains("--- a/src/server.ts"))
        assertTrue(detail.contains("-const port = 8080"))
        assertTrue(detail.contains("+const port = 64450"))
        assertTrue(detail.contains(" start(port)"))
    }

    @Test
    fun `T7_4 parses GFM tables with pipes inside code spans, escaped pipes, short separators, and br tags`() {
        val md = """
            | # | 命令示例 | 耗时 |
            | - | :---: | --: |
            | 1 | `hub tokens [list | revoke]` | 12ms |
            | 2 | 支持 a \| b 转义<br/>支持多行 | 4ms |

            阶段 | 状态
            -- | --
            M1 | 已完成
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertEquals(2, blocks.size)

        val t1 = blocks[0] as MdBlock.Table
        assertEquals(listOf("#", "命令示例", "耗时"), t1.headers)
        assertEquals(listOf(MdTableAlign.START, MdTableAlign.CENTER, MdTableAlign.END), t1.alignments)
        assertEquals(2, t1.rows.size)
        assertEquals("`hub tokens [list | revoke]`", t1.rows[0][1])
        assertEquals("支持 a | b 转义\n支持多行", t1.rows[1][1])

        val t2 = blocks[1] as MdBlock.Table
        assertEquals(listOf("阶段", "状态"), t2.headers)
        assertEquals(listOf("M1", "已完成"), t2.rows[0])
    }

    @Test
    fun `T7_5 reduceTranscriptBatch matches sequential reduceTranscript and memoizes parseCliTool`() {
        val sid = "host:agy:batch_perf"
        val events = listOf(
            dev.lework.protocol.AgentEvent.UserMessage(0, sid, 1L, "u1", "优化手机渲染性能"),
            dev.lework.protocol.AgentEvent.ReasoningDelta(1, sid, 2L, "r1", "分析首屏瓶颈 "),
            dev.lework.protocol.AgentEvent.ReasoningDelta(2, sid, 3L, "r1", "定位 Markdown 虚拟化"),
            dev.lework.protocol.AgentEvent.ToolCall(
                3,
                sid,
                4L,
                "tc1",
                "Bash",
                ToolKind.EXECUTE,
                ToolStatus.IN_PROGRESS,
                """{"command":"./gradlew test","description":"Run KMP tests"}""",
            ),
            dev.lework.protocol.AgentEvent.ToolUpdate(4, sid, 5L, "tc1", status = ToolStatus.COMPLETED, output = "BUILD SUCCESSFUL"),
            dev.lework.protocol.AgentEvent.AssistantDelta(5, sid, 6L, "a1", "已完成 **分块虚拟化** "),
            dev.lework.protocol.AgentEvent.AssistantDelta(6, sid, 7L, "a1", "与 `Release` 编译优化。"),
            dev.lework.protocol.AgentEvent.Usage(7, sid, 8L, inputTokens = 1200, outputTokens = 340),
        )

        val seqResult = events.fold(dev.lework.store.SessionTranscript()) { acc, ev ->
            dev.lework.store.reduceTranscript(acc, ev)
        }
        val batchResult = dev.lework.store.reduceTranscriptBatch(dev.lework.store.SessionTranscript(), events)
        assertEquals(seqResult, batchResult)

        val toolItem = batchResult.items.filterIsInstance<ChatTimelineItem.Tool>().single()
        val firstParsed = parseCliTool(toolItem)
        val secondParsed = parseCliTool(toolItem)
        assertTrue(firstParsed === secondParsed, "Expected parseCliTool to return memoized instance on identical tool state")
    }

    @Test
    fun `T7_6 transcript preserves send and reply timestamps and formats local time cleanly`() {
        val sid = "host:agy:ts_verify"
        val sendTs = 1_795_420_857_000L
        val replyStartTs = 1_795_420_870_000L
        val replyEndTs = 1_795_420_889_000L
        val events = listOf(
            dev.lework.protocol.AgentEvent.UserMessage(0, sid, sendTs, "u1", "查询发布状态"),
            dev.lework.protocol.AgentEvent.AssistantDelta(1, sid, replyStartTs, "a1", "正在检查 "),
            dev.lework.protocol.AgentEvent.AssistantDelta(2, sid, replyEndTs, "a1", "已完成校验。"),
        )

        val seqResult = events.fold(dev.lework.store.SessionTranscript()) { acc, ev ->
            dev.lework.store.reduceTranscript(acc, ev)
        }
        val batchResult = dev.lework.store.reduceTranscriptBatch(dev.lework.store.SessionTranscript(), events)
        assertEquals(seqResult, batchResult)

        val userItem = batchResult.items.filterIsInstance<ChatTimelineItem.User>().single()
        val assistantItem = batchResult.items.filterIsInstance<ChatTimelineItem.Assistant>().single()
        assertEquals(sendTs, userItem.ts)
        assertEquals(replyEndTs, assistantItem.ts)

        // Same day -> HH:mm:ss (8 chars)
        val sameDayLabel = dev.lework.ui.formatMessageTimestamp(sendTs, nowMs = replyEndTs)
        assertTrue(Regex("^\\d{2}:\\d{2}:\\d{2}$").matches(sameDayLabel), "Expected HH:mm:ss for same day, got: $sameDayLabel")

        // Same year earlier day -> MM-dd HH:mm:ss
        val twoDaysEarlier = sendTs - 2L * 86_400_000L
        val earlierDayLabel = dev.lework.ui.formatMessageTimestamp(twoDaysEarlier, nowMs = sendTs)
        assertTrue(Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$").matches(earlierDayLabel), "Expected MM-dd HH:mm:ss, got: $earlierDayLabel")

        // Cross year -> yyyy-MM-dd HH:mm
        val twoYearsEarlier = sendTs - 730L * 86_400_000L
        val crossYearLabel = dev.lework.ui.formatMessageTimestamp(twoYearsEarlier, nowMs = sendTs)
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$").matches(crossYearLabel), "Expected yyyy-MM-dd HH:mm, got: $crossYearLabel")

        // Epoch seconds normalization & sentinel guard
        assertEquals(sameDayLabel, dev.lework.ui.formatMessageTimestamp(sendTs / 1000L, nowMs = replyEndTs))
        assertEquals("", dev.lework.ui.formatMessageTimestamp(0L, nowMs = replyEndTs))
        assertEquals("", dev.lework.ui.formatMessageTimestamp(1_000L, nowMs = replyEndTs))
    }

    @Test
    fun `T7_7 makes inline-code URLs clickable and trims CJK and trailing punctuation from bare URLs`() {
        val sample = """
            - **PR #1**：`https://github.com/letrahoo/lework/pull/1`（`fix/ssh-forge-host-filter`）
            已发布：https://github.com/letrahoo/lework/releases/tag/v0.7.1。
            参考 (https://en.wikipedia.org/wiki/Foo_(bar))，以及 [文档](https://example.com/a_(b)).
            非链接代码 `pnpm verify` 与占位 `https://...` 不应挂链接。
        """.trimIndent()

        val parsed = parseEverforestInline(sample)
        val links = parsed.getLinkAnnotations(0, parsed.length)
            .mapNotNull { (it.item as? androidx.compose.ui.text.LinkAnnotation.Clickable)?.tag }

        assertEquals(
            listOf(
                "https://github.com/letrahoo/lework/pull/1",
                "https://github.com/letrahoo/lework/releases/tag/v0.7.1",
                "https://en.wikipedia.org/wiki/Foo_(bar)",
                "https://example.com/a_(b)",
            ),
            links,
        )
    }
}
