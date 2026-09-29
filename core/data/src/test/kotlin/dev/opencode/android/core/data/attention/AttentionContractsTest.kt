package dev.opencode.android.core.data.attention

import dev.opencode.android.core.data.forms.FieldProblem
import dev.opencode.android.core.data.forms.FormEngine
import dev.opencode.android.core.model.FormField
import dev.opencode.android.core.model.FormInfo
import dev.opencode.android.core.model.FormOption
import dev.opencode.android.core.model.LocationPublicRef
import dev.opencode.android.core.model.PermissionReply
import dev.opencode.android.core.model.SessionInfo
import dev.opencode.android.core.model.TokenUsage
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pieces of the attention layer that carry state between the app and the platform: the action
 * encoding, the request codes, the notification ids, quiet hours, and the unread marker.
 *
 * Each of these fails in a way a JVM test can see. Two actions sharing a request code means a button
 * that silently does the wrong thing; a form field that loses its rules on the way to the shade means
 * a required question answered blank; a quiet-hours window that mishandles midnight mutes nothing
 * after work; and a `session.view` sent at the wrong moment marks a turn the user never read.
 */
class AttentionContractsTest {

    // ------------------------------------------------------------------ action encoding

    @Test
    fun `every action variant survives a round trip through the intent encoding`() {
        val actions = listOf(
            AttentionAction.ReplyPermission("srv", "ses", "prm", PermissionReply.Once),
            AttentionAction.ReplyPermission("srv", "ses", "prm", PermissionReply.Always),
            AttentionAction.ReplyPermission("srv", "ses", "prm", PermissionReply.Reject),
            AttentionAction.AnswerForm("srv", "ses", "frm", "q0", listOf(field())),
            AttentionAction.AnswerForm("srv", "ses", "frm", null, emptyList()),
            AttentionAction.CancelForm("srv", "ses", "frm"),
            AttentionAction.OpenSession("srv", "ses"),
            AttentionAction.Interrupt("srv", "ses"),
        )
        val codes = AttentionActionCodes()
        actions.forEach { action ->
            val decoded = requireNotNull(AttentionActionCodec.decode(encodeOf(action)))
            assertEquals(action, decoded)
            assertEquals(codes.codeFor(action), codes.codeFor(decoded))
        }
    }

    @Test
    fun `a session id with a comma, a quote and a newline survives the encoding`() {
        // What a hand-rolled separator encoding gets wrong, and the reason the action is serialised
        // with the same JSON the rest of the app uses.
        val awkward = "A \"title\", with\nlines and | pipes"
        val action = AttentionAction.AnswerForm("srv", awkward, "frm", "q0", listOf(field()))
        assertEquals(action, AttentionActionCodec.decode(AttentionActionCodec.encode(action)))
    }

    @Test
    fun `a form title survives the encoding too`() {
        val action = AttentionAction.AnswerForm(
            "srv",
            "ses",
            "frm",
            "q0",
            listOf(EncodedFormField(key = "q0", type = "string", title = "Which, shell?\nSecond line")),
        )
        assertEquals(action, AttentionActionCodec.decode(AttentionActionCodec.encode(action)))
    }

    @Test
    fun `a payload this build cannot read is not an error`() {
        // It is a notification left by an older build, and throwing would take the receiver process
        // down for a button nobody pressed.
        assertNull(AttentionActionCodec.decode("{\"type\":\"reply\",\"serverId\":\"srv\"}"))
        assertNull(AttentionActionCodec.decode("not json at all"))
        assertNull(AttentionActionCodec.decode(""))
        assertNull(AttentionActionCodec.decode(null))
    }

    @Test
    fun `two distinct actions never share a request code`() {
        // The failure is invisible: the button works, it just fires the other action. Uniqueness is
        // therefore a property to assert over a corpus rather than a hope about a hash.
        val codes = AttentionActionCodes()
        val seen = HashMap<Int, AttentionAction>()
        val corpus = distinctActions()
        corpus.forEach { action ->
            val code = codes.codeFor(action)
            val clash = seen.put(code, action)
            assertNull("code $code is shared by $clash and $action", clash)
        }
        assertEquals(corpus.size, seen.size)
    }

    @Test
    fun `the corpus really is distinct, so the uniqueness test means something`() {
        val corpus = distinctActions()
        assertEquals(corpus.size, corpus.toSet().size)
        assertEquals(corpus.size, AttentionActionCodes().let { codes -> corpus.map { codes.codeFor(it) }.toSet().size })
    }

    @Test
    fun `the same action always gets the same request code`() {
        val codes = AttentionActionCodes()
        val action = AttentionAction.OpenSession("srv", "ses")
        assertEquals(codes.codeFor(action), codes.codeFor(AttentionAction.OpenSession("srv", "ses")))
    }

    @Test
    fun `two notifications that would replace each other never share an id`() {
        val ids = NotificationIds()
        val slots = allSlots()
        val seen = HashMap<Int, NotificationSlot>()
        slots.forEach { slot ->
            val id = ids.idFor(slot)
            val clash = seen.put(id, slot)
            assertNull("id $id is shared by $clash and $slot", clash)
        }
        assertEquals(slots.size, ids.size)
    }

    @Test
    fun `a permission and a form on one session get different ids`() {
        // The case a hash would plausibly collide on, and the one that would replace the permission
        // with the form without anyone noticing.
        val ids = NotificationIds()
        val permission = ids.idFor(NotificationSlot.Permission("srv", "x", "ses"))
        val form = ids.idFor(NotificationSlot.Form("srv", "x", "ses"))
        assertTrue(permission != form)
    }

    // ------------------------------------------------------------------ RemoteInput eligibility

    @Test
    fun `one free-text field is answerable from the shade`() {
        val only = RemoteInputSpec.singleFreeTextField(listOf(field()))
        assertNotNull(only)
        assertEquals("q0", only!!.key)
    }

    @Test
    fun `a hidden field does not count against the single-question rule`() {
        // The hidden one can never be answered anyway and the reply omits it, so the form is still
        // one question.
        val fields = listOf(field(), field(key = "hidden", hidden = true))
        assertEquals("q0", RemoteInputSpec.singleFreeTextField(fields)?.key)
    }

    @Test
    fun `a number, a boolean and a multiselect are never answered from the shade`() {
        assertNull(RemoteInputSpec.singleFreeTextField(listOf(numberField())))
        assertNull(RemoteInputSpec.singleFreeTextField(listOf(encodeField(FormField.BooleanField(key = "q0")))))
        assertNull(RemoteInputSpec.singleFreeTextField(listOf(encodeField(FormField.MultiselectField(key = "q0")))))
    }

    @Test
    fun `an encoded field keeps the rules the forms engine applies`() {
        val original = FormField.StringField(
            key = "q0",
            title = "Which shell?",
            required = true,
            minLength = 2,
            maxLength = 8,
            pattern = "^[a-z]+$",
            options = listOf(FormOption("bash", "bash"), FormOption("zsh", "zsh")),
            custom = true,
        )
        val restored = listOf(encodeField(original)).toFormFields().single()
        assertEquals(original, restored)
    }

    @Test
    fun `a shade answer is validated by the forms engine, not by the notification`() {
        // The point of carrying the fields: the same rule that would fail on screen fails in the
        // shade, and a required question cannot be answered with nothing.
        val form = FormInfo(
            id = "frm_1",
            sessionID = "ses_1",
            title = "Which shell?",
            fields = listOf(FormField.StringField(key = "q0", required = true, pattern = "^[a-z]+$")),
        )
        val fields = form.fields.map(::encodeField).let { encoded -> encoded.toFormFields() }

        assertNull(remoteInputAnswer("q0", "   "))
        assertEquals(
            mapOf("q0" to FieldProblem.REQUIRED),
            FormEngine.validate(fields, emptyMap()),
        )
        val good = remoteInputAnswer("q0", "bash")!!
        assertTrue(FormEngine.validate(fields, mapOf("q0" to good)).isEmpty())
        val bad = remoteInputAnswer("q0", "Bash 1")!!
        assertEquals(1, FormEngine.validate(fields, mapOf("q0" to bad)).size)
    }

    @Test
    fun `an unknown field type loses its rules, which is the honest outcome`() {
        val unknown = FormField.Unknown(declaredType = "future", key = "q0", raw = JsonObject(emptyMap()))
        val restored = listOf(encodeField(unknown)).toFormFields().single()
        assertTrue(restored is FormField.Unknown)
        assertEquals("future", (restored as FormField.Unknown).declaredType)
    }

    // ------------------------------------------------------------------ quiet hours

    @Test
    fun `a window that wraps midnight is quiet on both sides of it`() {
        val night = QuietHours.of(22 * 60, 7 * 60)
        assertTrue(night.isQuietAt(23 * 60))
        assertTrue(night.isQuietAt(0))
        assertTrue(night.isQuietAt(6 * 60 + 59))
        assertFalse(night.isQuietAt(7 * 60))
        assertFalse(night.isQuietAt(12 * 60))
        assertFalse(night.isQuietAt(21 * 60 + 59))
    }

    @Test
    fun `a window inside one day is quiet only inside it`() {
        val lunch = QuietHours.of(12 * 60, 13 * 60)
        assertFalse(lunch.isQuietAt(11 * 60 + 59))
        assertTrue(lunch.isQuietAt(12 * 60))
        assertTrue(lunch.isQuietAt(12 * 60 + 59))
        assertFalse(lunch.isQuietAt(13 * 60))
    }

    @Test
    fun `a disabled window is never quiet and has no label`() {
        val off = QuietHours()
        assertFalse(off.isQuietAt(3 * 60))
        assertNull(off.label())
    }

    @Test
    fun `a zero-length window is not a whole day`() {
        assertFalse(QuietHours.of(9 * 60, 9 * 60).isQuietAt(9 * 60 + 1))
    }

    @Test
    fun `the window is read against the device's own clock`() {
        // A quiet-hours window is a statement about the user's day, not about an instant, so the
        // offset is what turns an epoch time into a minute of their day.
        val day = 86_400_000L
        val midnightUtc = 1_700_000_000_000L / day * day
        val night = QuietHours.of(22 * 60, 7 * 60)
        assertTrue(night.isQuietAt(midnightUtc, utcOffsetMillis = 0L))
        assertFalse(night.isQuietAt(midnightUtc, utcOffsetMillis = 12 * 3_600_000L))
    }

    // ------------------------------------------------------------------ session.view

    @Test
    fun `an idle transition later than the last viewed one is what has to be marked`() {
        val mark = SessionViewMarker.requestFor(session(idle = 2_000L, viewed = 1_000L))
        assertNotNull(mark)
        assertEquals(2_000L, mark!!.idleAtMillis)
        assertEquals("ses_1", mark.sessionId)
    }

    @Test
    fun `a session the user has already seen is not marked again`() {
        assertNull(SessionViewMarker.requestFor(session(idle = 1_000L, viewed = 1_000L)))
        assertNull(SessionViewMarker.requestFor(session(idle = 1_000L, viewed = 2_000L)))
    }

    @Test
    fun `a session that has never gone idle is not marked`() {
        assertNull(SessionViewMarker.requestFor(session(idle = null, viewed = null)))
        assertNull(SessionViewMarker.requestFor(null))
    }

    @Test
    fun `a session with no viewed mark is unread as soon as it goes idle`() {
        // The plan's rule verbatim: `time.idle > time.viewed`, and a null `viewed` has never been seen.
        val mark = SessionViewMarker.requestFor(session(idle = 1_000L, viewed = null))
        assertEquals(1_000L, mark!!.idleAtMillis)
    }

    // ------------------------------------------------------------------ fixtures

    private fun encodeOf(action: AttentionAction): String = AttentionActionCodec.encode(action)

    /**
     * A corpus of distinct actions across every variant.
     *
     * Distinctness is part of the test rather than an accident: an entry that repeated would make
     * the uniqueness check pass for the wrong reason, so `the corpus really is distinct` asserts it.
     */
    private fun distinctActions(): List<AttentionAction> = buildList {
        for (server in 0 until 8) {
            for (session in 0 until 8) {
                for (target in 0 until 8) {
                    add(AttentionAction.ReplyPermission("srv$server", "ses$session", "tgt$target", PermissionReply.Once))
                    add(
                        AttentionAction.ReplyPermission(
                            "srv$server",
                            "ses$session",
                            "tgt$target",
                            PermissionReply.Always,
                        ),
                    )
                    add(AttentionAction.ReplyPermission("srv$server", "ses$session", "tgt$target", PermissionReply.Reject))
                    add(AttentionAction.AnswerForm("srv$server", "ses$session", "fgt$target", "q0", listOf(field())))
                    add(AttentionAction.CancelForm("srv$server", "ses$session", "cgt$target"))
                }
            }
            add(AttentionAction.OpenSession("srv$server", "ses"))
            add(AttentionAction.Interrupt("srv$server", "ses"))
        }
    }

    private fun allSlots(): List<NotificationSlot> = buildList {
        for (server in 0 until 6) {
            add(NotificationSlot.ServerSummary("srv$server"))
            add(NotificationSlot.ServerUpdate("srv$server", "2.0.19"))
            for (session in 0 until 6) {
                for (target in 0 until 6) {
                    add(NotificationSlot.Permission("srv$server", "pgt$target", "ses$session"))
                    add(NotificationSlot.Form("srv$server", "fgt$target", "ses$session"))
                    add(NotificationSlot.Confirmation("srv$server", "cgt$target", "ses$session"))
                    add(NotificationSlot.TurnFinished("srv$server", "ses$session", target.toLong()))
                    add(NotificationSlot.SubagentFinished("srv$server", "ses$session", target.toLong()))
                    add(NotificationSlot.Retry("srv$server", "ses$session", target, target.toLong()))
                }
            }
        }
    }

    private fun field(key: String = "q0", hidden: Boolean = false) = EncodedFormField(
        key = key,
        type = "string",
        title = "Which shell?",
        required = true,
        hidden = hidden,
        options = listOf("bash", "zsh"),
        custom = true,
    )

    private fun numberField() = EncodedFormField(key = "n", type = "number", required = true)

    private fun session(idle: Long?, viewed: Long?): SessionInfo = SessionInfo(
        id = "ses_1",
        time = SessionInfo.Time(created = 0L, updated = 0L, idle = idle, viewed = viewed),
        projectID = "prj_1",
        location = LocationPublicRef("/srv/project"),
        cost = 0.0,
        tokens = TokenUsage.Zero,
    )
}
