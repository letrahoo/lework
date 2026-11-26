package dev.lework.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lework.platform.copyTextToClipboard
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Native KMP LaTeX Math AST, Parser & Everforest Renderer (zero WebView deps)
// ---------------------------------------------------------------------------

internal sealed interface MathNode {
    data class Text(
        val value: String,
        val italic: Boolean = false,
        val bold: Boolean = false,
        val mono: Boolean = false,
    ) : MathNode

    data class Operator(val symbol: String, val spaced: Boolean = false) : MathNode
    data class Group(val children: List<MathNode>) : MathNode
    data class Script(val base: MathNode, val sub: MathNode?, val sup: MathNode?) : MathNode
    data class Fraction(val numerator: MathNode, val denominator: MathNode) : MathNode
    data class Sqrt(val index: MathNode?, val radicand: MathNode) : MathNode
    data class Accent(val base: MathNode, val combiningChar: String) : MathNode
    data class BigOp(val symbol: String, val lower: MathNode?, val upper: MathNode?) : MathNode
    data class Matrix(
        val leftDelim: String,
        val rightDelim: String,
        val rows: List<List<MathNode>>,
    ) : MathNode
}

internal data class InlineMathMatch(
    val body: String,
    val endExclusive: Int,
)

/**
 * Matches inline LaTeX math delimiters starting at [start]:
 * - `\(...\)`
 * - Single-line `$$...$$` inside paragraphs or table cells
 * - `$...$` with strict currency & shell-variable guards (`$100`, `$50 到 $200`, `$HOME`)
 */
internal fun matchInlineMath(text: String, start: Int): InlineMathMatch? {
    val n = text.length
    if (start >= n) return null
    if (start > 0 && text[start - 1] == '\\') return null

    // 1. Explicit `\(...\)` inline math
    if (text[start] == '\\' && start + 1 < n && text[start + 1] == '(') {
        var i = start + 2
        while (i + 1 < n) {
            if (text[i] == '\n') return null
            if (text[i] == '\\' && text[i + 1] == ')') {
                val body = text.substring(start + 2, i).trim()
                return if (body.isNotEmpty()) InlineMathMatch(body, i + 2) else null
            }
            if (text[i] == '\\' && i + 1 < n) {
                i += 2
                continue
            }
            i++
        }
        return null
    }

    if (text[start] != '$') return null

    // 2. Single-line inline `$$...$$` (common inside Markdown table cells)
    if (start + 1 < n && text[start + 1] == '$') {
        var i = start + 2
        while (i + 1 < n) {
            if (text[i] == '\n') return null
            if (text[i] == '\\' && i + 1 < n) {
                i += 2
                continue
            }
            if (text[i] == '$' && text[i + 1] == '$') {
                val body = text.substring(start + 2, i).trim()
                return if (body.isNotEmpty()) InlineMathMatch(body, i + 2) else null
            }
            i++
        }
        return null
    }

    // 3. Standard `$...$` inline math
    if (start + 1 >= n || text[start + 1].isWhitespace()) return null
    var i = start + 1
    while (i < n) {
        val ch = text[i]
        if (ch == '\n') return null
        if (ch == '\\' && i + 1 < n) {
            i += 2
            continue
        }
        if (ch == '$') {
            if (i == start + 1) return null
            if (text[i - 1].isWhitespace()) return null
            // Currency guard: `$100 and $200` has a digit right after the second `$`
            if (i + 1 < n && text[i + 1].isDigit()) return null
            // Avoid consuming half of a closing `$$`
            if (i + 1 < n && text[i + 1] == '$') return null
            val body = text.substring(start + 1, i)
            return if (isLikelyInlineMath(body)) InlineMathMatch(body, i + 1) else null
        }
        i++
    }
    return null
}

private val INLINE_MATH_OP_CHARS = setOf(
    '=', '<', '>', '+', '-', '*', '/', '|', '!', '~',
    '≤', '≥', '≠', '×', '÷', '±', '≈', '∈', '→', '⇒',
)

internal fun isLikelyInlineMath(body: String): Boolean {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return false

    // Explicit LaTeX command, superscript, or subscript is unambiguous math
    if (trimmed.contains('\\') || trimmed.contains('^') || trimmed.contains('_')) {
        return true
    }

    // Reject CJK prose between two currency `$` symbols (e.g., `$50 到 $200`)
    if (trimmed.any { it.code in 0x4E00..0x9FFF || it.code in 0x3000..0x303F }) {
        return false
    }

    // Reject unbalanced parentheses (e.g., `($50) and ($foo)`)
    var parenDepth = 0
    for (c in trimmed) {
        when (c) {
            '(' -> parenDepth++
            ')' -> {
                parenDepth--
                if (parenDepth < 0) return false
            }
        }
    }
    if (parenDepth != 0) return false

    // Reject leading number followed by prose words (e.g., `$50 per month`, `$100, and`)
    if (trimmed.first().isDigit() && trimmed.any { it.isWhitespace() }) {
        val hasMathOp = trimmed.any { it in INLINE_MATH_OP_CHARS }
        if (!hasMathOp) return false
        val longWords = trimmed.split(Regex("[^A-Za-z]+")).count { it.length >= 3 }
        if (longWords >= 2) return false
    }

    // Reject multi-word English prose sentences between currency symbols
    val englishWords = trimmed.split(Regex("[^A-Za-z]+")).filter { it.length >= 2 }
    if (englishWords.size >= 3 && trimmed.count { it == ' ' } >= 2 && trimmed.none { it in setOf('=', '<', '>', '+', '*') }) {
        return false
    }

    // Pure single variable / function call / math expression (`n`, `x`, `O(n)`, `a + b`, `k-1`)
    if (trimmed.any { it in INLINE_MATH_OP_CHARS }) return true
    if (trimmed.length <= 32 && trimmed.first().isLetter() && trimmed.all { it.isLetterOrDigit() || it in "().,' " }) {
        return true
    }
    return false
}

// ---------------------------------------------------------------------------
// LaTeX Symbol Tables
// ---------------------------------------------------------------------------

private val LATEX_GREEK_AND_SYMBOLS: Map<String, String> = mapOf(
    // Uppercase Greek
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
    "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
    "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    // Lowercase Greek
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ϵ", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
    "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
    "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
    "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    // Special letterlike / constants
    "infty" to "∞", "partial" to "∂", "nabla" to "∇", "hbar" to "ℏ",
    "ell" to "ℓ", "aleph" to "ℵ", "Re" to "ℜ", "Im" to "ℑ", "wp" to "℘",
    "emptyset" to "∅", "varnothing" to "∅", "forall" to "∀", "exists" to "∃",
    "nexists" to "∄", "neg" to "¬", "lnot" to "¬", "top" to "⊤", "bot" to "⊥",
    "angle" to "∠", "triangle" to "△", "square" to "□", "Box" to "□",
    "therefore" to "∴", "because" to "∵", "prime" to "′", "degree" to "°",
    "dots" to "…", "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
    // Delimiters
    "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
    "langle" to "⟨", "rangle" to "⟩", "vert" to "|", "lvert" to "|", "rvert" to "|",
    "Vert" to "‖", "lVert" to "‖", "rVert" to "‖",
)

private val LATEX_SPACED_OPERATORS: Map<String, String> = mapOf(
    // Relations
    "le" to "≤", "leq" to "≤", "leqslant" to "≤",
    "ge" to "≥", "geq" to "≥", "geqslant" to "≥",
    "ne" to "≠", "neq" to "≠",
    "approx" to "≈", "equiv" to "≡", "sim" to "∼", "simeq" to "≃",
    "cong" to "≅", "propto" to "∝", "ll" to "≪", "gg" to "≫",
    "prec" to "≺", "succ" to "≻", "preceq" to "≼", "succeq" to "≽",
    "triangleq" to "≜", "coloneqq" to "≔", "perp" to "⊥", "parallel" to "∥",
    "vdash" to "⊢", "models" to "⊨", "mid" to "∣",
    // Binary ops
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
    "oplus" to "⊕", "otimes" to "⊗", "circ" to "∘", "bullet" to "•",
    "ast" to "∗", "star" to "⋆", "setminus" to "∖",
    "cap" to "∩", "cup" to "∪", "wedge" to "∧", "vee" to "∨",
    "land" to "∧", "lor" to "∨",
    // Sets
    "in" to "∈", "notin" to "∉", "ni" to "∋",
    "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇",
    // Arrows
    "to" to "→", "rightarrow" to "→", "longrightarrow" to "⟶",
    "leftarrow" to "←", "longleftarrow" to "⟵",
    "leftrightarrow" to "↔", "longleftrightarrow" to "⟷",
    "Rightarrow" to "⇒", "Longrightarrow" to "⟹", "implies" to "⟹",
    "Leftarrow" to "⇐", "Longleftarrow" to "⟸", "impliedby" to "⟸",
    "Leftrightarrow" to "⇔", "Longleftrightarrow" to "⟺", "iff" to "⇔",
    "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓",
    "nearrow" to "↗", "searrow" to "↘",
)

private val LATEX_BIG_OPERATORS: Map<String, String> = mapOf(
    "sum" to "∑", "prod" to "∏", "coprod" to "∐",
    "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",
    "bigcup" to "⋃", "bigcap" to "⋂", "bigoplus" to "⨁", "bigotimes" to "⨂",
    "bigvee" to "⋁", "bigwedge" to "⋀",
)

private val LATEX_UPRIGHT_FUNCTIONS: Set<String> = setOf(
    "log", "ln", "lg", "exp", "sin", "cos", "tan", "cot", "sec", "csc",
    "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
    "max", "min", "sup", "inf", "lim", "limsup", "liminf",
    "arg", "argmax", "argmin", "det", "dim", "gcd", "hom", "ker",
    "Pr", "deg", "mod", "bmod", "pmod", "Var", "Cov",
)

private val LATEX_ACCENTS: Map<String, String> = mapOf(
    "hat" to "\u0302", "widehat" to "\u0302",
    "bar" to "\u0304", "overline" to "\u0304",
    "tilde" to "\u0303", "widetilde" to "\u0303",
    "vec" to "\u20D7",
    "dot" to "\u0307",
    "ddot" to "\u0308",
)

private val BLACKBOARD_BOLD_MAP: Map<Char, String> = mapOf(
    'C' to "ℂ", 'H' to "ℍ", 'N' to "ℕ", 'P' to "ℙ", 'Q' to "ℚ", 'R' to "ℝ", 'Z' to "ℤ",
    'E' to "𝔼", 'F' to "𝔽", 'K' to "𝕂",
)

private val LATEX_SIZING_MODIFIERS: Set<String> = setOf(
    "left", "right", "middle", "big", "Big", "bigg", "Bigg",
    "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr",
    "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits",
)

// ---------------------------------------------------------------------------
// Recursive-Descent LaTeX Parser
// ---------------------------------------------------------------------------

internal fun parseLatexMath(raw: String): MathNode {
    val parser = LatexMathParser(raw.trim())
    val nodes = parser.parseSequence(stopAtCloseBrace = false, stopAtEnvEnd = null)
    return if (nodes.size == 1) nodes.first() else MathNode.Group(nodes)
}

private class LatexMathParser(private val src: String) {
    private var pos = 0

    fun parseSequence(stopAtCloseBrace: Boolean, stopAtEnvEnd: String?): List<MathNode> {
        val out = mutableListOf<MathNode>()
        while (pos < src.length) {
            skipWhitespace()
            if (pos >= src.length) break
            val ch = src[pos]
            if (stopAtCloseBrace && ch == '}') {
                pos++
                break
            }
            if (stopAtEnvEnd != null && src.startsWith("\\end{$stopAtEnvEnd}", pos)) {
                break
            }
            if (stopAtEnvEnd != null && (ch == '&' || src.startsWith("\\\\", pos))) {
                break
            }
            val atom = parseAtom() ?: break
            out.add(attachScripts(atom))
        }
        return out
    }

    private fun attachScripts(initial: MathNode): MathNode {
        var sub: MathNode? = null
        var sup: MathNode? = null
        while (true) {
            val saved = pos
            skipWhitespace()
            if (pos >= src.length) {
                pos = saved
                break
            }
            val ch = src[pos]
            if (ch == '_' && sub == null) {
                pos++
                sub = parseRequiredArg()
            } else if (ch == '^' && sup == null) {
                pos++
                sup = parseRequiredArg()
            } else if (ch == '\'') {
                var primes = 0
                while (pos < src.length && src[pos] == '\'') {
                    primes++
                    pos++
                }
                val primeNode = MathNode.Text("′".repeat(primes), italic = false)
                sup = if (sup == null) primeNode else MathNode.Group(listOf(sup, primeNode))
            } else {
                pos = saved
                break
            }
        }
        if (sub == null && sup == null) return initial
        if (initial is MathNode.BigOp && initial.lower == null && initial.upper == null) {
            return initial.copy(lower = sub, upper = sup)
        }
        return MathNode.Script(base = initial, sub = sub, sup = sup)
    }

    private fun parseRequiredArg(): MathNode {
        skipWhitespace()
        if (pos >= src.length) return MathNode.Text("")
        if (src[pos] == '{') {
            pos++
            val inner = parseSequence(stopAtCloseBrace = true, stopAtEnvEnd = null)
            return if (inner.size == 1) inner.first() else MathNode.Group(inner)
        }
        return parseAtom() ?: MathNode.Text("")
    }

    private fun parseOptionalBracketArg(): MathNode? {
        val saved = pos
        skipWhitespace()
        if (pos >= src.length || src[pos] != '[') {
            pos = saved
            return null
        }
        pos++
        val start = pos
        var depth = 1
        while (pos < src.length && depth > 0) {
            when (src[pos]) {
                '[' -> depth++
                ']' -> depth--
            }
            if (depth > 0) pos++
        }
        val content = src.substring(start, pos)
        if (pos < src.length && src[pos] == ']') pos++
        return parseLatexMath(content)
    }

    private fun parseRawBracedText(): String {
        skipWhitespace()
        if (pos >= src.length) return ""
        if (src[pos] != '{') {
            val ch = src[pos]
            pos++
            return ch.toString()
        }
        pos++
        val start = pos
        var depth = 1
        while (pos < src.length && depth > 0) {
            val c = src[pos]
            if (c == '\\' && pos + 1 < src.length) {
                pos += 2
                continue
            }
            if (c == '{') depth++
            else if (c == '}') depth--
            if (depth > 0) pos++
        }
        val raw = src.substring(start, pos)
        if (pos < src.length && src[pos] == '}') pos++
        return raw
    }

    private fun parseAtom(): MathNode? {
        skipWhitespace()
        if (pos >= src.length) return null
        val ch = src[pos]
        return when {
            ch == '{' -> {
                pos++
                val inner = parseSequence(stopAtCloseBrace = true, stopAtEnvEnd = null)
                if (inner.size == 1) inner.first() else MathNode.Group(inner)
            }
            ch == '\\' -> parseCommand()
            ch.isDigit() -> parseNumber()
            ch.isLetter() -> {
                pos++
                MathNode.Text(ch.toString(), italic = true)
            }
            ch in setOf('+', '-', '=', '<', '>') -> {
                pos++
                val sym = if (ch == '-') "−" else ch.toString()
                MathNode.Operator(sym, spaced = true)
            }
            ch == ',' || ch == ';' -> {
                pos++
                MathNode.Text("$ch ", italic = false)
            }
            else -> {
                pos++
                MathNode.Text(ch.toString(), italic = false)
            }
        }
    }

    private fun parseNumber(): MathNode {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || (src[pos] == '.' && pos + 1 < src.length && src[pos + 1].isDigit()))) {
            pos++
        }
        return MathNode.Text(src.substring(start, pos), italic = false)
    }

    private fun parseCommand(): MathNode {
        pos++ // consume '\'
        if (pos >= src.length) return MathNode.Text("\\")
        val first = src[pos]
        if (!first.isLetter()) {
            pos++
            return when (first) {
                '\\' -> MathNode.Text("\n")
                ',', ';', ':', ' ' -> MathNode.Text(" ")
                '!' -> MathNode.Text("")
                '|' -> MathNode.Text("‖")
                '{', '}', '$', '%', '&', '#', '_' -> MathNode.Text(first.toString())
                else -> MathNode.Text(first.toString())
            }
        }
        val start = pos
        while (pos < src.length && src[pos].isLetter()) pos++
        val cmd = src.substring(start, pos)
        return handleCommand(cmd)
    }

    private fun handleCommand(cmd: String): MathNode {
        if (cmd == "quad") return MathNode.Text("  ")
        if (cmd == "qquad") return MathNode.Text("    ")
        if (cmd in LATEX_SIZING_MODIFIERS) {
            skipWhitespace()
            if (pos < src.length && src[pos] == '.') {
                pos++
                return MathNode.Text("")
            }
            return parseAtom() ?: MathNode.Text("")
        }
        if (cmd == "begin") return parseEnvironment()
        if (cmd == "frac" || cmd == "dfrac" || cmd == "tfrac" || cmd == "cfrac") {
            val num = parseRequiredArg()
            val den = parseRequiredArg()
            return MathNode.Fraction(num, den)
        }
        if (cmd == "binom" || cmd == "dbinom" || cmd == "tbinom") {
            val top = parseRequiredArg()
            val bot = parseRequiredArg()
            return MathNode.Matrix(leftDelim = "(", rightDelim = ")", rows = listOf(listOf(top), listOf(bot)))
        }
        if (cmd == "sqrt") {
            val idx = parseOptionalBracketArg()
            val rad = parseRequiredArg()
            return MathNode.Sqrt(idx, rad)
        }
        if (cmd == "text" || cmd == "textrm" || cmd == "mathrm" || cmd == "operatorname" || cmd == "mbox") {
            return MathNode.Text(parseRawBracedText(), italic = false)
        }
        if (cmd == "mathbf" || cmd == "boldsymbol" || cmd == "bm" || cmd == "textbf") {
            return MathNode.Text(flattenNodeToPlainText(parseRequiredArg()), italic = false, bold = true)
        }
        if (cmd == "mathit" || cmd == "textit") {
            return MathNode.Text(flattenNodeToPlainText(parseRequiredArg()), italic = true)
        }
        if (cmd == "texttt" || cmd == "mathtt") {
            return MathNode.Text(parseRawBracedText(), italic = false, mono = true)
        }
        if (cmd == "mathbb") {
            val inner = parseRawBracedText()
            val mapped = inner.map { BLACKBOARD_BOLD_MAP[it] ?: it.toString() }.joinToString("")
            return MathNode.Text(mapped, italic = false)
        }
        if (cmd == "mathcal" || cmd == "mathscr" || cmd == "mathfrak") {
            return MathNode.Text(parseRawBracedText(), italic = true)
        }
        LATEX_ACCENTS[cmd]?.let { combining ->
            val base = parseRequiredArg()
            return MathNode.Accent(base, combining)
        }
        LATEX_BIG_OPERATORS[cmd]?.let { sym ->
            return MathNode.BigOp(sym, lower = null, upper = null)
        }
        if (cmd in LATEX_UPRIGHT_FUNCTIONS) {
            return MathNode.Text(cmd, italic = false)
        }
        LATEX_SPACED_OPERATORS[cmd]?.let { sym ->
            return MathNode.Operator(sym, spaced = true)
        }
        LATEX_GREEK_AND_SYMBOLS[cmd]?.let { sym ->
            return MathNode.Text(sym, italic = false)
        }
        // Unknown command fallback: strip backslash cleanly when harmless, otherwise keep readable
        return MathNode.Text(cmd, italic = false)
    }

    private fun parseEnvironment(): MathNode {
        val envName = parseRawBracedText().trim()
        val (leftDelim, rightDelim) = when (envName) {
            "pmatrix" -> "(" to ")"
            "bmatrix" -> "[" to "]"
            "Bmatrix" -> "{" to "}"
            "vmatrix" -> "|" to "|"
            "Vmatrix" -> "‖" to "‖"
            "cases" -> "{" to ""
            else -> "" to ""
        }
        val rows = mutableListOf<List<MathNode>>()
        var currentRow = mutableListOf<MathNode>()
        while (pos < src.length) {
            skipWhitespace()
            if (src.startsWith("\\end{$envName}", pos)) {
                pos += "\\end{$envName}".length
                break
            }
            val cellNodes = parseSequence(stopAtCloseBrace = false, stopAtEnvEnd = envName)
            val cell = if (cellNodes.size == 1) cellNodes.first() else MathNode.Group(cellNodes)
            currentRow.add(cell)
            skipWhitespace()
            if (pos < src.length && src[pos] == '&') {
                pos++
            } else if (src.startsWith("\\\\", pos)) {
                pos += 2
                // Consume optional spacing `[4pt]` after `\\`
                parseOptionalBracketArg()
                rows.add(currentRow)
                currentRow = mutableListOf()
            } else if (src.startsWith("\\end{$envName}", pos)) {
                pos += "\\end{$envName}".length
                break
            } else {
                break
            }
        }
        if (currentRow.isNotEmpty()) {
            rows.add(currentRow)
        }
        return MathNode.Matrix(leftDelim = leftDelim, rightDelim = rightDelim, rows = rows)
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }
}

private fun flattenNodeToPlainText(node: MathNode): String = when (node) {
    is MathNode.Text -> node.value
    is MathNode.Operator -> node.symbol
    is MathNode.Group -> node.children.joinToString("") { flattenNodeToPlainText(it) }
    is MathNode.Script -> flattenNodeToPlainText(node.base)
    is MathNode.Fraction -> "${flattenNodeToPlainText(node.numerator)}/${flattenNodeToPlainText(node.denominator)}"
    is MathNode.Sqrt -> "√${flattenNodeToPlainText(node.radicand)}"
    is MathNode.Accent -> "${flattenNodeToPlainText(node.base)}${node.combiningChar}"
    is MathNode.BigOp -> node.symbol
    is MathNode.Matrix -> node.rows.joinToString("; ") { r -> r.joinToString(", ") { flattenNodeToPlainText(it) } }
}

// ---------------------------------------------------------------------------
// Inline AnnotatedString Renderer (native Compose scripts & clean copy)
// ---------------------------------------------------------------------------

internal fun renderInlineLatexToAnnotatedString(
    latex: String,
    baseFontSizeSp: Float = 14.5f,
): AnnotatedString = buildAnnotatedString {
    appendInlineLatexMath(latex = latex, baseFontSizeSp = baseFontSizeSp)
}

internal fun AnnotatedString.Builder.appendInlineLatexMath(
    latex: String,
    baseFontSizeSp: Float = 14.5f,
) {
    val ast = parseLatexMath(latex)
    appendMathNodeInline(ast, baseFontSizeSp)
}

private fun AnnotatedString.Builder.appendMathNodeInline(
    node: MathNode,
    fontSizeSp: Float,
    prevSibling: MathNode? = null,
) {
    when (node) {
        is MathNode.Text -> {
            val style = SpanStyle(
                fontStyle = if (node.italic) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (node.bold) FontWeight.SemiBold else null,
                fontFamily = if (node.mono) FontFamily.Monospace else null,
            )
            withStyle(style) { append(node.value) }
        }
        is MathNode.Operator -> {
            val isUnary = (node.symbol == "−" || node.symbol == "+" || node.symbol == "±") && (
                prevSibling == null ||
                    prevSibling is MathNode.Operator ||
                    (prevSibling is MathNode.Text && prevSibling.value.lastOrNull() in setOf('(', '[', '{', ','))
                )
            if (isUnary) {
                append(node.symbol)
            } else {
                val prevEndedWithSpace = prevSibling is MathNode.Text && prevSibling.value.endsWith(" ")
                if (node.spaced && prevSibling != null && !prevEndedWithSpace) append(" ")
                append(node.symbol)
                if (node.spaced) append(" ")
            }
        }
        is MathNode.Group -> {
            node.children.forEachIndexed { idx, child ->
                val prev = if (idx > 0) node.children[idx - 1] else null
                if (prev != null && needsImplicitSpace(prev, child)) {
                    append(" ")
                }
                appendMathNodeInline(child, fontSizeSp, prevSibling = prev)
            }
        }
        is MathNode.Script -> {
            appendMathNodeInline(node.base, fontSizeSp)
            val scriptSizeSp = (fontSizeSp * 0.76f).coerceAtLeast(9.5f)
            node.sub?.let { sub ->
                withStyle(
                    SpanStyle(
                        fontSize = scriptSizeSp.sp,
                        baselineShift = BaselineShift.Subscript,
                    ),
                ) {
                    appendMathNodeInline(sub, scriptSizeSp)
                }
            }
            node.sup?.let { sup ->
                withStyle(
                    SpanStyle(
                        fontSize = scriptSizeSp.sp,
                        baselineShift = BaselineShift.Superscript,
                    ),
                ) {
                    appendMathNodeInline(sup, scriptSizeSp)
                }
            }
        }
        is MathNode.Fraction -> {
            val wrapNum = isCompoundNode(node.numerator)
            val wrapDen = isCompoundNode(node.denominator)
            if (wrapNum) append("(")
            appendMathNodeInline(node.numerator, fontSizeSp)
            if (wrapNum) append(")")
            append("/")
            if (wrapDen) append("(")
            appendMathNodeInline(node.denominator, fontSizeSp)
            if (wrapDen) append(")")
        }
        is MathNode.Sqrt -> {
            node.index?.let { idx ->
                val idxSize = (fontSizeSp * 0.72f).coerceAtLeast(9f)
                withStyle(SpanStyle(fontSize = idxSize.sp, baselineShift = BaselineShift.Superscript)) {
                    appendMathNodeInline(idx, idxSize)
                }
            }
            append("√")
            val wrap = isCompoundNode(node.radicand)
            if (wrap) append("(")
            appendMathNodeInline(node.radicand, fontSizeSp)
            if (wrap) append(")")
        }
        is MathNode.Accent -> {
            val plain = flattenNodeToPlainText(node.base)
            if (plain.length == 1) {
                val italic = (node.base as? MathNode.Text)?.italic ?: false
                withStyle(SpanStyle(fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal)) {
                    append(plain)
                    append(node.combiningChar)
                }
            } else {
                appendMathNodeInline(node.base, fontSizeSp)
                append(node.combiningChar)
            }
        }
        is MathNode.BigOp -> {
            append(node.symbol)
            val scriptSizeSp = (fontSizeSp * 0.76f).coerceAtLeast(9.5f)
            node.lower?.let { low ->
                withStyle(SpanStyle(fontSize = scriptSizeSp.sp, baselineShift = BaselineShift.Subscript)) {
                    appendMathNodeInline(low, scriptSizeSp)
                }
            }
            node.upper?.let { up ->
                withStyle(SpanStyle(fontSize = scriptSizeSp.sp, baselineShift = BaselineShift.Superscript)) {
                    appendMathNodeInline(up, scriptSizeSp)
                }
            }
        }
        is MathNode.Matrix -> {
            if (node.leftDelim.isNotEmpty()) append(node.leftDelim)
            node.rows.forEachIndexed { rIdx, row ->
                if (rIdx > 0) append("; ")
                row.forEachIndexed { cIdx, cell ->
                    if (cIdx > 0) append(", ")
                    appendMathNodeInline(cell, fontSizeSp)
                }
            }
            if (node.rightDelim.isNotEmpty()) append(node.rightDelim)
        }
    }
}

private fun needsImplicitSpace(prev: MathNode, next: MathNode): Boolean {
    val prevIsUprightFunc = prev is MathNode.Text && !prev.italic && prev.value.length >= 2 && prev.value.all { it.isLetter() }
    val nextIsAlphaNum = next is MathNode.Text && next.value.firstOrNull()?.isLetterOrDigit() == true
    val prevIsSingleToken = prev is MathNode.Text && prev.value.length == 1 && prev.value[0].isLetterOrDigit()
    val nextIsUprightFunc = next is MathNode.Text && !next.italic && next.value.length >= 2 && next.value.all { it.isLetter() }
    return (prevIsUprightFunc && nextIsAlphaNum) || (prevIsSingleToken && nextIsUprightFunc)
}

private fun isCompoundNode(node: MathNode): Boolean = when (node) {
    is MathNode.Group -> node.children.size > 1 && node.children.any { it is MathNode.Operator }
    is MathNode.Fraction -> true
    else -> false
}

// ---------------------------------------------------------------------------
// Block Math Card Renderer (Stacked Fractions, BigOp Limits, Matrices & Copy)
// ---------------------------------------------------------------------------

@Composable
internal fun MathBlockView(
    latex: String,
    modifier: Modifier = Modifier,
) {
    val ast = remember(latex) { parseLatexMath(latex) }
    var copies by remember(latex) { mutableIntStateOf(0) }
    val copied = copies != 0
    LaunchedEffect(latex, copies) {
        if (copied) {
            delay(1_600L)
            copies = 0
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Everforest.BgDim,
        border = BorderStroke(1.dp, Everforest.Bg2),
        modifier = modifier
            .fillMaxWidth()
            .testTag("md_math_block"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Everforest.Bg1.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "数学公式",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Everforest.CommentGrey,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                if (copyTextToClipboard(latex)) {
                                    copies++
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("md_math_copy_btn"),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = "复制公式源码",
                            tint = if (copied) Everforest.SoftSage else Everforest.CommentGrey,
                            modifier = Modifier.size(12.5.dp),
                        )
                        Text(
                            text = if (copied) "已复制" else "复制 LaTeX",
                            fontSize = 11.sp,
                            color = if (copied) Everforest.SoftSage else Everforest.CommentGrey,
                        )
                    }
                }
            }

            HorizontalDivider(color = Everforest.Bg2, thickness = 0.5.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                MathNodeBlockComposable(
                    node = ast,
                    fontSizeSp = 16.5f,
                    color = Everforest.Fg,
                )
            }
        }
    }
}

@Composable
private fun MathNodeBlockComposable(
    node: MathNode,
    fontSizeSp: Float,
    color: Color,
) {
    when {
        !containsBlockLayoutNode(node) -> {
            val inlineStyled = remember(node, fontSizeSp) {
                buildAnnotatedString {
                    appendMathNodeInline(node, fontSizeSp)
                }
            }
            Text(
                text = inlineStyled,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.45f).sp,
                color = color,
            )
        }
        node is MathNode.Group -> {
            val lines = remember(node) { splitGroupOnLineBreaks(node.children) }
            if (lines.size > 1) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (lineChildren in lines) {
                        MathGroupRow(lineChildren, fontSizeSp, color)
                    }
                }
            } else {
                MathGroupRow(node.children, fontSizeSp, color)
            }
        }
        node is MathNode.Fraction -> {
            val childFontSp = (fontSizeSp * 0.88f).coerceAtLeast(12f)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(horizontal = 4.dp),
            ) {
                MathNodeBlockComposable(node.numerator, childFontSp, color)
                Box(
                    modifier = Modifier
                        .widthIn(min = 24.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(color.copy(alpha = 0.78f)),
                )
                MathNodeBlockComposable(node.denominator, childFontSp, color)
            }
        }
        node is MathNode.BigOp -> {
            val limitFontSp = (fontSizeSp * 0.72f).coerceAtLeast(10.5f)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 3.dp),
            ) {
                node.upper?.let { up ->
                    MathNodeBlockComposable(up, limitFontSp, color)
                }
                Text(
                    text = node.symbol,
                    fontSize = (fontSizeSp * 1.45f).sp,
                    lineHeight = (fontSizeSp * 1.55f).sp,
                    color = color,
                )
                node.lower?.let { low ->
                    MathNodeBlockComposable(low, limitFontSp, color)
                }
            }
        }
        node is MathNode.Sqrt -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                node.index?.let { idx ->
                    val idxSize = (fontSizeSp * 0.68f).coerceAtLeast(10f)
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        MathNodeBlockComposable(idx, idxSize, color)
                    }
                }
                Text(
                    text = "√",
                    fontSize = (fontSizeSp * 1.15f).sp,
                    color = color,
                )
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .padding(start = 1.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(min = 16.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(color.copy(alpha = 0.75f)),
                    )
                    Box(modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)) {
                        MathNodeBlockComposable(node.radicand, fontSizeSp, color)
                    }
                }
            }
        }
        node is MathNode.Matrix -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (node.leftDelim.isNotEmpty()) {
                    Text(
                        text = node.leftDelim,
                        fontSize = (fontSizeSp * (1f + (node.rows.size - 1).coerceAtMost(2) * 0.45f)).sp,
                        color = Everforest.Grey2,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    for (row in node.rows) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            for (cell in row) {
                                MathNodeBlockComposable(cell, (fontSizeSp * 0.94f).coerceAtLeast(12.5f), color)
                            }
                        }
                    }
                }
                if (node.rightDelim.isNotEmpty()) {
                    Text(
                        text = node.rightDelim,
                        fontSize = (fontSizeSp * (1f + (node.rows.size - 1).coerceAtMost(2) * 0.45f)).sp,
                        color = Everforest.Grey2,
                    )
                }
            }
        }
        else -> {
            val inlineStyled = remember(node, fontSizeSp) {
                buildAnnotatedString { appendMathNodeInline(node, fontSizeSp) }
            }
            Text(
                text = inlineStyled,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.45f).sp,
                color = color,
            )
        }
    }
}

@Composable
private fun MathGroupRow(
    children: List<MathNode>,
    fontSizeSp: Float,
    color: Color,
) {
    // Coalesce contiguous inline-safe runs into a single Text node so scripts & operators keep baseline flow
    val chunks = remember(children) { coalesceBlockMathRuns(children) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (chunk in chunks) {
            MathNodeBlockComposable(chunk, fontSizeSp, color)
        }
    }
}

private fun containsBlockLayoutNode(node: MathNode): Boolean = when (node) {
    is MathNode.Fraction,
    is MathNode.BigOp,
    is MathNode.Sqrt,
    is MathNode.Matrix -> true
    is MathNode.Group -> node.children.any {
        containsBlockLayoutNode(it) || (it is MathNode.Text && it.value == "\n")
    }
    else -> false
}

private fun splitGroupOnLineBreaks(children: List<MathNode>): List<List<MathNode>> {
    val lines = mutableListOf<List<MathNode>>()
    val cur = mutableListOf<MathNode>()
    for (child in children) {
        if (child is MathNode.Text && child.value == "\n") {
            if (cur.isNotEmpty()) {
                lines.add(cur.toList())
                cur.clear()
            }
        } else {
            cur.add(child)
        }
    }
    if (cur.isNotEmpty()) lines.add(cur.toList())
    return lines
}

private fun coalesceBlockMathRuns(children: List<MathNode>): List<MathNode> {
    val out = mutableListOf<MathNode>()
    val inlineBuf = mutableListOf<MathNode>()
    fun flush() {
        if (inlineBuf.isNotEmpty()) {
            out.add(if (inlineBuf.size == 1) inlineBuf.first() else MathNode.Group(inlineBuf.toList()))
            inlineBuf.clear()
        }
    }
    for (child in children) {
        if (containsBlockLayoutNode(child)) {
            flush()
            out.add(child)
        } else {
            inlineBuf.add(child)
        }
    }
    flush()
    return out
}
