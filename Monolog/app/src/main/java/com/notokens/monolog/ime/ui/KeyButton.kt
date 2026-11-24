package com.notokens.monolog.ime.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick as semanticsClick
import androidx.compose.ui.semantics.onLongClick as semanticsLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notokens.monolog.ui.theme.PaperAccentBlue
import com.notokens.monolog.ui.theme.PaperKeyActive
import com.notokens.monolog.ui.theme.PaperKeySubText
import com.notokens.monolog.ui.theme.PaperKeyText
import com.notokens.monolog.ui.theme.PaperPillBackground
import com.notokens.monolog.ui.theme.PaperPillBorder
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.MonologIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class KeyAppearance(
    val borders: Boolean = false,
    val popups: Boolean = false,
    val popupMs: Int = 70,
    val vibration: Boolean = true,
    val longPressMs: Int = 350,
    val swipeTrailDp: Int = 120,
    val compact: Boolean = false
)
class CursorDrag(val start: () -> Unit, val move: (Int) -> Unit, val end: () -> Unit)

internal data class CursorKeys(
    val drag: CursorDrag? = null,
    val active: Boolean = false,
    val character: String? = null
)
internal val LocalCursorKeys = staticCompositionLocalOf { CursorKeys() }
internal val LocalPreviewOnly = staticCompositionLocalOf { false }
internal val LocalKeyAppearance = staticCompositionLocalOf { KeyAppearance() }
internal val LocalVibrationHaptics = staticCompositionLocalOf { true }

internal fun triggerHapticFeedback(view: View, isLong: Boolean = false, enabled: Boolean = true) {
    if (!enabled) return
    view.performHapticFeedback(if (isLong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
}

@Composable
fun KeyButton(
    modifier: Modifier = Modifier,
    text: String? = null,
    subText: String? = null,
    fontSize: TextUnit = 23.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    isSpecial: Boolean = false,
    isAccent: Boolean = false,
    isActive: Boolean = false,
    isPill: Boolean = false,
    contentColor: Color? = null,
    glyph: Glyph? = null,
    secondaryGlyph: Glyph? = null,
    label: String? = null,
    previewText: String? = null,
    alternates: List<String> = emptyList(),
    onAlternate: ((String) -> Unit)? = null,
    longClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    cursorDrag: CursorDrag? = null,
    repeatOnHold: Boolean = glyph == Glyph.BACKSPACE,
    repeatIntervalMs: Long = 55,
    faceStart: androidx.compose.ui.unit.Dp = 0.dp,
    faceEnd: androidx.compose.ui.unit.Dp = 0.dp,
    glow: Boolean = false,
    tracing: Boolean = false,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null
) {
    val view = LocalView.current
    val previewOnly = LocalPreviewOnly.current
    val appearance = LocalKeyAppearance.current
    val cursor = LocalCursorKeys.current
    val keyCharacter = (previewText ?: text)?.takeIf { it.length == 1 }
    val dimmed = cursor.active && (keyCharacter == null || !keyCharacter.equals(cursor.character, ignoreCase = true))
    val currentDrag by rememberUpdatedState(cursorDrag)
    var isPressed by remember { mutableStateOf(false) }
    val controller by rememberUpdatedState(LocalKeyPreview.current)
    val popupDelay by rememberUpdatedState(appearance.popupMs)
    val holdDelay by rememberUpdatedState(appearance.longPressMs)
    val tapPreviews by rememberUpdatedState(appearance.popups)
    val alternateChoices by rememberUpdatedState(alternates)
    val pickAlternate by rememberUpdatedState(onAlternate)
    val hasAlternates = alternates.isNotEmpty() && onAlternate != null
    val legend by rememberUpdatedState(previewText ?: text ?: when(glyph) {
        Glyph.SHIFT -> "⇧"
        Glyph.BACKSPACE -> "⌫"
        Glyph.ENTER -> "↵"
        else -> label?.take(16) ?: glyph?.name?.lowercase()?.replaceFirstChar(Char::uppercaseChar)
    })
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val tap by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onLongClick)

    val baseBg = when {
        isAccent -> PaperAccentBlue
        tracing && !appearance.borders -> PaperPillBackground
        isActive -> PaperKeyActive
        isPill -> PaperPillBackground
        isPressed -> PaperKeyActive.copy(alpha = 0.8f)
        else -> Color.Transparent
    }

    val finalBg = if (isPressed && !isAccent && !(tracing && !appearance.borders)) {
        PaperKeyActive
    } else if (appearance.borders && !isPill && baseBg == Color.Transparent) {
        PaperPillBackground
    } else {
        baseBg
    }

    val textColor = contentColor ?: when {
        isAccent -> MaterialTheme.colorScheme.onPrimary
        isActive -> PaperAccentBlue
        isSpecial -> PaperKeyText.copy(alpha = 0.85f)
        else -> PaperKeyText
    }

    val pillModifier = if (isPill && !appearance.borders && !tracing) {
        Modifier.border(if (isActive) 2.dp else 1.dp,
            if (isActive) MaterialTheme.colorScheme.primary else PaperPillBorder, RoundedCornerShape(24.dp))
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(if (LocalKeyPreview.current != null && (appearance.popups || hasAlternates))
                Modifier.onGloballyPositioned { bounds = it.boundsInRoot() } else Modifier)
            .then(if (previewOnly) Modifier else Modifier
            .semantics {
                val description = label ?: glyph?.name?.lowercase()
                if (description != null) contentDescription = description
                semanticsClick { onClick(); true }
                if (onLongClick != null) semanticsLongClick(longClickLabel) { onLongClick(); true }
                if (hasAlternates) {
                    customActions = alternates.map { alternate ->
                        CustomAccessibilityAction("Type $alternate") { onAlternate?.invoke(alternate); true }
                    }
                }
            }
            .pointerInput(onLongClick != null, hasAlternates, cursorDrag != null, repeatOnHold, repeatIntervalMs) {
                coroutineScope {
                    class Press(val preview: KeyPreview.Preview?, val start: Offset,
                        var action: Job? = null, var handled: Boolean = false) {
                        var drag: CursorDrag? = null
                        var offset = 0f
                        var selecting = false
                        var latest = start
                        var holdStart = start
                    }
                    val stepWidth = 18.dp.toPx()
                    val cellWidth = 42.dp.toPx()
                    val popupGap = 3.dp.toPx()
                    val selectionSlop = 6.dp.toPx()
                    val cancelBelow = 24.dp.toPx()
                    val presses = mutableMapOf<PointerId, Press>()
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                for (change in event.changes) {
                                    val active = presses[change.id]
                                    if (active != null && (!change.pressed || change.isConsumed)) {
                                        presses.remove(change.id)
                                        active.action?.cancel()
                                        active.drag?.end?.invoke()
                                        val completed = !change.pressed && !change.isConsumed
                                        if (active.selecting) {
                                            if (completed) {
                                                if ((change.position - active.holdStart).getDistance() >= selectionSlop) {
                                                    controller?.move(active, bounds.topLeft + change.position, bounds.bottom + cancelBelow)
                                                }
                                                change.consume()
                                            }
                                            controller?.finishHold(active, completed)?.let { pickAlternate?.invoke(it) }
                                        } else {
                                            controller?.up(active, active.preview.takeIf { completed && (!active.handled || repeatOnHold) })
                                        }
                                        if (completed && !active.handled) {
                                            change.consume()
                                            tap()
                                        }
                                    } else if (active?.drag != null) {
                                        active.offset += change.position.x - change.previousPosition.x
                                        val steps = (active.offset / stepWidth).toInt()
                                        if (steps != 0) {
                                            active.offset -= steps * stepWidth
                                            active.drag?.move?.invoke(steps)
                                        }
                                        change.consume()
                                    } else if (active != null) {
                                        active.latest = change.position
                                        if (active.selecting) {
                                            if ((change.position - active.holdStart).getDistance() >= selectionSlop) {
                                                controller?.move(active, bounds.topLeft + change.position, bounds.bottom + cancelBelow)
                                            }
                                            change.consume()
                                        }
                                    } else if (active == null && change.pressed && !change.previousPressed && !change.isConsumed) {
                                        change.consume()
                                        val press = Press(legend?.takeIf { tapPreviews }?.let { KeyPreview.Preview(it, bounds) },
                                            change.position, handled = repeatOnHold)
                                        press.preview?.let { controller?.down(press, it, popupDelay) }
                                        presses[change.id] = press
                                        triggerHapticFeedback(view, enabled = appearance.vibration)
                                        if (repeatOnHold) {
                                            tap()
                                            press.action = launch {
                                                delay(350)
                                                while (true) {
                                                    tap()
                                                    delay(repeatIntervalMs)
                                                }
                                            }
                                        } else if (hasAlternates || hold != null || currentDrag != null) {
                                            press.action = launch {
                                                delay(holdDelay.toLong())
                                                press.handled = true
                                                controller?.up(press, null)
                                                triggerHapticFeedback(view, isLong = true, enabled = appearance.vibration)
                                                if (hasAlternates) {
                                                    press.holdStart = press.latest
                                                    press.selecting = controller?.hold(press, bounds, alternateChoices,
                                                        cellWidth, cellWidth, popupGap) == true
                                                    return@launch
                                                }
                                                press.drag = currentDrag
                                                if (press.drag != null) press.drag?.start?.invoke()
                                                else hold?.invoke()
                                            }
                                        }
                                    }
                                }
                                isPressed = presses.isNotEmpty()
                            }
                        }
                    } finally {
                        presses.values.forEach {
                            it.action?.cancel()
                            it.drag?.end?.invoke()
                            if (it.selecting) controller?.finishHold(it, false) else controller?.up(it, null)
                        }
                        isPressed = false
                    }
                }
            }
            )
            .padding(horizontal = if (appearance.borders && appearance.compact) 3.5.dp else if (appearance.borders) 2.dp else 1.dp,
                vertical = if (appearance.borders && appearance.compact) 4.dp else if (appearance.borders) 2.dp else if (isPill) 7.dp else 2.dp)
            .padding(start = faceStart, end = faceEnd)
            .aiKeyTracing(tracing, if (appearance.borders) if (isPill) 12.dp else 9.dp else if (isPill) 24.dp else 8.dp)
            .activeKeyGlow(glow, circular = true)
            .then(if (appearance.borders) Modifier.materialKey(
                if (isAccent) finalBg else lerp(
                    (if (finalBg == Color.Transparent) PaperPillBackground else finalBg)
                        .compositeOver(MaterialTheme.colorScheme.surface),
                    MaterialTheme.colorScheme.primaryContainer,
                    if (isPressed || isActive) .19f else if (isPill) .38f else .31f
                ),
                isPressed, isPill
            ) else Modifier.clip(RoundedCornerShape(if (isPill) 24.dp else 8.dp))
                .then(pillModifier).background(finalBg))
            .then(if (dimmed) Modifier.blur(2.6.dp) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (glyph != null) {
            MonologIcon(glyph, Modifier.size(23.dp), textColor)
            if (secondaryGlyph != null) {
                MonologIcon(secondaryGlyph, Modifier.align(Alignment.TopEnd).padding(3.dp).size(13.dp), PaperKeySubText)
            }
        } else if (content != null) {
            content()
        } else if (text != null) {
            if (subText != null) {
                Text(
                    text = subText,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp),
                    color = PaperKeySubText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 10.sp
                )
            }
            Text(
                text = text,
                color = textColor,
                fontSize = fontSize,
                fontWeight = fontWeight,
                textAlign = TextAlign.Center
            )
        }
    }
}


private fun Modifier.materialKey(face: Color, pressed: Boolean, wide: Boolean): Modifier {
    val radius = if (wide) 12.dp else 9.dp
    return shadow(if (pressed) 0.5.dp else 2.dp, RoundedCornerShape(radius), clip = false)
        .drawBehind {
            val corners = CornerRadius(radius.toPx())
            val depth = if (pressed) 1.dp.toPx() else 3.dp.toPx()
            val step = if (pressed) 1.5.dp.toPx() else 0f
            drawRoundRect(lerp(face, Color.Black, .33f), cornerRadius = corners)
            val faceSize = Size(size.width, (size.height - depth - step).coerceAtLeast(1f))
            drawRoundRect(
                Brush.verticalGradient(listOf(lerp(face, Color.White, .23f), face, lerp(face, Color.Black, .075f)),
                    startY = step, endY = step + faceSize.height),
                topLeft = Offset(0f, step), size = faceSize, cornerRadius = corners
            )
            val inset = .45.dp.toPx()
            if (faceSize.width > inset * 2 && faceSize.height > inset * 2) {
                drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .32f), Color.Black.copy(alpha = .08f))),
                    topLeft = Offset(inset, step + inset), size = Size(faceSize.width - 2 * inset, faceSize.height - 2 * inset),
                    cornerRadius = corners, style = Stroke(inset * 2))
            }
        }
}
