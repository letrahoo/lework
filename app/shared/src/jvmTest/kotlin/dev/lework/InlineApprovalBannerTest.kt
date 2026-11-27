package dev.lework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.lework.protocol.AgentEvent
import dev.lework.protocol.PermissionOption
import dev.lework.protocol.PermissionOptionKind
import dev.lework.ui.Everforest
import dev.lework.ui.EverforestColorScheme
import dev.lework.ui.InlineApprovalBanner
import dev.lework.ui.formatApprovalHeaderTitle
import dev.lework.ui.formatApprovalOptionLabel
import dev.lework.ui.shouldUseVerticalApprovalOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InlineApprovalBannerTest {

    @Test
    fun `shouldUseVerticalApprovalOptions selects compact row for short pairs and vertical stack for long options`() {
        val shortPair = listOf(
            PermissionOption("tty_key:1", "1. Yes", PermissionOptionKind.ALLOW_ONCE),
            PermissionOption("tty_key:2", "2. No", PermissionOptionKind.REJECT_ONCE),
        )
        assertFalse(shouldUseVerticalApprovalOptions(shortPair))

        val longAgyOptions = listOf(
            PermissionOption("tty_key:1", "1. Yes, run command", PermissionOptionKind.ALLOW_ONCE),
            PermissionOption(
                "tty_key:2",
                "2. Yes, and always allow in this conversation for commands that start with 'ssh devbox'",
                PermissionOptionKind.ALLOW_ALWAYS,
            ),
            PermissionOption("tty_key:3", "3. No, deny", PermissionOptionKind.REJECT_ONCE),
        )
        assertTrue(shouldUseVerticalApprovalOptions(longAgyOptions))
    }

    @Test
    fun `formatApprovalHeaderTitle and formatApprovalOptionLabel preserve full text while styling prefixes and snippets`() {
        val header = formatApprovalHeaderTitle("Reason: Overwrites systemd drop-in file on devbox.")
        assertEquals("Reason: Overwrites systemd drop-in file on devbox.", header.text)
        assertTrue(header.spanStyles.any { it.item.color == Everforest.BoldGold })

        val opt = formatApprovalOptionLabel(
            rawName = "2. Always allow 'ssh devbox' commands",
            indexAccentColor = Everforest.BoldGold,
            codeAccentColor = Everforest.InlineCode,
        )
        assertEquals("2. Always allow 'ssh devbox' commands", opt.text)
        assertTrue(opt.spanStyles.any { it.item.color == Everforest.BoldGold && it.start == 0 && it.end == 3 })
        assertTrue(opt.spanStyles.any { it.item.color == Everforest.InlineCode })
    }

    @Test
    fun `long multi-option approval renders vertical non-clipped menu and dispatches clicks accurately`() = runComposeUiTest {
        var picked: Triple<String, String?, String>? = null
        val perm = AgentEvent.PermissionRequested(
            seq = 12,
            sessionId = "host:agy:conv_approval",
            ts = 1_730_000_000_000L,
            requestId = "tty_perm_devbox_1",
            title = "Reason: Overwrites the pre-existing systemd drop-in file on devbox.\nssh devbox 'systemctl --user restart lework-hub-master.service'",
            options = listOf(
                PermissionOption("tty_key:1", "1. Yes, run command", PermissionOptionKind.ALLOW_ONCE),
                PermissionOption(
                    "tty_key:2",
                    "2. Yes, and always allow in this conversation for commands that start with 'ssh devbox'",
                    PermissionOptionKind.ALLOW_ALWAYS,
                ),
                PermissionOption(
                    "tty_key:3",
                    "3. Yes, and always allow in all conversations for commands that start with 'ssh devbox'",
                    PermissionOptionKind.ALLOW_ALWAYS,
                ),
                PermissionOption("tty_key:4", "4. No, tell agent what to do instead", PermissionOptionKind.REJECT_ONCE),
            ),
        )

        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(640.dp, 420.dp)) {
                    InlineApprovalBanner(
                        permission = perm,
                        onDecision = { reqId, optId, sid -> picked = Triple(reqId, optId, sid) },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag("inline_approval_vertical_options").assertIsDisplayed()
        onNodeWithTag("approval_option_tty_key:1").assertIsDisplayed()
        onNodeWithTag("approval_option_tty_key:2").assertIsDisplayed()
        onNodeWithTag("approval_option_tty_key:3").assertIsDisplayed()
        onNodeWithTag("approval_option_tty_key:4").assertIsDisplayed()

        onNodeWithTag("approval_option_tty_key:3").performClick()
        waitForIdle()
        assertEquals(Triple("tty_perm_devbox_1", "tty_key:3", "host:agy:conv_approval"), picked)

        // Header cancel action remains clickable and sends null optionId
        onNodeWithText("取消").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(Triple("tty_perm_devbox_1", null, "host:agy:conv_approval"), picked)
    }

    @Test
    fun `short approval options render compact horizontal row and remain clickable by label text`() = runComposeUiTest {
        var pickedOption: String? = null
        val perm = AgentEvent.PermissionRequested(
            seq = 3,
            sessionId = "host:claude:short_perm",
            ts = 1_730_000_000_000L,
            requestId = "perm_short",
            title = "允许执行 git status？",
            options = listOf(
                PermissionOption("allow", "1. Yes", PermissionOptionKind.ALLOW_ONCE),
                PermissionOption("reject", "2. No", PermissionOptionKind.REJECT_ONCE),
            ),
        )

        setContent {
            MaterialTheme(colorScheme = EverforestColorScheme) {
                Box(modifier = Modifier.size(560.dp, 240.dp)) {
                    InlineApprovalBanner(
                        permission = perm,
                        onDecision = { _, optId, _ -> pickedOption = optId },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithTag("inline_approval_compact_options").assertIsDisplayed()
        onNodeWithText("1. Yes").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals("allow", pickedOption)
    }
}
