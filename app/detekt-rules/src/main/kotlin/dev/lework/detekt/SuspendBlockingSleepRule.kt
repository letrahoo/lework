package dev.lework.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * AST-based replacement for `SleepInsteadOfDelay` (which requires full compiler type resolution).
 * Prevents calling blocking `Thread.sleep(...)` inside suspending functions or coroutine builders,
 * where it pins dispatcher threads and ignores coroutine cancellation.
 */
class SuspendBlockingSleepRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        id = "SuspendBlockingSleep",
        severity = Severity.Defect,
        description = "Use kotlinx.coroutines.delay() instead of blocking Thread.sleep() inside suspending contexts.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val receiver = expression.receiverExpression.text.replace(WHITESPACE_REGEX, "")
        if (receiver != "Thread" && receiver != "java.lang.Thread") return
        val selectorCall = expression.selectorExpression as? KtCallExpression ?: return
        val callee = (selectorCall.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        if (callee != "sleep") return

        if (isInsideSuspendingContext(expression)) {
            report(
                CodeSmell(
                    issue = issue,
                    entity = Entity.from(expression),
                    message = "Avoid blocking Thread.sleep() inside a suspending context; use kotlinx.coroutines.delay() instead.",
                ),
            )
        }
    }

    private fun isInsideSuspendingContext(node: org.jetbrains.kotlin.psi.KtElement): Boolean {
        var cur = node.parent
        while (cur != null) {
            when (cur) {
                is KtNamedFunction -> return cur.hasModifier(KtTokens.SUSPEND_KEYWORD)
                is KtFunctionLiteral -> {
                    val lambdaExpr = cur.parent as? KtLambdaExpression
                    val callExpr = findEnclosingCall(lambdaExpr)
                    val calleeName = (callExpr?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    if (calleeName in COROUTINE_BUILDERS) return true
                    if (calleeName !in TRANSPARENT_LAMBDAS) return false
                }
                is KtClassOrObject -> return false
            }
            cur = cur.parent
        }
        return false
    }

    private fun findEnclosingCall(lambda: KtLambdaExpression?): KtCallExpression? {
        var p = lambda?.parent ?: return null
        repeat(3) {
            if (p is KtCallExpression) return p
            p = p.parent ?: return null
        }
        return null
    }

    private companion object {
        val WHITESPACE_REGEX = Regex("\\s+")
        val COROUTINE_BUILDERS = setOf(
            "launch",
            "async",
            "LaunchedEffect",
            "withContext",
            "coroutineScope",
            "supervisorScope",
            "withTimeout",
            "withTimeoutOrNull",
            "flow",
            "produce",
            "produceState",
        )
        val TRANSPARENT_LAMBDAS = setOf(
            "let",
            "also",
            "apply",
            "run",
            "with",
            "use",
            "withLock",
            "runCatching",
            "forEach",
            "forEachIndexed",
            "repeat",
        )
    }
}
