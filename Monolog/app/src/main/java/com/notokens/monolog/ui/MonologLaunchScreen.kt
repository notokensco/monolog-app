package com.notokens.monolog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.R

@Composable
internal fun MonologLaunchScreen(onFinished: () -> Unit) {
    val timeline = remember { Animatable(0f) }
    val finish = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        timeline.animateTo(1f, tween(1_050, easing = LinearEasing))
        finish.value()
    }

    Box(Modifier.fillMaxSize().launchAurora(colorResource(R.color.monolog_launch_background)),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                LaunchCard({ timeline.value }, .04f, 9f, Color(0xFFAFBDDF))
                LaunchCard({ timeline.value }, .15f, 6f, Color(0xFFCAD4EB))
                LaunchCard({ timeline.value }, .26f, 3f, Color(0xFFE0E7F4))
                LaunchCard({ timeline.value }, .38f, 0f, Color(0xFFF0F3FC)) {
                    Image(
                        painterResource(R.drawable.ic_monolog_monochrome),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val mark = launchStage(timeline.value, .50f, .22f)
                            alpha = mark
                            scaleX = .94f + .06f * mark
                            scaleY = scaleX
                        },
                        colorFilter = ColorFilter.tint(Color(0xFF151B2B))
                    )
                }
            }
            Spacer(Modifier.height(15.dp))
            Column(Modifier.graphicsLayer {
                val wordmark = launchStage(timeline.value, .52f, .24f)
                alpha = wordmark
                translationY = (1f - wordmark) * 7.dp.toPx()
            }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Monolog", color = Color(0xFFF6F5FF), fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.headlineLarge, letterSpacing = (-1).sp)
                Spacer(Modifier.height(6.dp))
                Text("your side of the day.", color = Color(0xFFCFD4E5),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LaunchCard(progress: () -> Float, start: Float, inset: Float, tint: Color,
    content: @Composable () -> Unit = {}) {
    val shape = RoundedCornerShape(23.dp)
    Box(Modifier.size(106.dp).graphicsLayer {
        val arrived = launchStage(progress(), start, .34f)
        alpha = arrived
        translationX = (inset + (1f - arrived) * 16f - 4f).dp.toPx()
        translationY = (inset + (1f - arrived) * 24f - 4f).dp.toPx()
        scaleX = .9f + .1f * arrived
        scaleY = scaleX
        rotationZ = (1f - arrived) * 8f
        this.shape = shape
        shadowElevation = 3.dp.toPx()
    }.background(Brush.verticalGradient(listOf(Color.White, tint)), shape)
        .border(1.dp, Color(0x997683AA), shape)) {
        content()
    }
}

private fun launchStage(progress: Float, start: Float, span: Float) =
    FastOutSlowInEasing.transform(((progress - start) / span).coerceIn(0f, 1f))

private fun Modifier.launchAurora(background: Color) = drawWithCache {
    val w = size.width
    val h = size.height
    val spread = maxOf(w, h * .64f)
    val mint = Color(0xFF52D8B4)
    val iris = Color(0xFF9E82F2)
    val ice = Color(0xFF568DE1)
    val top = Brush.radialGradient(listOf(mint.copy(alpha = .26f), Color.Transparent),
        Offset(w * .86f, h * .13f), spread * .78f)
    val bottom = Brush.radialGradient(listOf(iris.copy(alpha = .27f), Color.Transparent),
        Offset(w * .10f, h * .89f), spread)
    val crown = Path().apply {
        moveTo(-w * .15f, h * .32f)
        cubicTo(w * .12f, h * .08f, w * .49f, h * .33f, w * .73f, h * .21f)
        quadraticTo(w * .94f, h * .12f, w * 1.12f, h * .02f)
    }
    val tail = Path().apply {
        moveTo(-w * .08f, h * .96f)
        cubicTo(w * .29f, h * .72f, w * .44f, h * .86f, w * .67f, h * .76f)
        quadraticTo(w * .93f, h * .66f, w * 1.08f, h * .57f)
    }
    val crownInk = Brush.linearGradient(listOf(Color.Transparent, ice.copy(alpha = .34f),
        mint.copy(alpha = .56f), Color.Transparent), Offset(0f, h * .28f), Offset(w, h * .10f))
    val tailInk = Brush.linearGradient(listOf(Color.Transparent, iris.copy(alpha = .48f),
        ice.copy(alpha = .27f), Color.Transparent), Offset(0f, h * .91f), Offset(w, h * .62f))
    onDrawBehind {
        drawRect(background)
        drawRect(top)
        drawRect(bottom)
        for ((path, color, contour) in listOf(Triple(crown, mint, crownInk), Triple(tail, iris, tailInk))) {
            for (width in listOf(54.dp, 34.dp, 19.dp)) {
                drawPath(path, color.copy(alpha = .045f), style = Stroke(width.toPx(), cap = StrokeCap.Round))
            }
            drawPath(path, contour, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
