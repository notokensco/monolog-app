package com.notokens.monolog.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notokens.monolog.ui.theme.LocalMonologDark

/** Android vector equivalents of the original SVGs in Monolog/artwork/illustrations. */
@Composable
internal fun ScreenArtwork(@DrawableRes artwork: Int, modifier: Modifier = Modifier, opacity: Float = 1f) {
    Image(painterResource(artwork), contentDescription = null, modifier = modifier,
        alpha = if (LocalMonologDark.current) opacity * .88f else opacity)
}

/** Fills the corner behind card content. The surrounding glass surface clips the overhang. */
@Composable
internal fun BoxScope.CardCornerArtwork(@DrawableRes artwork: Int, size: Dp, opacity: Float = 1f) {
    Box(Modifier.matchParentSize()) {
        ScreenArtwork(artwork, Modifier.align(Alignment.BottomEnd).requiredSize(size)
            .offset(x = size * .20f, y = size * .23f), opacity = opacity)
    }
}
