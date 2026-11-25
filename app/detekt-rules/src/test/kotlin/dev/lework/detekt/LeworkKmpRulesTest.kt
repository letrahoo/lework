package dev.lework.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeworkKmpRulesTest {

    @Test
    fun `KmpCommonPlatformApi flags JVM-only auto-imported APIs, runBlocking, println, and JVM RegexOptions`() {
        val rule = KmpCommonPlatformApiRule(Config.empty)
        val code = """
            package dev.lework.store

            fun broken() {
                val now = System.currentTimeMillis()
                Thread.sleep(10)
                val s = String.format("%d", now)
                val r = Regex("abc", RegexOption.CANON_EQ)
                runBlocking { println(s + r.pattern) }
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(6, findings.size, "Findings: ${findings.map { it.message }}")
    }

    @Test
    fun `KmpCommonPlatformApi allows portable common Kotlin APIs`() {
        val rule = KmpCommonPlatformApiRule(Config.empty)
        val code = """
            package dev.lework.store

            fun clean() {
                val r = Regex("abc", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
                val s = "ok-${'$'}{r.pattern}"
                s.length
            }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `SuspendCatchSwallowsCancellation flags broad catch without rethrowing CancellationException`() {
        val rule = SuspendCatchSwallowsCancellationRule(Config.empty)
        val code = """
            package dev.lework.transport

            suspend fun badFetch(): String? = try {
                "ok"
            } catch (_: Throwable) {
                null
            }

            fun badLaunched() {
                LaunchedEffect(Unit) {
                    try {
                        Unit
                    } catch (e: Exception) {
                        Unit
                    }
                }
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.message.contains("CancellationException") })
    }

    @Test
    fun `SuspendCatchSwallowsCancellation allows explicit CancellationException rethrow or non-suspend functions`() {
        val rule = SuspendCatchSwallowsCancellationRule(Config.empty)
        val code = """
            package dev.lework.transport
            import kotlinx.coroutines.CancellationException

            suspend fun safeSeparateCatch(): String? = try {
                "ok"
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }

            suspend fun safeIsCheck(): String? = try {
                "ok"
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }

            suspend fun safeRethrow(): String = try {
                "ok"
            } catch (t: Throwable) {
                throw t
            }

            fun syncHelper(): String? = try {
                "ok"
            } catch (_: Throwable) {
                null
            }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `ComposeNestedSelectionContainer flags nested SelectionContainer, markdown inside selection, and unguarded Dialog`() {
        val rule = ComposeNestedSelectionContainerRule(Config.empty)
        val code = """
            package dev.lework.ui

            @Composable
            fun BadUi() {
                SelectionContainer {
                    SelectionContainer { Text("nested") }
                    EverforestMarkdownContent("md")
                    Dialog(onDismissRequest = {}) { Text("modal") }
                }
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(3, findings.size, "Findings: ${findings.map { it.message }}")
    }

    @Test
    fun `ComposeNestedSelectionContainer allows DisableSelection detachment before Dialog`() {
        val rule = ComposeNestedSelectionContainerRule(Config.empty)
        val code = """
            package dev.lework.ui

            @Composable
            fun GoodUi() {
                SelectionContainer {
                    Text("selectable")
                    DisableSelection {
                        Dialog(onDismissRequest = {}) { Text("modal") }
                    }
                }
                EverforestMarkdownContent("md")
            }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }

    @Test
    fun `ComposeMutableCollectionState flags mutable collections, unremembered state, and raw CoroutineScope in Composable`() {
        val rule = ComposeMutableCollectionStateRule(Config.empty)
        val code = """
            package dev.lework.ui

            class StateHolder {
                var okNonComposableState = mutableStateOf(false)
            }

            @Composable
            fun BadState() {
                val items = remember { mutableStateOf(mutableListOf<String>()) }
                val unremembered = mutableStateOf("lost-on-recompose")
                val badScope = CoroutineScope(Dispatchers.Main)
                val okScope = rememberCoroutineScope()
                val okList = remember { mutableStateOf(emptyList<String>()) }
                val okDerived = remember { derivedStateOf { okList.value.size } }
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(3, findings.size, "Findings: ${findings.map { it.message }}")
    }

    @Test
    fun `IosMainThreadUiKit flags UIApplication openURL outside dispatch_get_main_queue`() {
        val rule = IosMainThreadUiKitRule(Config.empty)
        val code = """
            package dev.lework.platform

            fun badOpen(url: Any) {
                UIApplication.sharedApplication.openURL(url)
            }

            fun goodOpen(url: Any) {
                dispatch_async(dispatch_get_main_queue()) {
                    UIApplication.sharedApplication.openURL(url)
                }
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("dispatch_get_main_queue"))
    }

    @Test
    fun `UnsafeNonNullAssertion flags double-bang assertions and allows safe checks`() {
        val rule = UnsafeNonNullAssertionRule(Config.empty)
        val code = """
            package dev.lework.store

            fun bad(x: String?): Int = x!!.length
            fun good(x: String?): Int = checkNotNull(x) { "missing" }.length
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size)
    }

    @Test
    fun `SuspendBlockingSleep flags Thread sleep inside suspend functions and coroutine builders`() {
        val rule = SuspendBlockingSleepRule(Config.empty)
        val code = """
            package dev.lework.platform

            suspend fun badSuspend() {
                Thread.sleep(50L)
            }

            fun badBuilder() {
                launch {
                    java.lang.Thread.sleep(10L)
                }
            }

            fun okBlockingWorker() {
                Thread.sleep(10L)
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(2, findings.size)
    }
}
