package com.notokens.monolog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.R
import kotlinx.coroutines.delay

@Composable
internal fun MonologLaunchScreen(background: Color, ink: Color, onFinished: () -> Unit) {
    val finish = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        delay(1_050)
        finish.value()
    }

    Box(Modifier.fillMaxSize().background(background)) {
        Image(
            painter = painterResource(R.drawable.art_launch_brush),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(ink)
        )
        BoxWithConstraints(Modifier.align(Alignment.Center).widthIn(max = 540.dp).fillMaxWidth()
            .padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
            val branding = "monolog."
            val style = TextStyle(fontFamily = MonologWordmark, fontSize = 64.sp)
            val measurer = rememberTextMeasurer()
            val naturalSize = measurer.measure(branding, style, softWrap = false, maxLines = 1).size.width
            val available = with(LocalDensity.current) { maxWidth.toPx() }
            val scale = (available * .98f / naturalSize.coerceAtLeast(1)).coerceAtMost(1.2f)
            Text(branding, color = ink, style = style.copy(fontSize = style.fontSize * scale),
                textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
        }
        Text("your side of the day.", color = ink, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 32.dp, end = 32.dp, bottom = 46.dp), textAlign = TextAlign.Center)
    }
}
