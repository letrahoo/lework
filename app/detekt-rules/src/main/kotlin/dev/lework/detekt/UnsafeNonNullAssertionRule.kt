package dev.lework.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtPostfixExpression

/**
 * AST-based replacement for `UnsafeCallOnNullableType` (which requires full compiler type resolution).
 * Disallows `!!` non-null assertions in production Kotlin sources so nullable paths fail safely
 * via `?.`, `?:`, `checkNotNull()`, or `requireNotNull()` instead of throwing uncontextualized NPEs.
 */
class UnsafeNonNullAssertionRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        id = "UnsafeNonNullAssertion",
        severity = Severity.Defect,
        description = "Avoid the non-null assertion operator (!!) in production code; " +
            "use safe calls, Elvis fallbacks, or checkNotNull()/requireNotNull() with clear messages.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitPostfixExpression(expression: KtPostfixExpression) {
        super.visitPostfixExpression(expression)
        if (expression.operationToken == KtTokens.EXCLEXCL) {
            report(
                CodeSmell(
                    issue = issue,
                    entity = Entity.from(expression),
                    message = "Avoid unsafe non-null assertion (!!); handle null safely or use checkNotNull()/requireNotNull().",
                ),
            )
        }
    }
}
