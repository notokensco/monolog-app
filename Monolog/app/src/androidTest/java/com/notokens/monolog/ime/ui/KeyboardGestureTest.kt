package com.notokens.monolog.ime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.notokens.monolog.ime.SwipePoint
import com.notokens.monolog.ui.Glyph
import com.notokens.monolog.ui.theme.MonologTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeyboardGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun heldBackspaceRepeatsAndStopsOnRelease() {
        val deletes = AtomicInteger()
        compose.setContent {
            MonologTheme {
                Row(Modifier.size(60.dp)) {
                    KeyButton(Modifier.testTag("delete"), glyph = Glyph.BACKSPACE, onClick = { deletes.incrementAndGet() })
                }
            }
        }
        compose.onNodeWithTag("delete").performTouchInput { down(center) }
        compose.waitUntil(3_000) { deletes.get() >= 4 }
        compose.onNodeWithTag("delete").performTouchInput { up() }
        val released = deletes.get()
        Thread.sleep(200)
        assertEquals(released, deletes.get())
    }

    @Test fun cancelledBackspaceStopsRepeating() {
        val deletes = AtomicInteger()
        compose.setContent {
            MonologTheme {
                Row(Modifier.size(60.dp)) {
                    KeyButton(Modifier.testTag("delete"), glyph = Glyph.BACKSPACE, onClick = { deletes.incrementAndGet() })
                }
            }
        }
        compose.onNodeWithTag("delete").performTouchInput { down(center) }
        compose.waitUntil(3_000) { deletes.get() >= 3 }
        compose.onNodeWithTag("delete").performTouchInput { cancel() }
        val cancelled = deletes.get()
        Thread.sleep(200)
        assertEquals(cancelled, deletes.get())
    }

    @Test fun swipeClaimsGestureBeforeChildKeyAndTapStillWorks() {
        val taps = AtomicInteger()
        val swipes = mutableListOf<List<SwipePoint>>()
        compose.setContent {
            MonologTheme {
                Column(Modifier.fillMaxWidth().testTag("letters").then(swipeTypingModifier(true) { swipes.add(it) })) {
                    repeat(3) {
                        Row(Modifier.fillMaxWidth().height(54.dp)) {
                            repeat(10) {
                                KeyButton(Modifier.weight(1f), text = "a", onClick = { taps.incrementAndGet() })
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("letters").performTouchInput {
            down(Offset(width * .15f, height / 6f))
            moveTo(Offset(width * .25f, height / 6f), 70)
            moveTo(Offset(width * .55f, height / 2f), 70)
            moveTo(Offset(width * .85f, height / 6f), 70)
            up()
        }
        compose.runOnIdle {
            assertEquals(1, swipes.size)
            assertEquals(0, taps.get())
        }
        compose.onNodeWithTag("letters").performTouchInput {
            down(Offset(width * .15f, height / 6f))
            up()
        }
        compose.runOnIdle { assertEquals(1, taps.get()) }
        // Some devices coalesce a fast glide into a down and displaced up.
        compose.onNodeWithTag("letters").performTouchInput {
            down(Offset(width * .15f, height / 6f))
            updatePointerTo(0, Offset(width * .65f, height / 6f))
            up()
        }
        compose.runOnIdle {
            assertEquals(2, swipes.size)
            assertEquals(1, taps.get())
        }
    }
}
