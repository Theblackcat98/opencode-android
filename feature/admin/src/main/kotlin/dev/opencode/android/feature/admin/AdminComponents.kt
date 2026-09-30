package dev.opencode.android.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.opencode.android.core.data.action.ActionError
import dev.opencode.android.core.data.config.DocumentParseFailure
import dev.opencode.android.core.data.config.SchemaDiagnostic
import dev.opencode.android.core.data.config.WritePlan
import dev.opencode.android.core.designsystem.text.SyncedTextField

/**
 * The dialog every write in this phase goes through.
 *
 * **It names the file, says what changes, and leads with a privilege warning when there is one.** Plan
 * §5.2 requires explicit confirmation for an experimental file write, and a permissions or agent
 * definition edit is a privilege change: it decides what the agent may do. So the plan carries a
 * `consequence` written from the specific edit rather than a generic "save?", and this dialog shows the
 * target path, the byte counts before and after, and — when [WritePlan.isPrivilegeChange] is set — a
 * sentence that says the change alters what the agent can do.
 *
 * **The dialog is not a place a value is shown.** It renders [WritePlan.target], which is a path the
 * user chose, and the plan's own summary. It never prints the document: a `opencode.jsonc` can hold an
 * API key, and a confirmation dialog is a screenshot waiting to be taken.
 */
@Composable
fun WriteConfirmationDialog(
    plan: WritePlan,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(AdminTags.CONFIRM_WRITE),
        title = {
            Text(
                text = if (plan.isPrivilegeChange) {
                    stringResource(R.string.admin_write_privilege_title)
                } else {
                    stringResource(R.string.admin_write_title)
                },
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = plan.consequence)
                Text(
                    text = plan.target,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (plan.isPrivilegeChange) {
                    Text(
                        text = stringResource(R.string.admin_write_privilege_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(AdminTags.CONFIRM_WRITE),
                    )
                }
                val previous = plan.previousBytes
                Text(
                    // Three cases, because a plan can be for a file this app writes, a file the server
                    // changes, or a target whose existence nobody asked about. Only the second has no
                    // length to quote, and calling it "a new file of 0 bytes" named the server's own
                    // working configuration a file that was about to be created.
                    text = when {
                        plan.isServerSide -> stringResource(R.string.admin_write_size_setting)
                        previous == null -> stringResource(R.string.admin_write_size_new, plan.bytes)
                        else -> stringResource(R.string.admin_write_size, previous, plan.bytes)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(AdminTags.CONFIRM_WRITE),
            ) { Text(stringResource(R.string.admin_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(AdminTags.CANCEL_WRITE)) {
                Text(stringResource(R.string.admin_cancel))
            }
        },
    )
}

/**
 * One validation problem, with its location.
 *
 * **The path and the line are the actionable part, so they lead.** A diagnostic without a location
 * cannot be acted on from a phone, and the line is only attached when the key is unambiguous — see
 * `SchemaDiagnostic.withLine` — so its absence is a statement rather than a gap.
 *
 * **The rejected value is never printed.** A configuration file holds API keys, and a validator that
 * quoted what it rejected would put one in a screenshot of this list.
 */
@Composable
fun DiagnosticRow(diagnostic: SchemaDiagnostic, modifier: Modifier = Modifier) {
    val location = when {
        diagnostic.line != null && diagnostic.column != null ->
            stringResource(R.string.admin_diagnostic_at, diagnostic.line!!, diagnostic.column!!)

        else -> stringResource(R.string.admin_diagnostic_where, diagnostic.path.ifEmpty { "/" })
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(AdminTags.diagnostic(diagnostic.path))
            // Read as one sentence, so TalkBack does not read five disconnected fragments.
            .semantics {
                contentDescription = "$location, ${diagnostic.keyword}: ${diagnostic.expected}"
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = location,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.admin_diagnostic_expected, diagnostic.keyword, diagnostic.expected),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.admin_diagnostic_found, diagnostic.found),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The syntax error of a document, which is a different kind of problem from a schema one.
 *
 * **A file that cannot be read is a different situation from a file that is wrong**, and collapsing the
 * two would make a missing brace and a misspelled key the same message. It also says what it is without
 * quoting the bytes, because the parser's own text includes the document.
 */
@Composable
fun ParseFailureRow(failure: DocumentParseFailure, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.admin_syntax_at, failure.line, failure.column),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(text = failure.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * A failure of a call, by class, with the server's own message beside it.
 *
 * **The message is shown but the request is not**, and that is the same rule P8 applied: an
 * `HttpException` carries the request URL, which for this phase would carry a path into a user's home
 * directory, and the class is what decides whether a retry is worth offering.
 */
@Composable
fun ErrorLine(error: ActionError, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.admin_error_class, error.kind.name.lowercase().replace('_', ' ')),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (error.message.isNotBlank()) {
                Text(text = error.message, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.admin_dismiss)) }
    }
}

/**
 * A field with an error under it, which is how every form in this phase reports a problem.
 *
 * Every value it shows comes from a view model, so it is a [SyncedTextField]: a paste or a fast burst of
 * typing must not be rewound to the echo of an older keystroke.
 */
@Composable
fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    supporting: String? = null,
    tag: String? = null,
) {
    SyncedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        singleLine = singleLine,
        minLines = minLines,
        supportingText = when {
            error != null -> ({ Text(error) })
            supporting != null -> ({ Text(supporting) })
            else -> null
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(if (tag == null) Modifier else Modifier.testTag(tag)),
    )
}

/** A section heading, marked as a heading for TalkBack and separated from what follows it. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        HorizontalDivider()
    }
}

/** A short fact about a row: a label and a value, in the monospace face when it is a path. */
@Composable
fun Fact(label: String, value: String, monospace: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

/**
 * Whether [path] is worth showing in full, or should be shortened to its last segment.
 *
 * **A long absolute path does not fit a phone row, and a path is the one thing a user has to be able to
 * read exactly.** So the row shows the tail and the dialog quotes the whole thing: the tail is enough to
 * recognise a file, the full path is what a user needs when they are deciding whether to write to it.
 */
internal fun shortPath(path: String, maxSegments: Int = 3): String {
    if (path.length <= 48) return path
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.size <= maxSegments) return path
    return segments.takeLast(maxSegments).joinToString("/", prefix = "…/")
}
