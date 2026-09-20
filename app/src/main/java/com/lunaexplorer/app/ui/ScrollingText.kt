package com.lunaexplorer.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import kotlinx.coroutines.delay

@Composable
internal fun ScrollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val scroll = rememberScrollState()
    val distance = scroll.maxValue
    LaunchedEffect(text, distance) {
        if (distance <= 0) return@LaunchedEffect
        delay(BEFORE_START)
        while (true) {
            scroll.animateScrollTo(distance, tween(travelMillis(distance), easing = { it }))
            delay(AT_END)
            scroll.scrollTo(0)
            delay(BEFORE_REPEAT)
        }
    }
    Row(modifier.horizontalScroll(scroll, enabled = false)) {
        Text(text, style = style, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
    }
}

private fun travelMillis(distance: Int): Int =
    ((distance / PX_PER_SECOND) * 1000f).toInt().coerceIn(600, 20_000)

private const val PX_PER_SECOND = 40f
private const val BEFORE_START = 1_200L
private const val AT_END = 1_600L
private const val BEFORE_REPEAT = 900L
