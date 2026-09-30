package dev.opencode.android.feature.requests.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.action.ActionErrorKind
import dev.opencode.android.core.data.forms.FieldProblem
import dev.opencode.android.feature.requests.R

/**
 * The failure classes a driving action reports, as the text a user reads
 * (plan §4.2: a failed write changes nothing except an error).
 *
 * The mapping lives in the feature module, not in `core:data`, so the transport stays free of
 * user-facing text and the wording can change without touching it. `ActionErrorKind` is an enum
 * precisely so this mapping stays exhaustive: a class the data layer invents fails the build here
 * instead of showing a blank.
 */
@StringRes
fun ActionErrorKind.messageRes(): Int = when (this) {
    ActionErrorKind.UNAUTHORIZED -> R.string.action_error_unauthorized
    ActionErrorKind.FORBIDDEN -> R.string.action_error_forbidden
    ActionErrorKind.NOT_FOUND -> R.string.action_error_not_found
    ActionErrorKind.CONFLICT -> R.string.action_error_conflict
    ActionErrorKind.SESSION_BUSY -> R.string.action_error_session_busy
    ActionErrorKind.INVALID_REQUEST -> R.string.action_error_invalid
    ActionErrorKind.SERVER -> R.string.action_error_server
    ActionErrorKind.OFFLINE -> R.string.action_error_offline
    ActionErrorKind.UNKNOWN -> R.string.action_error_unknown
}

/**
 * [this] worded for a person: the class's sentence, with the server's own words where the class takes them.
 *
 * One reading of an [ActionError] for the screens that show one, so a failure reads the same in the global
 * inbox as it does under the composer.
 */
@Composable
fun ActionError.displayMessage(): String =
    if (takesArgument) stringResource(kind.messageRes(), message) else stringResource(kind.messageRes())

/**
 * True for the two classes whose message carries the server's own words.
 *
 * An invalid request names the field it objected to and an unknown failure has nothing better to
 * show, so both interpolate [ActionError.message]; the rest are complete sentences on their own.
 */
val ActionErrorKind.takesArgument: Boolean
    get() = this == ActionErrorKind.INVALID_REQUEST || this == ActionErrorKind.UNKNOWN

/** Whether an error of this class needs [ActionError.message] interpolated into it. */
val ActionError.takesArgument: Boolean get() = kind.takesArgument

/**
 * The string resource a field's problem is reported with.
 *
 * [count] is the field's `minItems` or `maxItems`, which only the two item-count problems use; the
 * renderer passes it from the field rather than from the problem, because the problem alone does not
 * know the number.
 */
@StringRes
fun FieldProblem.messageRes(): Int = when (this) {
    FieldProblem.REQUIRED -> R.string.form_error_required
    FieldProblem.PATTERN -> R.string.form_error_pattern
    FieldProblem.TOO_SHORT -> R.string.form_error_too_short
    FieldProblem.TOO_LONG -> R.string.form_error_too_long
    FieldProblem.BELOW_MINIMUM -> R.string.form_error_below_minimum
    FieldProblem.ABOVE_MAXIMUM -> R.string.form_error_above_maximum
    FieldProblem.NOT_AN_INTEGER -> R.string.form_error_not_an_integer
    FieldProblem.NOT_AN_OPTION -> R.string.form_error_not_an_option
    FieldProblem.TOO_FEW -> R.string.form_error_too_few
    FieldProblem.TOO_MANY -> R.string.form_error_too_many
}

/** True for the problems whose string names how many options the field wants. */
val FieldProblem.takesCount: Boolean
    get() = this == FieldProblem.TOO_FEW || this == FieldProblem.TOO_MANY
