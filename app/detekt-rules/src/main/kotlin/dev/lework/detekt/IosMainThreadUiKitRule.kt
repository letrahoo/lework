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
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Ensures UIKit UI-mutating calls on `UIApplication.sharedApplication` (such as `openURL`,
 * `sendAction`, `keyWindow`) are dispatched onto the iOS main queue (`dispatch_get_main_queue()`),
 * preventing UIKit Main Thread Checker violations and crashes when invoked from Ktor / coroutine threads.
 */
class IosMainThreadUiKitRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        id = "IosMainThreadUiKit",
        severity = Severity.Defect,
        description = "Dispatch UIApplication UI operations onto dispatch_get_main_queue() in iOS Kotlin.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val receiver = expression.receiverExpression.text.replace(WHITESPACE_REGEX, "")
        if (!receiver.endsWith("UIApplication.sharedApplication")) return

        val selector = expression.selectorExpression
        val memberName = when (selector) {
            is KtCallExpression -> (selector.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            is KtNameReferenceExpression -> selector.getReferencedName()
            else -> null
        } ?: return

        if (memberName !in MAIN_THREAD_UIAPPLICATION_MEMBERS) return
        if (isInsideMainQueueDispatch(expression)) return

        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(expression),
                message = "UIApplication.sharedApplication.$memberName must run on the iOS main queue; " +
                    "wrap it in dispatch_async(dispatch_get_main_queue()) { ... }.",
            ),
        )
    }

    private fun isInsideMainQueueDispatch(node: org.jetbrains.kotlin.psi.KtElement): Boolean {
        var cur = node.parent
        while (cur != null) {
            if (cur is KtFunctionLiteral) {
                val lambdaExpr = cur.parent as? KtLambdaExpression
                val call = findEnclosingCall(lambdaExpr)
                val calleeName = (call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                if (calleeName == "dispatch_async" || calleeName == "dispatch_sync") {
                    val firstArg = call.valueArguments.firstOrNull()?.getArgumentExpression()?.text.orEmpty()
                    if (firstArg.contains("dispatch_get_main_queue")) return true
                }
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
        val MAIN_THREAD_UIAPPLICATION_MEMBERS = setOf(
            "openURL",
            "sendAction",
            "keyWindow",
        )
    }
}
