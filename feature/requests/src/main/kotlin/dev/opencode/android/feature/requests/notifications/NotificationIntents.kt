package dev.opencode.android.feature.requests.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.opencode.android.core.data.attention.AttentionAction
import dev.opencode.android.core.data.attention.AttentionActionCodec
import dev.opencode.android.core.data.attention.AttentionActionCodes
import dev.opencode.android.core.data.attention.EncodedFormField

/**
 * How a notification action travels from the shade to the receiver (plan §6, Phase 4).
 *
 * **One encoded string, because that is what an `Intent` extra can be.** The action is a sealed union
 * whose `AnswerForm` variant carries a form's whole field list, and a hand-rolled encoding of that is
 * the kind of thing that works until a field title contains the separator. So the action is serialised
 * with the same JSON the rest of the app uses and decoded back with the same union, and the only
 * thing this class adds is the round trip through an `Intent` and the `PendingIntent` flags.
 *
 * **The broadcast is explicit.** The receiver exists to no other app, and an implicit one would be a
 * message any installed app could send carrying "answer this permission" as a payload.
 */
object NotificationIntents {

    /** The extra every action rides in. Namespaced so it cannot collide with a launcher's own. */
    const val EXTRA_ACTION: String = "dev.opencode.android.extra.ATTENTION_ACTION"

    /** The receiver's action string. */
    const val ACTION_PERFORM: String = "dev.opencode.android.action.PERFORM_ATTENTION_ACTION"

    /** What the activity is asked to do when a notification body is tapped. */
    const val ACTION_OPEN_SESSION: String = "dev.opencode.android.action.OPEN_SESSION"

    const val EXTRA_SERVER_ID: String = "dev.opencode.android.extra.SERVER_ID"
    const val EXTRA_SESSION_ID: String = "dev.opencode.android.extra.SESSION_ID"

    fun encode(action: AttentionAction): String = AttentionActionCodec.encode(action)

    /** An encoding this build can read, or `null`; see [AttentionActionCodec.decode]. */
    fun decode(encoded: String?): AttentionAction? = AttentionActionCodec.decode(encoded)

    /** The intent the receiver receives for [action]. */
    fun actionIntent(context: Context, action: AttentionAction): Intent = Intent(ACTION_PERFORM).apply {
        setClass(context, NotificationActionReceiver::class.java)
        putExtra(EXTRA_ACTION, encode(action))
    }

    /** The action an intent carries, or `null` when it carries something else. */
    fun readAction(intent: Intent?): AttentionAction? = decode(intent?.getStringExtra(EXTRA_ACTION))

    /**
     * The text the user typed into a notification, under the field the action names.
     *
     * Read through [RemoteInput.getResultsFromIntent] rather than as a plain extra: that is where the
     * platform puts an inline reply, and it is the only path that works on every supported version.
     */
    fun readRemoteInput(intent: Intent?, key: String?): String? {
        if (key == null) return null
        val results = RemoteInput.getResultsFromIntent(intent ?: return null) ?: return null
        return results.getCharSequence(key)?.toString()
    }

    /** The field an [AttentionAction.AnswerForm] answers, which is what pairs the text with a key. */
    fun answeredKey(action: AttentionAction?): String? = (action as? AttentionAction.AnswerForm)?.fieldKey

    /** The form's fields, as the receiver needs them to validate the answer. */
    fun fieldsOf(action: AttentionAction?): List<EncodedFormField> =
        (action as? AttentionAction.AnswerForm)?.fields.orEmpty()

    /**
     * The pending intent that opens a session from a notification body.
     *
     * **The launcher activity is resolved through the package manager rather than named here.** The
     * notification code lives in a feature module and the activity in the app, so naming the class
     * would invert the dependency; asking for the package's own launch intent is also correct across
     * the `play` and `fdroid` flavors, which have different application ids in a debug build.
     */
    /** The package's own launch intent, used as a shortcut's target. */
    fun launchIntent(context: Context): Intent =
        (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(ACTION_OPEN_SESSION))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun openSession(
        context: Context,
        serverId: String,
        sessionId: String,
        codes: AttentionActionCodes,
    ): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(ACTION_OPEN_SESSION)).apply {
            action = ACTION_OPEN_SESSION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_SERVER_ID, serverId)
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            codes.codeFor(AttentionAction.OpenSession(serverId, sessionId)),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
