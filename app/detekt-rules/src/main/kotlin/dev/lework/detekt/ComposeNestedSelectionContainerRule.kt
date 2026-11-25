package dev.lework.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Prevents Compose Multiplatform `SelectionContainer` hierarchy defects:
 * 1. Nesting a `SelectionContainer` inside another `SelectionContainer` (or wrapping
 *    `EverforestMarkdownContent`, which already provides its own `SelectionContainer`), which causes
 *    competing `SelectionManager` instances and broken drag/copy interactions.
 * 2. Hosting a separate-window overlay (`Dialog`, `AlertDialog`, `ModalBottomSheet`, `Popup`,
 *    `DropdownMenu`) inside an active `SelectionContainer` without detaching via `DisableSelection`,
 *    which crashes `SelectionManager` coordinate conversions across window roots.
 */
class ComposeNestedSelectionContainerRule(config: Config = Config.empty) : Rule(config) {
    override val issue: Issue = Issue(
        id = "ComposeNestedSelectionContainer",
        severity = Severity.Defect,
        description = "Do not nest SelectionContainer (or EverforestMarkdownContent) inside another SelectionContainer, " +
            "and always wrap Dialog/Popup/DropdownMenu/ModalBottomSheet in DisableSelection when hosted inside a SelectionContainer.",
        debt = Debt.TEN_MINS,
    )

    override fun visitKtFile(file: KtFile) {
        val selectionStack = ArrayDeque<Boolean>()

        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    val callee = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    val insideActiveSelection = selectionStack.lastOrNull() == true

                    when {
                        callee == "SelectionContainer" -> {
                            if (insideActiveSelection) {
                                report(
                                    CodeSmell(
                                        issue = issue,
                                        entity = Entity.from(expression),
                                        message = "Do not nest SelectionContainer inside another SelectionContainer; " +
                                            "nested SelectionManagers compete for focus and pointer events.",
                                    ),
                                )
                            }
                            selectionStack.addLast(true)
                            super.visitCallExpression(expression)
                            selectionStack.removeLast()
                            return
                        }

                        callee == "DisableSelection" -> {
                            selectionStack.addLast(false)
                            super.visitCallExpression(expression)
                            selectionStack.removeLast()
                            return
                        }

                        callee == "EverforestMarkdownContent" && insideActiveSelection -> {
                            report(
                                CodeSmell(
                                    issue = issue,
                                    entity = Entity.from(expression),
                                    message = "EverforestMarkdownContent already wraps its content in SelectionContainer; " +
                                        "remove the outer SelectionContainer to avoid nested selection hierarchies.",
                                ),
                            )
                        }

                        callee in SEPARATE_WINDOW_OVERLAYS && insideActiveSelection -> {
                            report(
                                CodeSmell(
                                    issue = issue,
                                    entity = Entity.from(expression),
                                    message = "Wrap '$callee' in DisableSelection when invoked inside a SelectionContainer " +
                                        "to prevent SelectionManager cross-hierarchy coordinate crashes.",
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
        val SEPARATE_WINDOW_OVERLAYS = setOf(
            "Dialog",
            "AlertDialog",
            "ModalBottomSheet",
            "Popup",
            "DropdownMenu",
        )
    }
}
