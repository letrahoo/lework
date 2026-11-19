package dev.lework

import androidx.compose.ui.graphics.Color
import dev.lework.protocol.ToolKind
import dev.lework.protocol.ToolStatus
import dev.lework.store.ChatTimelineItem
import dev.lework.ui.CondensedStep
import dev.lework.ui.Everforest
import dev.lework.ui.condenseTraceSteps
import dev.lework.ui.highlightEverforestCode
import dev.lework.ui.parseEverforestInline
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
}
