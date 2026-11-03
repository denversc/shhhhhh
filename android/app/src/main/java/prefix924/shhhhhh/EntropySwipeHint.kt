package prefix924.shhhhhh

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Idle time (no entropy input) before the first swipe hint plays, and the start-to-start period.
 */
internal const val SWIPE_HINT_IDLE_MS = 5_000L

/** Duration of a single swipe hint animation. */
internal const val SWIPE_HINT_DURATION_MS = 1_000L

/** Vertical amplitude of the S-curve, as a fraction of the box height. */
internal const val SWIPE_HINT_AMPLITUDE_FRACTION = 0.15f

/** Horizontal inset of the path from the box's left and right edges, as a fraction of width. */
internal const val SWIPE_HINT_INSET_FRACTION = 0.15f

/** Length of the fading trail behind the fingertip, as a fraction of the path's progress. */
private const val SWIPE_HINT_TRAIL_LENGTH = 0.3f
private const val SWIPE_HINT_TRAIL_SEGMENTS = 24
private const val SWIPE_HINT_STATIC_SEGMENTS = 48
private const val SWIPE_HINT_ICON_ALPHA = 0.6f
private const val SWIPE_HINT_TRAIL_ALPHA = 0.5f

/**
 * Fraction of the icon's size where the fingertip of [Icons.Filled.TouchApp] sits, so that the
 * fingertip (rather than the icon's top-left corner) follows the path.
 */
private val SWIPE_HINT_FINGERTIP_FRACTION = Offset(0.4f, 0.12f)

/** Cubic ease-in-out: accelerates toward the middle and decelerates toward the end. */
internal fun swipeHintEaseInOut(t: Float): Float {
  val x = t.coerceIn(0f, 1f)
  return if (x < 0.5f) {
    4f * x * x * x
  } else {
    val inv = -2f * x + 2f
    1f - inv * inv * inv / 2f
  }
}

/**
 * Returns the point on the swipe hint "wave" path for a horizontal fraction [u] in `[0, 1]`.
 *
 * The path runs left to right, inset from the edges by [SWIPE_HINT_INSET_FRACTION] of the width,
 * along one full sine period (an S-curve) through the vertical center with an amplitude of
 * [SWIPE_HINT_AMPLITUDE_FRACTION] of the height.
 */
internal fun swipeHintWavePoint(u: Float, width: Float, height: Float): Offset {
  val clamped = u.coerceIn(0f, 1f)
  val inset = width * SWIPE_HINT_INSET_FRACTION
  val x = inset + clamped * (width - 2f * inset)
  val y = height / 2f - height * SWIPE_HINT_AMPLITUDE_FRACTION * sin(2.0 * PI * clamped).toFloat()
  return Offset(x, y)
}

/**
 * Returns the delay, in milliseconds, from [idleMs] (time since the last input) until the next
 * swipe hint animation should start. Animations start at 5 s, 10 s, 15 s, ... after the last input.
 */
internal fun swipeHintDelayUntilNextStartMs(idleMs: Long): Long {
  if (idleMs < SWIPE_HINT_IDLE_MS) return SWIPE_HINT_IDLE_MS - idleMs
  val sinceLastStart = (idleMs - SWIPE_HINT_IDLE_MS) % SWIPE_HINT_IDLE_MS
  return if (sinceLastStart == 0L) 0L else SWIPE_HINT_IDLE_MS - sinceLastStart
}

/**
 * Returns the swipe hint animation phase in `[0, 1)` for the given idle time, or `null` if no
 * animation should be playing at that moment.
 */
internal fun swipeHintPhase(idleMs: Long): Float? {
  if (idleMs < SWIPE_HINT_IDLE_MS) return null
  val sinceLastStart = (idleMs - SWIPE_HINT_IDLE_MS) % SWIPE_HINT_IDLE_MS
  if (sinceLastStart >= SWIPE_HINT_DURATION_MS) return null
  return sinceLastStart.toFloat() / SWIPE_HINT_DURATION_MS.toFloat()
}

/** Overall opacity envelope of the hint: fades in and out over the first and last 15%. */
internal fun swipeHintEnvelope(phase: Float): Float {
  val p = phase.coerceIn(0f, 1f)
  return when {
    p < 0.15f -> p / 0.15f
    p > 0.85f -> (1f - p) / 0.15f
    else -> 1f
  }
}

/**
 * Purely visual overlay showing a pointing hand swiping along a wave with a fading trail.
 *
 * Has no pointer input handling, so touches pass through to the entropy box beneath it.
 *
 * @param phase the animation phase in `[0, 1]`.
 * @param reduceMotion if true, the hand does not move; the hand and a static wave trail fade in and
 *   out in place instead.
 */
@Composable
internal fun EntropySwipeHint(
  phase: Float,
  reduceMotion: Boolean,
  trailColor: Color,
  iconColor: Color,
  modifier: Modifier = Modifier,
) {
  val painter = rememberVectorPainter(Icons.Filled.TouchApp)
  Canvas(modifier = modifier) {
    val strokeWidthPx = 3.dp.toPx()
    val iconSizePx = 48.dp.toPx()

    val fingertip: Offset
    if (reduceMotion) {
      val alpha = sin(PI * phase.coerceIn(0f, 1f)).toFloat()
      var previous = swipeHintWavePoint(0f, size.width, size.height)
      for (i in 1..SWIPE_HINT_STATIC_SEGMENTS) {
        val point =
          swipeHintWavePoint(
            i.toFloat() / SWIPE_HINT_STATIC_SEGMENTS.toFloat(),
            size.width,
            size.height,
          )
        drawLine(
          color = trailColor.copy(alpha = SWIPE_HINT_TRAIL_ALPHA * alpha),
          start = previous,
          end = point,
          strokeWidth = strokeWidthPx,
          cap = StrokeCap.Round,
        )
        previous = point
      }
      fingertip = swipeHintWavePoint(1f, size.width, size.height)
      drawHandIcon(painter, iconColor, fingertip, iconSizePx, SWIPE_HINT_ICON_ALPHA * alpha)
    } else {
      val envelope = swipeHintEnvelope(phase)
      val head = swipeHintEaseInOut(phase)
      val tail = (head - SWIPE_HINT_TRAIL_LENGTH).coerceAtLeast(0f)
      if (head > tail) {
        var previous = swipeHintWavePoint(tail, size.width, size.height)
        for (i in 1..SWIPE_HINT_TRAIL_SEGMENTS) {
          val fraction = i.toFloat() / SWIPE_HINT_TRAIL_SEGMENTS.toFloat()
          val point = swipeHintWavePoint(tail + (head - tail) * fraction, size.width, size.height)
          drawLine(
            color = trailColor.copy(alpha = SWIPE_HINT_TRAIL_ALPHA * envelope * fraction),
            start = previous,
            end = point,
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
          )
          previous = point
        }
      }
      fingertip = swipeHintWavePoint(head, size.width, size.height)
      drawHandIcon(painter, iconColor, fingertip, iconSizePx, SWIPE_HINT_ICON_ALPHA * envelope)
    }
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandIcon(
  painter: androidx.compose.ui.graphics.painter.Painter,
  iconColor: Color,
  fingertip: Offset,
  iconSizePx: Float,
  alpha: Float,
) {
  val topLeft =
    Offset(
      fingertip.x - iconSizePx * SWIPE_HINT_FINGERTIP_FRACTION.x,
      fingertip.y - iconSizePx * SWIPE_HINT_FINGERTIP_FRACTION.y,
    )
  translate(left = topLeft.x, top = topLeft.y) {
    with(painter) {
      draw(
        size = Size(iconSizePx, iconSizePx),
        alpha = alpha.coerceIn(0f, 1f),
        colorFilter = ColorFilter.tint(iconColor),
      )
    }
  }
}
