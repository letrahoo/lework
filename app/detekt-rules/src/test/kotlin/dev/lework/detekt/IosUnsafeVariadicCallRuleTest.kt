package dev.lework.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosUnsafeVariadicCallRuleTest {

    private val rule = IosUnsafeVariadicCallRule(Config.empty)

    @Test
    fun `reports direct NSLog call`() {
        val code = """
            package dev.lework.platform
            import platform.Foundation.NSLog

            fun log(msg: String) {
                NSLog("%@", msg)
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("NSLog"))
    }

    @Test
    fun `reports fully qualified NSLog call without import`() {
        val code = """
            package dev.lework.platform

            fun log(msg: String) {
                platform.Foundation.NSLog("%@", msg)
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("NSLog"))
    }

    @Test
    fun `reports aliased NSLog call`() {
        val code = """
            package dev.lework.platform
            import platform.Foundation.NSLog as nativeLog

            fun log(msg: String) {
                nativeLog("%@", msg)
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("nativeLog"))
    }

    @Test
    fun `reports NSString stringWithFormat variadic call including alias`() {
        val code = """
            package dev.lework.platform
            import platform.Foundation.NSString
            import platform.Foundation.NSString as FNSString

            fun fmt(msg: String): Any = NSString.stringWithFormat("%@", msg)
            fun fmtFqn(msg: String): Any = platform.Foundation.NSString.stringWithFormat("%@", msg)
            fun fmtAlias(msg: String): Any = FNSString.stringWithFormat("%@", msg)
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(3, findings.size)
        assertTrue(findings.all { it.message.contains("stringWithFormat") })
    }

    @Test
    fun `reports multiline and backtick-escaped NSLog calls`() {
        val code = """
            package dev.lework.platform

            fun logEscaped(msg: String) {
                platform.Foundation
                    .`NSLog`("%@", msg)
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("NSLog"))
    }

    @Test
    fun `reports NSLogv variadic call`() {
        val code = """
            package dev.lework.platform

            fun logv(msg: String) {
                platform.Foundation.NSLogv("%@", msg)
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("NSLogv"))
    }

    @Test
    fun `reports CFStringCreateWithFormat and POSIX variadic calls`() {
        val code = """
            package dev.lework.platform
            import platform.CoreFoundation.CFStringCreateWithFormat
            import platform.posix.snprintf

            fun bad() {
                CFStringCreateWithFormat(null, null, null)
                snprintf(null, 0u, "%s")
                platform.posix.printf("%s")
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(3, findings.size)
    }

    @Test
    fun `ignores comments, string literals, and safe println logging`() {
        val code = """
            package dev.lework.platform

            // Do not use NSLog or NSString.stringWithFormat in iOS Kotlin.
            /* platform.Foundation.NSLog("%@", "comment") */
            fun log(tag: String, msg: String) {
                val hint = "Avoid NSLog and NSString.stringWithFormat"
                println("[Lework/${'$'}tag] ${'$'}msg (${'$'}hint)")
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size)
    }
}
