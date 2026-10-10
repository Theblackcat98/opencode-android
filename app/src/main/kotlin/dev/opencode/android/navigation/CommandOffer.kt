package dev.opencode.android.navigation

import dev.opencode.android.core.data.tui.TuiControl

/**
 * What the app shows for a command the TUI asked it to run.
 *
 * [text] is the sentence to show. [performable] says whether the app may carry the command out itself, and it is
 * `false` for every command in this build (see [commandOffer]).
 */
internal data class CommandOffer(val text: String, val performable: Boolean)

/**
 * The message a TUI command becomes. Every command is shown, and none is performed.
 *
 * **Performing a command is not built.** No [TuiControl.AppAction] has a mapping to an app navigation or state
 * change yet, so [CommandOffer.performable] is `false` for all of them, a safe one included. A command that changes
 * server state (interrupt, background, compact, submit) must stay a message even once a mapping exists, because the
 * app cannot know whether the user is watching the session it would change.
 *
 * An unknown command is shown with its name exactly as the server sent it, because a name this build does not know
 * is the part the user needs to see. [unknownTemplate] and [offeredTemplate] are sentences from `strings.xml` with one
 * `%1$s` for the name. They are parameters because this function has no `Context`, and the name is the argument, not
 * the pattern, so a `%` in it is shown as written.
 */
internal fun commandOffer(
    command: TuiControl.Command,
    unknownTemplate: String,
    offeredTemplate: String,
): CommandOffer {
    val template = if (command.action == TuiControl.AppAction.UNKNOWN) unknownTemplate else offeredTemplate
    return CommandOffer(text = template.format(command.name), performable = false)
}
