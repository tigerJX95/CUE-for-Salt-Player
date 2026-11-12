package spw.cue

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CueMiniSeekbar(
    modifier: Modifier,
    window: CuePlaybackWindow,
    positionMs: Long,
    color: Color,
    radiusDp: Float,
    paddingDp: Float,
    onSeek: (Float) -> Boolean
) {
    val density = LocalDensity.current
    val radius = with(density) { radiusDp.dp.toPx() }
    val padding = with(density) { paddingDp.dp.toPx() }
    val elapsed = window.elapsedMs(positionMs)
    val progress = (elapsed.toDouble() / window.durationMs).toFloat()
    val liveProgress by rememberUpdatedState(progress)
    var hover by remember(window) { mutableStateOf<SeekPoint?>(null) }
    var drag by remember(window) { mutableStateOf<SeekPoint?>(null) }

    val hitHeight = (paddingDp * 2).dp
    Box(
        modifier.fillMaxWidth().height(radiusDp.dp + hitHeight)
            .drawWithCache {
                val stroke = Stroke(1.dp.toPx().coerceAtLeast(1.5f))
                val left = stroke.width / 2
                val right = (size.width - left).coerceAtLeast(left)
                val corner = radius.coerceIn(0f, (right - left) / 2)
                val path = Path().apply {
                    if (corner == 0f) {
                        moveTo(left, padding)
                        lineTo(right, padding)
                    } else {
                        arcTo(Rect(left, padding, left + 2 * corner, padding + 2 * corner), 180f, 90f, true)
                        lineTo(right - corner, padding)
                        arcTo(Rect(right - 2 * corner, padding, right, padding + 2 * corner), 270f, 90f, false)
                    }
                }
                onDrawBehind {
                    val fraction = drag?.fraction ?: liveProgress
                    drawPath(path, color.copy(alpha = .18f), style = stroke)
                    if (fraction > 0f) {
                        clipRect(right = size.width * fraction) { drawPath(path, color, style = stroke) }
                    }
                }
            }
    ) {
        // Compose selects a pointer target before dispatching the event. Keep the
        // decorative arcs outside the input node so the buttons below can be hit.
        Box(
            Modifier.fillMaxWidth().height(hitHeight)
                .onPointerEvent(PointerEventType.Move) { event ->
                    val point = event.changes.firstOrNull()?.position
                    hover = point?.takeIf { it.x in 0f..size.width.toFloat() && it.y in 0f..size.height.toFloat() }
                        ?.let { SeekPoint.at(it.x, size.width) }
                }
                .onPointerEvent(PointerEventType.Exit) { hover = null }
                .pointerInput(window) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!currentEvent.buttons.isPrimaryPressed ||
                            down.position.x !in 0f..size.width.toFloat() ||
                            down.position.y !in 0f..size.height.toFloat()
                        ) {
                            return@awaitEachGesture
                        }
                        down.consume()
                        drag = SeekPoint.at(down.position.x, size.width)
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                val point = SeekPoint.at(change.position.x, size.width)
                                drag = point
                                if (change.changedToUp()) {
                                    change.consume()
                                    hover = point.takeIf {
                                        change.position.x in 0f..size.width.toFloat() &&
                                            change.position.y in 0f..size.height.toFloat()
                                    }
                                    onSeek(point.fraction)
                                    break
                                }
                                if (!change.pressed) break
                                change.consume()
                            }
                        } finally {
                            drag = null
                        }
                    }
                }
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    stateDescription = "${formatCueTime(elapsed)} / ${formatCueTime(window.durationMs)}"
                    setProgress(action = onSeek)
                }
        )
        // The pointer position and the popup anchor must both be relative to this seekbar.
        (drag ?: hover)?.let { point ->
            val preview = (point.fraction * window.durationMs.toDouble()).toLong()
            val background = if (color.luminance() > .5f) Color(0xff292929) else Color(0xffeeeeee)
            val gap = with(density) { 10.dp.roundToPx() }
            val provider = remember(point.x, gap) { CueSeekTooltipPosition(point.x, gap) }
            Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
                BasicText(
                    "${formatCueTime(preview)} / ${formatCueTime(window.durationMs)}",
                    Modifier.background(background, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    style = TextStyle(color = color, fontSize = 12.sp)
                )
            }
        }
    }
}

private data class SeekPoint(val fraction: Float, val x: Int) {
    companion object {
        fun at(x: Float, width: Int): SeekPoint {
            val clamped = x.coerceIn(0f, width.toFloat())
            return SeekPoint(if (width == 0) 0f else clamped / width, clamped.roundToInt())
        }
    }
}

internal class CueSeekTooltipPosition(private val pointerX: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize
    ): IntOffset {
        val left = anchorBounds.left + pointerX - popupContentSize.width / 2
        val top = anchorBounds.top - gap - popupContentSize.height
        return IntOffset(
            left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        )
    }
}
