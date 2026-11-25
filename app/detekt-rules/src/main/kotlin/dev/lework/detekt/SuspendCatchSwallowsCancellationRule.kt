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
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType

/**
 * Flags broad `catch (e: Exception)` / `catch (e: Throwable)` blocks inside `suspend` functions or
 * coroutine builders when `CancellationException` is neither caught separately nor rethrown.
 *
 * Swallowing `CancellationException` breaks structured concurrency across KMP / Compose Multiplatform
 * (`LaunchedEffect`, `CoroutineScope`, Ktor requests), leaking background jobs and stale UI updates.
 */
class SuspendCatchSwallowsCancellationRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        id = "SuspendCatchSwallowsCancellation",
        severity = Severity.Defect,
        description = "Catching Throwable or Exception inside a suspending context without rethrowing " +
            "CancellationException breaks structured coroutine cancellation.",
        debt = Debt.TEN_MINS,
    )

    override fun visitTryExpression(expression: KtTryExpression) {
        super.visitTryExpression(expression)
        if (!isInsideSuspendingContext(expression)) return

        val catches = expression.catchClauses
        if (catches.isEmpty()) return

        var hasCancellationCatchBefore = false
        for (catchClause in catches) {
            val typeName = catchTypeSimpleName(catchClause) ?: continue
            if (typeName == "CancellationException") {
                hasCancellationCatchBefore = true
                continue
            }
            if (typeName in BROAD_EXCEPTION_TYPES && !hasCancellationCatchBefore) {
                if (!rethrowsCancellation(catchClause)) {
                    report(
                        CodeSmell(
                            issue = issue,
                            entity = Entity.from(catchClause),
                            message = "Catching '$typeName' inside a suspending context swallows CancellationException; " +
                                "add 'catch (ce: CancellationException) { throw ce }' before this clause or check and rethrow it.",
                        ),
                    )
                }
            }
        }
    }

    private fun catchTypeSimpleName(catchClause: KtCatchClause): String? {
        val raw = catchClause.catchParameter?.typeReference?.text?.trim() ?: return null
        return raw.substringBefore('<').substringAfterLast('.').removeSuffix("?").trim()
    }

    private fun rethrowsCancellation(catchClause: KtCatchClause): Boolean {
        val body = catchClause.catchBody ?: return false
        val checksCancellation = body.anyDescendantOfType<KtIsExpression> { isExpr ->
            val target = isExpr.typeReference?.text?.substringAfterLast('.')?.trim()
            target == "CancellationException"
        }
        if (checksCancellation) return true

        // Also allow catch blocks that unconditionally rethrow at top level (e.g. log + throw t).
        var hasTopLevelThrow = false
        body.accept(
            object : KtTreeVisitorVoid() {
                override fun visitThrowExpression(expression: KtThrowExpression) {
                    hasTopLevelThrow = true
                }

                override fun visitNamedFunction(function: KtNamedFunction) {
                    // Do not look inside local functions.
                }

                override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
                    // Do not look inside nested lambdas.
                }

                override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                    // Do not look inside nested classes.
                }
            },
        )
        return hasTopLevelThrow
    }

    private fun isInsideSuspendingContext(tryExpr: KtTryExpression): Boolean {
        var cur = tryExpr.parent
        while (cur != null) {
            when (cur) {
                is KtNamedFunction -> {
                    return cur.hasModifier(KtTokens.SUSPEND_KEYWORD)
                }
                is KtFunctionLiteral -> {
                    val lambdaExpr = cur.parent as? KtLambdaExpression
                    val callExpr = findEnclosingCallForLambda(lambdaExpr)
                    val calleeName = (callExpr?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    if (calleeName in COROUTINE_BUILDER_NAMES) {
                        return true
                    }
                    // For inline stdlib lambdas (let/also/apply/run/with/forEach/map/etc.), keep walking up.
                    if (calleeName !in TRANSPARENT_INLINE_LAMBDAS) {
                        return false
                    }
                }
                is KtClassOrObject -> return false
            }
            cur = cur.parent
        }
        return false
    }

    private fun findEnclosingCallForLambda(lambda: KtLambdaExpression?): KtCallExpression? {
        var p = lambda?.parent ?: return null
        repeat(3) {
            if (p is KtCallExpression) return p
            p = p.parent ?: return null
        }
        return null
    }

    private companion object {
        val BROAD_EXCEPTION_TYPES = setOf("Throwable", "Exception")
        val COROUTINE_BUILDER_NAMES = setOf(
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
            "rememberCoroutineScope",
        )
        val TRANSPARENT_INLINE_LAMBDAS = setOf(
            "let",
            "also",
            "apply",
            "run",
            "with",
            "use",
            "withLock",
            "forEach",
            "forEachIndexed",
            "map",
            "mapNotNull",
            "flatMap",
            "filter",
            "repeat",
            "synchronized",
        )
    }
}
