package com.notokens.monolog.ime.vox

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.notokens.monolog.R

internal val VoxWordmarkFont = FontFamily(Font(R.font.science_gothic_vox, FontWeight.SemiBold))

@Composable
internal fun ActiveVoxLabel() {
    Text(buildAnnotatedString {
        append("Active in ")
        withStyle(SpanStyle(fontFamily = VoxWordmarkFont, fontWeight = FontWeight.SemiBold)) { append("VOX") }
    }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
}
