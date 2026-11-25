package dev.lework.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Guards KMP `commonMain` / `commonTest` code against JVM-only or non-portable APIs that bypass
 * `ForbiddenImport` (such as auto-imported `java.lang.System`, `Thread`, `Runtime`, `String.format`,
 * JVM-only `RegexOption` constants, `runBlocking`, and raw `println` in production common code).
 */
class KmpCommonPlatformApiRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        id = "KmpCommonPlatformApi",
        severity = Severity.Defect,
        description = "Avoid JVM-only or non-portable APIs in KMP common sources; " +
            "bridge platform APIs via dev.lework.platform expect/actual and use structured coroutines/logging.",
        debt = Debt.TEN_MINS,
    )

    override fun visitKtFile(file: KtFile) {
        val isTestFile = file.virtualFilePath.replace('\\', '/').contains("/commonTest/")

        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                    val receiver = expression.receiverExpression.text.replace(WHITESPACE_REGEX, "")
                    val selector = expression.selectorExpression
                    val selectorName = when (selector) {
                        is KtCallExpression -> (selector.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                        is KtNameReferenceExpression -> selector.getReferencedName()
                        else -> null
                    }

                    val reason = when {
                        (receiver == "System" || receiver == "java.lang.System") && selectorName in FORBIDDEN_SYSTEM_MEMBERS ->
                            "Avoid JVM-only 'System.$selectorName' in KMP common code; use dev.lework.platform expect/actual (e.g. epochMillis())."

                        (receiver == "Thread" || receiver == "java.lang.Thread") && selectorName in FORBIDDEN_THREAD_MEMBERS ->
                            "Avoid JVM-only 'Thread.$selectorName' in KMP common code; use kotlinx.coroutines.delay()."

                        (receiver == "Runtime" || receiver == "java.lang.Runtime") && selectorName == "getRuntime" ->
                            "Avoid JVM-only 'Runtime.getRuntime()' in KMP common code."

                        (receiver == "String" || receiver == "java.lang.String") && selectorName == "format" ->
                            "Avoid JVM-only 'String.format()' in KMP common code; use Kotlin string templates or explicit formatting helpers."

                        receiver == "RegexOption" && selectorName in JVM_ONLY_REGEX_OPTIONS ->
                            "Avoid JVM-only 'RegexOption.$selectorName' in KMP common code; it is unavailable on Kotlin/Native iOS."

                        else -> null
                    }

                    if (reason != null) {
                        report(
                            CodeSmell(
                                issue = issue,
                                entity = Entity.from(expression),
                                message = reason,
                            ),
                        )
                    }
                    super.visitDotQualifiedExpression(expression)
                }

                override fun visitCallExpression(expression: KtCallExpression) {
                    val calleeName = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    if (calleeName != null) {
                        val parent = expression.parent
                        val isQualifiedSelector =
                            (parent as? KtDotQualifiedExpression)?.selectorExpression == expression ||
                                (parent as? KtSafeQualifiedExpression)?.selectorExpression == expression

                        val reason = when {
                            !isQualifiedSelector && calleeName == "runBlocking" ->
                                "Avoid 'runBlocking' in KMP common code; it deadlocks mobile UI threads and breaks non-blocking KMP targets."

                            !isQualifiedSelector && !isTestFile && (calleeName == "println" || calleeName == "print") ->
                                "Avoid raw '$calleeName' in KMP commonMain; use logRuntimeEvent() for structured, redacted cross-platform logs."

                            else -> null
                        }

                        if (reason != null) {
                            report(
                                CodeSmell(
                                    issue = issue,
                                    entity = Entity.from(expression),
                                    message = reason,
                                ),
                            )
                        }
                    }
                    super.visitCallExpression(expression)
                }
            },
        )
    }

    private companion object {
        val WHITESPACE_REGEX = Regex("\\s+")
        val FORBIDDEN_SYSTEM_MEMBERS = setOf(
            "currentTimeMillis",
            "nanoTime",
            "getenv",
            "getProperty",
            "setProperty",
            "exit",
            "gc",
            "out",
            "err",
        )
        val FORBIDDEN_THREAD_MEMBERS = setOf(
            "sleep",
            "currentThread",
        )
        val JVM_ONLY_REGEX_OPTIONS = setOf(
            "CANON_EQ",
            "COMMENTS",
            "UNIX_LINES",
        )
    }
}
