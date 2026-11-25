package dev.lework.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Prevents three common Compose Multiplatform state bugs (covering gaps where Android Lint
 * does not run on KMP iOS/Desktop targets):
 * 1. Wrapping a mutable collection (`mutableListOf`, `mutableMapOf`, `mutableSetOf`, `ArrayList`, etc.)
 *    inside `mutableStateOf(...)`, where in-place mutations silently fail to trigger recomposition.
 * 2. Calling `mutableStateOf` / `mutable*StateOf` / `mutableStateListOf` / `mutableStateMapOf` /
 *    `derivedStateOf` directly inside a `@Composable` function body without `remember { ... }`,
 *    which silently resets state on every recomposition.
 * 3. Constructing a raw `CoroutineScope(...)` directly inside a `@Composable` function body without
 *    `remember` / `rememberCoroutineScope()`, which leaks a new scope on every recomposition.
 */
class ComposeMutableCollectionStateRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        id = "ComposeMutableCollectionState",
        severity = Severity.Defect,
        description = "Avoid mutable collections inside mutableStateOf(), unremembered state creation, " +
            "and raw CoroutineScope() construction directly inside @Composable functions.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return

        if (callee == "mutableStateOf") {
            val firstArgExpr = expression.valueArguments.firstOrNull()?.getArgumentExpression() as? KtCallExpression
            val argCallee = (firstArgExpr?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (argCallee in MUTABLE_COLLECTION_FACTORIES) {
                report(
                    CodeSmell(
                        issue = issue,
                        entity = Entity.from(expression),
                        message = "Avoid wrapping mutable collection '$argCallee()' in mutableStateOf(); " +
                            "in-place mutations do not trigger Compose recomposition. Use immutable collections " +
                            "(emptyList/listOf/mapOf) or mutableStateListOf()/mutableStateMapOf().",
                    ),
                )
            }
        }
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val isComposable = function.annotationEntries.any {
            it.shortName?.asString() == "Composable"
        }
        if (!isComposable) return

        val body = function.bodyExpression ?: return
        body.accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    val callee = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    if (callee == "CoroutineScope" && !isInsideSafeScopeBoundary(expression, body)) {
                        report(
                            CodeSmell(
                                issue = issue,
                                entity = Entity.from(expression),
                                message = "Do not construct CoroutineScope() directly inside a @Composable body; " +
                                    "use rememberCoroutineScope() to avoid leaking scopes across recompositions.",
                            ),
                        )
                    } else if (callee in UNREMEMBERED_STATE_FACTORIES && !isInsideSafeScopeBoundary(expression, body)) {
                        report(
                            CodeSmell(
                                issue = issue,
                                entity = Entity.from(expression),
                                message = "Creating Compose state via '$callee()' directly inside a @Composable " +
                                    "without remember { ... } resets state on every recomposition.",
                            ),
                        )
                    }
                    super.visitCallExpression(expression)
                }
            },
        )
    }

    private fun isInsideSafeScopeBoundary(call: KtCallExpression, composableBody: org.jetbrains.kotlin.psi.KtElement): Boolean {
        var cur = call.parent
        while (cur != null && cur != composableBody) {
            if (cur is KtFunctionLiteral) {
                val lambdaExpr = cur.parent as? KtLambdaExpression
                val enclosingCall = findEnclosingCall(lambdaExpr)
                val enclosingName = (enclosingCall?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                if (enclosingName in SAFE_COMPOSABLE_CALLBACKS) return true
            }
            if (cur is KtNamedFunction || cur is KtClassOrObject) return true
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
        val MUTABLE_COLLECTION_FACTORIES = setOf(
            "mutableListOf",
            "mutableMapOf",
            "mutableSetOf",
            "arrayListOf",
            "hashMapOf",
            "hashSetOf",
            "linkedMapOf",
            "linkedSetOf",
            "ArrayList",
            "HashMap",
            "HashSet",
            "LinkedHashMap",
            "LinkedHashSet",
        )
        val UNREMEMBERED_STATE_FACTORIES = setOf(
            "mutableStateOf",
            "mutableIntStateOf",
            "mutableLongStateOf",
            "mutableFloatStateOf",
            "mutableDoubleStateOf",
            "mutableStateListOf",
            "mutableStateMapOf",
            "derivedStateOf",
        )
        val SAFE_COMPOSABLE_CALLBACKS = setOf(
            "remember",
            "rememberSaveable",
            "LaunchedEffect",
            "DisposableEffect",
            "SideEffect",
            "produceState",
        )
    }
}
