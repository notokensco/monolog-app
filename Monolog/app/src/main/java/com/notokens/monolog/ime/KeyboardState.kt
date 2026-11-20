package com.notokens.monolog.ime

enum class ShiftState {
    OFF,
    ONCE,
    CAPS_LOCK;

    val isUppercase: Boolean
        get() = this == ONCE || this == CAPS_LOCK
}

enum class KeyboardLayer {
    LETTERS,
    SYMBOLS,
    MORE_SYMBOLS,
    EMOJI
}

data class KeyboardState(
    val layer: KeyboardLayer = KeyboardLayer.LETTERS,
    val shiftState: ShiftState = ShiftState.OFF
) {
    fun cycleShift(): KeyboardState {
        val nextShift = when (shiftState) {
            ShiftState.OFF -> ShiftState.ONCE
            ShiftState.ONCE -> ShiftState.CAPS_LOCK
            ShiftState.CAPS_LOCK -> ShiftState.OFF
        }
        return copy(shiftState = nextShift)
    }

    fun onLetterTyped(): KeyboardState {
        return if (shiftState == ShiftState.ONCE) {
            copy(shiftState = ShiftState.OFF)
        } else {
            this
        }
    }

    fun toggleSymbols(): KeyboardState {
        val nextLayer = if (layer == KeyboardLayer.LETTERS) {
            KeyboardLayer.SYMBOLS
        } else {
            KeyboardLayer.LETTERS
        }
        return copy(layer = nextLayer)
    }

    fun toggleMoreSymbols(): KeyboardState {
        val nextLayer = if (layer == KeyboardLayer.SYMBOLS) {
            KeyboardLayer.MORE_SYMBOLS
        } else {
            KeyboardLayer.SYMBOLS
        }
        return copy(layer = nextLayer)
    }

    fun toggleEmoji(): KeyboardState {
        return if (layer == KeyboardLayer.EMOJI) switchToLetters() else copy(layer = KeyboardLayer.EMOJI)
    }

    fun switchToLetters(): KeyboardState = copy(layer = KeyboardLayer.LETTERS)
}
