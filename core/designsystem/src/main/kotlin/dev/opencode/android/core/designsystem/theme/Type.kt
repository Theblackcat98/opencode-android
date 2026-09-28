package dev.opencode.android.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.opencode.android.core.designsystem.R

/** JetBrains Mono (SIL OFL 1.1), bundled so code renders identically on every device and flavor. */
val CodeFontFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** Text styles for code, diffs, terminal output and tool input. Material 3 has no slot for these. */
@Immutable
data class CodeTypography(
    val body: TextStyle = TextStyle(fontFamily = CodeFontFamily, fontSize = 13.sp, lineHeight = 18.sp),
    val small: TextStyle = TextStyle(fontFamily = CodeFontFamily, fontSize = 11.sp, lineHeight = 15.sp),
    val inline: TextStyle = TextStyle(fontFamily = CodeFontFamily, fontSize = 14.sp),
)

val LocalCodeTypography: ProvidableCompositionLocal<CodeTypography> =
    staticCompositionLocalOf { CodeTypography() }

internal val OpenCodeTypography: Typography = Typography()
