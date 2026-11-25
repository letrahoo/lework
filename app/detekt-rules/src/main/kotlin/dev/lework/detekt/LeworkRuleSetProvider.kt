package dev.lework.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class LeworkRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "lework"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            IosUnsafeVariadicCallRule(config),
            IosMainThreadUiKitRule(config),
            KmpCommonPlatformApiRule(config),
            SuspendCatchSwallowsCancellationRule(config),
            SuspendBlockingSleepRule(config),
            UnsafeNonNullAssertionRule(config),
            ComposeNestedSelectionContainerRule(config),
            ComposeMutableCollectionStateRule(config),
        ),
    )
}
