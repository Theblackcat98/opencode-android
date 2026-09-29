package dev.opencode.android.core.data.tui

import dev.opencode.android.core.model.event.Event
import dev.opencode.android.core.model.event.EventPayload
import dev.opencode.android.core.model.event.TuiCommandExecute
import dev.opencode.android.core.model.event.TuiPromptAppend
import dev.opencode.android.core.model.event.TuiSessionSelect
import dev.opencode.android.core.model.event.TuiToastShow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the TUI asks the app to do (features doc §36, `tui.*`).
 *
 * **These events are the desktop client talking to the phone, not the phone reacting to the
 * server.** The same server serves the TUI, a web app and this one, and a plugin or a user driving
 * the TUI can make the server broadcast a control event to every connected client. So a TUI event
 * is a *request from somewhere else*, and the app treats it as such:
 *
 *  - a toast becomes a snackbar, because a message the user was told to expect and did not see is
 *    indistinguishable from one the app dropped;
 *  - session selection and prompt text fill in the composer, but only while "follow desktop" is on,
 *    because otherwise the app would take over a session the user is reading;
 *  - a command maps to an app action, and the ones that would be dangerous to do without the user
 *    watching (interrupting a running turn) are surfaced rather than performed.
 *
 * Nothing here is durable and nothing here is a guess about server state: it is a one-shot
 * instruction, and the server's events remain the only source of truth.
 */
class TuiControl {
    private val _toasts = MutableSharedFlow<Toast>(replay = 0, extraBufferCapacity = 32)
    private val _commands = MutableSharedFlow<Command>(replay = 0, extraBufferCapacity = 32)
    private val _promptAppends = MutableSharedFlow<PromptAppend>(replay = 0, extraBufferCapacity = 32)
    private val _sessionSelections = MutableSharedFlow<SessionSelect>(replay = 0, extraBufferCapacity = 32)
    private val _rpcEvents = MutableStateFlow<List<RpcEvent>>(emptyList())

    private val _followDesktop = MutableStateFlow(false)

    /** Toasts the server asked for, for a snackbar. */
    val toasts: SharedFlow<Toast> = _toasts.asSharedFlow()

    /** Commands the server asked the app to carry out. */
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    /** Text the TUI put in its composer, for the app's composer while [followDesktop] is on. */
    val promptAppends: SharedFlow<PromptAppend> = _promptAppends.asSharedFlow()

    /** The session the TUI opened. */
    val sessionSelections: SharedFlow<SessionSelect> = _sessionSelections.asSharedFlow()

    /** Plugin RPC events, newest last, for the event-history viewer. */
    val rpcEvents: StateFlow<List<RpcEvent>> = _rpcEvents.asStateFlow()

    /**
     * Whether the app follows what the TUI is doing.
     *
     * **Off by default.** Filling the composer and jumping between sessions are two ways to lose
     * what the user was typing, and neither is what someone opened the phone to do. It is a setting
     * they turn on when they are deliberately mirroring the desktop.
     */
    val followDesktop: StateFlow<Boolean> = _followDesktop.asStateFlow()

    fun setFollowDesktop(enabled: Boolean) {
        _followDesktop.value = enabled
    }

    /** Applies one event. Returns `true` when this event was a TUI control event. */
    fun apply(event: Event): Boolean {
        val payload = event.payload
        return when (payload) {
            is TuiToastShow -> {
                _toasts.tryEmit(Toast.from(payload, event.location?.directory))
                true
            }

            is TuiCommandExecute -> {
                val command = Command.of(payload.command, event.location?.directory)
                _commands.tryEmit(command)
                true
            }

            is TuiPromptAppend -> {
                // Ignored while following is off, and the setting is checked here rather than in the
                // collector so that a late subscriber cannot apply a prompt the user turned it off for.
                if (_followDesktop.value) {
                    _promptAppends.tryEmit(PromptAppend(payload.text, event.location?.directory))
                }
                true
            }

            is TuiSessionSelect -> {
                if (_followDesktop.value) {
                    _sessionSelections.tryEmit(SessionSelect(payload.sessionID, event.location?.directory))
                }
                true
            }

            is EventPayload.Rpc -> {
                _rpcEvents.value = (_rpcEvents.value + RpcEvent.from(payload, event)).takeLast(RPC_HISTORY)
                true
            }

            else -> false
        }
    }

    fun clear() {
        _rpcEvents.value = emptyList()
    }

    /**
     * A toast, with the server's four variant names resolved to a level a snackbar can style.
     *
     * A variant this build does not know becomes [ToastLevel.INFO] rather than being dropped: the
     * message is the part the user needs, and the styling is not.
     */
    data class Toast(
        val title: String?,
        val message: String,
        val level: ToastLevel,
        val directory: String?,
    ) {
        companion object {
            fun from(payload: TuiToastShow, directory: String?): Toast = Toast(
                title = payload.title,
                message = payload.message,
                level = when (payload.variant.value) {
                    "success" -> ToastLevel.SUCCESS
                    "warning" -> ToastLevel.WARNING
                    "error" -> ToastLevel.ERROR
                    else -> ToastLevel.INFO
                },
                directory = directory,
            )
        }
    }

    /** How a toast reads, independent of the four names the server uses. */
    enum class ToastLevel { INFO, SUCCESS, WARNING, ERROR }

    /** A command the TUI ran, mapped to what the app can do about it. */
    data class Command(
        val name: String,
        val action: AppAction,
        val directory: String?,
    ) {
        /**
         * Whether the app may carry this out on its own.
         *
         * **A command that would change server state, or that the user would want to see happen, is
         * offered rather than performed.** Interrupting a running turn from a phone nobody is holding
         * is exactly the surprise plan §5.2 warns about, and the app cannot know whether the user is
         * reading the session the TUI is driving.
         */
        val isSafeToPerform: Boolean get() = action.performsServerChange.not()

        companion object {
            /**
             * Maps a TUI command name to an app action.
             *
             * The published list is a union with `(string & {})`, so a newer TUI sends names this
             * build has never heard of; those become [AppAction.Unknown] and are shown verbatim
             * rather than dropped, because "the desktop did something this phone cannot" is worth
             * saying.
             */
            fun of(name: String, directory: String?): Command =
                Command(name, AppAction.of(name), directory)
        }
    }

    /** What a TUI command asks the app to do. */
    enum class AppAction {
        /** Open the session list. */
        SESSION_LIST,

        /** Start a new session. */
        SESSION_NEW,

        /** Interrupt the running turn. Changes server state. */
        SESSION_INTERRUPT,

        /** Move blocking tools to the background. Changes server state. */
        SESSION_BACKGROUND,

        /** Compact the session's history. Changes server state. */
        SESSION_COMPACT,

        /** Clear the composer. */
        PROMPT_CLEAR,

        /** Submit what is in the composer. */
        PROMPT_SUBMIT,

        /** Move to the next agent. */
        AGENT_CYCLE,

        /** Scroll the timeline. The app performs these itself; the server change is nil. */
        SCROLL_UP,
        SCROLL_DOWN,
        SCROLL_TO_TOP,
        SCROLL_TO_BOTTOM,

        /**
         * A command this build does not know.
         *
         * **Never safe to perform**, because the app cannot know what it does. A newer TUI may send
         * anything, and performing a command whose effect is unknown is how an app breaks
         * something on a server it does not understand.
         */
        UNKNOWN,
        ;

        val performsServerChange: Boolean
            get() = this in setOf(SESSION_INTERRUPT, SESSION_BACKGROUND, SESSION_COMPACT, PROMPT_SUBMIT, UNKNOWN)

        companion object {
            private val BY_NAME = mapOf(
                "session.list" to SESSION_LIST,
                "session.new" to SESSION_NEW,
                "session.interrupt" to SESSION_INTERRUPT,
                "session.background" to SESSION_BACKGROUND,
                "session.compact" to SESSION_COMPACT,
                "session.page.up" to SCROLL_UP,
                "session.page.down" to SCROLL_DOWN,
                "session.line.up" to SCROLL_UP,
                "session.line.down" to SCROLL_DOWN,
                "session.half.page.up" to SCROLL_UP,
                "session.half.page.down" to SCROLL_DOWN,
                "session.first" to SCROLL_TO_TOP,
                "session.last" to SCROLL_TO_BOTTOM,
                "prompt.clear" to PROMPT_CLEAR,
                "prompt.submit" to PROMPT_SUBMIT,
                "agent.cycle" to AGENT_CYCLE,
            )

            fun of(name: String): AppAction = BY_NAME[name] ?: UNKNOWN
        }
    }

    data class PromptAppend(val text: String, val directory: String?)

    data class SessionSelect(val sessionID: String, val directory: String?)

    /** One `rpc.<rpcID>.<event>` frame, kept for the developer viewer. */
    data class RpcEvent(
        val rpcID: String,
        val event: String,
        val data: Map<String, kotlinx.serialization.json.JsonElement>,
        val at: Long,
    ) {
        /** The type as it arrived on the wire. */
        val type: String get() = "rpc.$rpcID.$event"

        companion object {
            fun from(payload: EventPayload.Rpc, event: Event): RpcEvent = RpcEvent(
                rpcID = payload.rpcID,
                event = payload.event,
                data = payload.data,
                at = event.created ?: 0L,
            )
        }
    }

    companion object {
        /** How many plugin RPC events the viewer keeps. */
        const val RPC_HISTORY = 200
    }
}
