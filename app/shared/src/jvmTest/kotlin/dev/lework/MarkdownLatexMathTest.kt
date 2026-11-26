package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import dev.lework.platform.JvmPlatformTestHooks
import dev.lework.ui.EverforestColorScheme
import dev.lework.ui.EverforestMarkdownContent
import dev.lework.ui.MdBlock
import dev.lework.ui.matchInlineMath
import dev.lework.ui.parseEverforestInline
import dev.lework.ui.parseLatexMath
import dev.lework.ui.parseMarkdownBlocks
import dev.lework.ui.renderInlineLatexToAnnotatedString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarkdownLatexMathTest {

    @BeforeTest
    fun resetClipboardHooks() {
        JvmPlatformTestHooks.clipboardText = ""
        JvmPlatformTestHooks.clipboardWriteSucceeds = true
    }

    @AfterTest
    fun restoreClipboardHooks() {
        JvmPlatformTestHooks.clipboardText = ""
        JvmPlatformTestHooks.clipboardWriteSucceeds = true
    }

    @Test
    fun inlineMathRendersGreekOperatorsScriptsFractionsAndFunctions() {
        // Exact scenario from screenshot: `WAL `Changeset` (仅 $\Delta$) + Spanner 元数据`
        val cell = parseEverforestInline("WAL `Changeset` (仅 \$\\Delta\$) + Spanner 元数据")
        assertTrue(cell.text.contains("(仅 Δ)"), "Expected rendered Delta, got: ${cell.text}")
        assertFalse(cell.text.contains("\\Delta"), "Raw LaTeX should not leak: ${cell.text}")

        // Complexity notation with upright `\log` and italic variables
        val complexity = renderInlineLatexToAnnotatedString("O(n \\log n)")
        assertEquals("O(n log n)", complexity.text)
        assertTrue(
            complexity.spanStyles.any { it.item.fontStyle == FontStyle.Italic },
            "Expected italic variables in O(n log n)",
        )

        // Superscript and subscript native BaselineShift
        val scripts = renderInlineLatexToAnnotatedString("x_i^2 + y_{k+1}")
        assertEquals("xi2 + yk + 1", scripts.text)
        assertTrue(scripts.spanStyles.any { it.item.baselineShift == BaselineShift.Subscript })
        assertTrue(scripts.spanStyles.any { it.item.baselineShift == BaselineShift.Superscript })

        // Relations, arrows, sets, blackboard bold, fractions, roots
        val symbols = renderInlineLatexToAnnotatedString("\\alpha \\le \\beta \\Rightarrow x \\in \\mathbb{R}")
        assertEquals("α ≤ β ⇒ x ∈ ℝ", symbols.text)

        val fracAndRoot = renderInlineLatexToAnnotatedString("\\frac{a + b}{2} + \\sqrt{x^2 + 1}")
        assertEquals("(a + b)/2 + √(x2 + 1)", fracAndRoot.text)

        // Explicit `\(...\)` inline delimiters
        val parenInline = parseEverforestInline("差值 \\(\\Delta t \\ge 0\\) 成立")
        assertEquals("差值 Δt ≥ 0 成立", parenInline.text)
    }

    @Test
    fun currencyAmountsShellVarsAndCodeSpansAreNotMisinterpretedAsMath() {
        // Currency ranges and prices must remain untouched
        val cny = parseEverforestInline("价格从 \$50 到 \$200 不等，单价 \$100。")
        assertEquals("价格从 \$50 到 \$200 不等，单价 \$100。", cny.text)

        val usd = parseEverforestInline("Budget (\$50) and (\$100) per month")
        assertEquals("Budget (\$50) and (\$100) per month", usd.text)

        // Spaces immediately inside `$` are rejected
        assertNull(matchInlineMath("\$ 5 + 3 \$", 0))

        // Backtick code spans must preserve literal `$\Delta$`
        val inCode = parseEverforestInline("源码写法：`\$\\Delta\$`")
        assertTrue(inCode.text.contains("\$\\Delta\$"), "Code span should stay literal: ${inCode.text}")

        // Escaped `\$` does not start a math span
        assertNull(matchInlineMath("\\\$\\Delta\$", 1))
    }

    @Test
    fun blockMarkdownParserExtractsDollarAndBracketMathBlocks() {
        val md = """
            前文说明：
            $$
            \Delta = b^2 - 4ac
            $$
            单行公式：
            \[ \sum_{i=1}^{n} \frac{1}{i^2} = \frac{\pi^2}{6} \]
            ```tex
            $$ \alpha + \beta $$
            ```
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        val mathBlocks = blocks.filterIsInstance<MdBlock.MathBlock>()
        assertEquals(2, mathBlocks.size)
        assertEquals("\\Delta = b^2 - 4ac", mathBlocks[0].latex)
        assertEquals("\\sum_{i=1}^{n} \\frac{1}{i^2} = \\frac{\\pi^2}{6}", mathBlocks[1].latex)

        // Code fence containing `$$` must remain a CodeFence, NOT a MathBlock
        val codeBlocks = blocks.filterIsInstance<MdBlock.CodeFence>()
        assertEquals(1, codeBlocks.size)
        assertTrue(codeBlocks[0].code.contains("\\alpha + \\beta"))

        // Matrix / cases environment AST parsing
        val casesAst = parseLatexMath("\\begin{cases} x & x \\ge 0 \\\\ -x & x < 0 \\end{cases}")
        assertTrue(casesAst is dev.lework.ui.MathNode.Matrix)
    }

    @Test
    fun composeRendersTableInlineDeltaAndCopyableBlockMathCard() = runComposeUiTest {
        val markdown = """
            | 维度 | Scion（为高并发 Agent + CI 设计） |
            | --- | --- |
            | 存储 | WAL `Changeset` (仅 ${'$'}\Delta${'$'}) + Spanner 元数据 + 对象存储 Blob |

            $$
            \Delta = \frac{b^2 - 4ac}{2a}
            $$
        """.trimIndent()

        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(720.dp, 520.dp)) {
                    EverforestMarkdownContent(markdown = markdown)
                }
            }
        }
        waitForIdle()

        // 1. Table cell displays rendered `Δ` instead of raw `$\Delta$`
        onNodeWithTag("md_table", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("(仅 Δ)", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("\\Delta", substring = true, useUnmergedTree = true).assertDoesNotExist()

        // 2. Block math card displays stacked formula and copies raw LaTeX on click
        onNodeWithTag("md_math_block", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("数学公式", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("md_math_copy_btn", useUnmergedTree = true).assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals("\\Delta = \\frac{b^2 - 4ac}{2a}", JvmPlatformTestHooks.clipboardText)
        onNodeWithText("已复制", useUnmergedTree = true).assertIsDisplayed()
    }
}
