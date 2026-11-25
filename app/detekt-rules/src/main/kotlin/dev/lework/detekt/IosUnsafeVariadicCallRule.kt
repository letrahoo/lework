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
 * Prevents calling Objective-C / Foundation C-variadic formatting APIs from Kotlin/Native iOS code.
 *
 * Kotlin/Native cannot type-check `%@` format arguments across an Objective-C variadic boundary,
 * which can crash at runtime (e.g. `NSLog("%@", kotlinString)` during launch).
 */
class IosUnsafeVariadicCallRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        id = "IosUnsafeVariadicCall",
        severity = Severity.Defect,
        description = "Avoid calling Objective-C Foundation variadic APIs (NSLog, NSLogv, NSString.stringWithFormat) " +
            "from iOS Kotlin; use logRuntimeEvent (or println inside IosRuntimeLogger) or format strings in Kotlin.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitKtFile(file: KtFile) {
        val bannedAliases = mutableSetOf<String>()
        val nsStringAliases = mutableSetOf("NSString")
        for (importDirective in file.importDirectives) {
            val fqName = importDirective.importedFqName?.asString() ?: continue
            val alias = importDirective.aliasName
            if (fqName in FORBIDDEN_IMPORTS && alias != null) {
                bannedAliases.add(alias)
            } else if (fqName == "platform.Foundation.NSString" && alias != null) {
                nsStringAliases.add(alias)
            }
        }

        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    val calleeExpr = expression.calleeExpression
                    val calleeName = (calleeExpr as? KtNameReferenceExpression)?.getReferencedName()
                        ?: calleeExpr?.text
                    if (calleeName != null) {
                        val dotParent = expression.parent as? KtDotQualifiedExpression
                        val safeParent = expression.parent as? KtSafeQualifiedExpression
                        val isQualifiedSelector =
                            dotParent?.selectorExpression == expression || safeParent?.selectorExpression == expression
                        val receiverText = when {
                            dotParent?.selectorExpression == expression -> dotParent.receiverExpression.text
                            safeParent?.selectorExpression == expression -> safeParent.receiverExpression.text
                            else -> null
                        }?.replace(WHITESPACE_REGEX, "")

                        val violationReason = when {
                            calleeName in bannedAliases && !isQualifiedSelector ->
                                "Do not call aliased Foundation variadic API '$calleeName' in iOS Kotlin."

                            calleeName in FORBIDDEN_LOGGER_CALLS &&
                                (!isQualifiedSelector || receiverText == "platform.Foundation" || receiverText?.endsWith(".Foundation") == true) ->
                                "Do not call Foundation '$calleeName' in iOS Kotlin; use logRuntimeEvent (or println inside IosRuntimeLogger)."

                            calleeName == "stringWithFormat" &&
                                (!isQualifiedSelector || receiverText in nsStringAliases || receiverText?.endsWith(".NSString") == true) ->
                                "Do not call Foundation 'NSString.stringWithFormat' in iOS Kotlin; format strings in Kotlin instead."

                            else -> null
                        }

                        if (violationReason != null) {
                            report(
                                CodeSmell(
                                    issue = issue,
                                    entity = Entity.from(expression),
                                    message = violationReason,
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
        val FORBIDDEN_IMPORTS = setOf(
            "platform.Foundation.NSLog",
            "platform.Foundation.NSLogv",
            "platform.Foundation.stringWithFormat",
        )
        val FORBIDDEN_LOGGER_CALLS = setOf(
            "NSLog",
            "NSLogv",
        )
    }
}
