package com.notokens.monolog.ai

import kotlinx.coroutines.CancellationException

internal suspend fun <T> withAiFallback(online: suspend () -> T, offline: suspend () -> T): T =
    try {
        online()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        offline()
    }
