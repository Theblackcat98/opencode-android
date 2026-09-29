package dev.opencode.android.core.data.attention

import dev.opencode.android.core.data.execution.FinishedShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finished shell command, as a notification (plan §6, "Shell commands"; plan §4, Phase 4).
 *
 * **The exit criterion this covers is "a background subagent can be watched, and its completion is
 * notified"**, and its shell half: a build the user started from the phone finishes while the app is
 * somewhere else, and the shade says so. The reconciler is where that decision is made, so it is where
 * it is tested — the coordinator, the channels and the notification renderer are the parts P4 already
 * covers for every other kind.
 */
class ShellCompletionAttentionTest {

    private val finished = FinishedShell(
        id = "sh_1",
        command = "npm run build",
        status = "exited",
        exitCode = 1,
        directory = "/work",
        completedAtMillis = 1_000L,
    )

    @Test
    fun `a finished command the user is not watching notifies`() {
        val drafts = AttentionReconciler.draftsFor(state(finishedShells = listOf(finished)))
        val draft = drafts.single { it.channel == AttentionChannel.SHELL_FINISHED }
        assertEquals(
            NotificationSlot.ShellFinished("srv", "sh_1", "/work", 1_000L),
            draft.slot,
        )
        assertEquals(
            NotificationContent.ShellFinished(
                sessionId = "",
                command = "npm run build",
                status = "exited",
                exitCode = 1,
                directory = "/work",
            ),
            draft.content,
        )
    }

    @Test
    fun `the action opens the checkout's command panel, not a session`() {
        val draft = AttentionReconciler.draftsFor(state(finishedShells = listOf(finished)))
            .single { it.channel == AttentionChannel.SHELL_FINISHED }
        assertEquals(
            listOf(AttentionAction.OpenLocation("srv", "/work")),
            draft.actions,
        )
        // The slot belongs to a directory, so the shade groups it under the server rather than under a
        // conversation that does not exist.
        assertEquals(NotificationSlot.summaryGroupOf("srv"), draft.slot.group)
    }

    @Test
    fun `a command in the location whose panel is on screen notifies nothing`() {
        // The user is watching the output arrive; a notification for it would be the notification
        // equivalent of telling someone the light is on in a room they are standing in.
        val drafts = AttentionReconciler.draftsFor(
            state(finishedShells = listOf(finished), openDirectory = "/work"),
        )
        assertTrue(drafts.none { it.channel == AttentionChannel.SHELL_FINISHED })
    }

    @Test
    fun `a command in another location still notifies while a panel is open`() {
        val drafts = AttentionReconciler.draftsFor(
            state(finishedShells = listOf(finished.copy(directory = "/other")), openDirectory = "/work"),
        )
        assertEquals(1, drafts.count { it.channel == AttentionChannel.SHELL_FINISHED })
    }

    @Test
    fun `quiet hours suppress a command, because it is information and not a blocked request`() {
        val drafts = AttentionReconciler.draftsFor(
            state(
                finishedShells = listOf(finished),
                quietHours = QuietHours(enabled = true, startMinuteOfDay = 0, endMinuteOfDay = 1_439),
                now = noon(),
            ),
        )
        assertTrue(drafts.none { it.channel == AttentionChannel.SHELL_FINISHED })
    }

    @Test
    fun `the same state twice produces the same drafts, so a resync does not re-notify`() {
        val current = state(finishedShells = listOf(finished))
        val first = AttentionReconciler.reconcile(current, emptyList())
        val second = AttentionReconciler.reconcile(current, first.posts)
        assertEquals(1, first.added.size)
        assertTrue(second.isEmpty)
    }

    @Test
    fun `a second run of the same command is a second slot`() {
        // The slot carries the completion instant, so a command that is run again after finishing posts
        // a new notification rather than looking unchanged — the same reason a turn does.
        val first = AttentionReconciler.draftsFor(state(finishedShells = listOf(finished)))
        val again = AttentionReconciler.draftsFor(
            state(finishedShells = listOf(finished.copy(completedAtMillis = 2_000L))),
        )
        assertTrue(
            first.single { it.channel == AttentionChannel.SHELL_FINISHED }.slot !=
                again.single { it.channel == AttentionChannel.SHELL_FINISHED }.slot,
        )
    }

    @Test
    fun `a shell notification is not blocking, so auto-approve does not touch it`() {
        assertTrue(!AttentionChannel.SHELL_FINISHED.blocking)
    }

    private fun noon(): Long = 1_700_000_000_000L

    private fun state(
        finishedShells: List<FinishedShell>,
        openDirectory: String? = null,
        quietHours: QuietHours = QuietHours(),
        now: Long = 0L,
    ) = AttentionState(
        serverId = "srv",
        serverName = "Server",
        finishedShells = finishedShells.map {
            AttentionShell(
                id = it.id,
                command = it.command,
                status = it.status,
                exitCode = it.exitCode,
                directory = it.directory,
                completedAtMillis = it.completedAtMillis,
            )
        },
        openDirectory = openDirectory,
        quietHours = quietHours,
        nowMillis = now,
        utcOffsetMillis = 0L,
    )
}
