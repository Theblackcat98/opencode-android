package dev.opencode.android.core.designsystem.text

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * An outlined text field whose text survives a view model that echoes it late.
 *
 * Handing a `String` from an asynchronously derived state straight to a field makes the field show
 * whatever the state held a moment ago, so fast typing, a paste or an autofill loses the characters in
 * between. This keeps the field's own value as the source of truth while the user types and only adopts
 * [value] when it came from somewhere else; see [TextSync].
 */
@Composable
fun SyncedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val sync = remember { TextSync() }
    LaunchedEffect(value) {
        sync.onState(value, field.text)?.let { adopted -> field = TextFieldValue(adopted, TextRange(adopted.length)) }
    }
    OutlinedTextField(
        value = field,
        onValueChange = { edited: TextFieldValue ->
            val changed = edited.text != field.text
            if (changed) sync.onEdited(edited.text)
            field = edited
            if (changed) onValueChange(edited.text)
        },
        modifier = modifier,
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
    )
}
