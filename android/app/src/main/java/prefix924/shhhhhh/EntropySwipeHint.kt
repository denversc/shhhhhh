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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Idle time (no entropy input) before the first swipe hint plays, and the start-to-start period of
 * subsequent swipe hint cycles.
 */
internal const val SWIPE_HINT_IDLE_MS = 5_000L

/** Duration of the sweep at the start of each swipe hint cycle. */
internal const val SWIPE_HINT_SWEEP_MS = 2_000L

/**
 * Duration of the quick fade-out of the parked hand at the end of each cycle, finishing exactly
 * when the next sweep starts.
 */
internal const val SWIPE_HINT_HAND_FADE_OUT_MS = 200L

/** Vertical amplitude of the S-curve, as a fraction of the box height. */
internal const val SWIPE_HINT_AMPLITUDE_FRACTION = 0.15f

/** Horizontal inset of the path from the box's left and right edges, as a fraction of width. */
internal const val SWIPE_HINT_INSET_FRACTION = 0.15f

/** Fraction of the sweep over which the hand and trail fade in, and the trail fades out. */
private const val SWIPE_HINT_SWEEP_FADE_FRACTION = 0.15f

/** Fraction of the sweep over which the hand fades in, in reduce-motion mode. */
private const val SWIPE_HINT_REDUCE_MOTION_FADE_IN_FRACTION = 0.5f

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
 * Returns the time, in milliseconds, into the current swipe hint cycle for the given idle time
 * (time since the last input), or `null` if the hint should not be shown yet.
 *
 * Cycles start at 5 s, 10 s, 15 s, ... after the last input; once the first cycle starts the hint
 * stays visible (the hand parks at the end of the path between sweeps) until the next input.
 */
internal fun swipeHintCycleMs(idleMs: Long): Long? {
  if (idleMs < SWIPE_HINT_IDLE_MS) return null
  return (idleMs - SWIPE_HINT_IDLE_MS) % SWIPE_HINT_IDLE_MS
}

/**
 * Returns how long, in milliseconds, the hint can wait from [idleMs] before the next frame with a
 * visual change is needed: before the first cycle, and while the hand is parked fully visible,
 * nothing changes, so there is no need to render every frame. Returns 0 while animating.
 */
internal fun swipeHintIdleDelayMs(idleMs: Long): Long {
  val cycleMs = swipeHintCycleMs(idleMs) ?: return SWIPE_HINT_IDLE_MS - idleMs
  val fadeOutStartMs = SWIPE_HINT_IDLE_MS - SWIPE_HINT_HAND_FADE_OUT_MS
  return if (cycleMs in SWIPE_HINT_SWEEP_MS until fadeOutStartMs) fadeOutStartMs - cycleMs else 0L
}

/** What to draw for one frame of the swipe hint. All values are in `[0, 1]`. */
internal data class SwipeHintFrame(
  /** Horizontal fraction along the wave path at which the fingertip sits. */
  val handProgress: Float,
  /** Opacity multiplier for the hand icon. */
  val handAlpha: Float,
  /** Path fraction where the trail starts (its faded tail). */
  val trailStart: Float,
  /** Path fraction where the trail ends (its opaque head). */
  val trailEnd: Float,
  /** Opacity multiplier for the trail. */
  val trailAlpha: Float,
)

/**
 * Computes the [SwipeHintFrame] for [cycleMs] milliseconds into a swipe hint cycle.
 * - Sweep (first [SWIPE_HINT_SWEEP_MS]): the hand fades in and moves left to right with ease-in-out
 *   followed by a fading trail. In [reduceMotion] mode the hand does not move: it fades in at the
 *   end of the path, and the full static wave fades in and back out.
 * - Park: the hand stays fully visible at the end of the path; no trail.
 * - Final [SWIPE_HINT_HAND_FADE_OUT_MS]: the parked hand fades out, finishing as the next sweep
 *   starts.
 */
internal fun swipeHintFrame(cycleMs: Long, reduceMotion: Boolean): SwipeHintFrame {
  val fadeOutStartMs = SWIPE_HINT_IDLE_MS - SWIPE_HINT_HAND_FADE_OUT_MS
  if (cycleMs >= SWIPE_HINT_SWEEP_MS) {
    val handAlpha =
      if (cycleMs < fadeOutStartMs) {
        1f
      } else {
        (1f - (cycleMs - fadeOutStartMs).toFloat() / SWIPE_HINT_HAND_FADE_OUT_MS.toFloat())
          .coerceIn(0f, 1f)
      }
    return SwipeHintFrame(
      handProgress = 1f,
      handAlpha = handAlpha,
      trailStart = 1f,
      trailEnd = 1f,
      trailAlpha = 0f,
    )
  }

  val t = (cycleMs.toFloat() / SWIPE_HINT_SWEEP_MS.toFloat()).coerceIn(0f, 1f)
  if (reduceMotion) {
    return SwipeHintFrame(
      handProgress = 1f,
      handAlpha = (t / SWIPE_HINT_REDUCE_MOTION_FADE_IN_FRACTION).coerceAtMost(1f),
      trailStart = 0f,
      trailEnd = 1f,
      trailAlpha = sin(PI * t).toFloat(),
    )
  }

  val fadeIn = (t / SWIPE_HINT_SWEEP_FADE_FRACTION).coerceAtMost(1f)
  val fadeOut = ((1f - t) / SWIPE_HINT_SWEEP_FADE_FRACTION).coerceAtMost(1f)
  val head = swipeHintEaseInOut(t)
  return SwipeHintFrame(
    handProgress = head,
    handAlpha = fadeIn,
    trailStart = (head - SWIPE_HINT_TRAIL_LENGTH).coerceAtLeast(0f),
    trailEnd = head,
    trailAlpha = minOf(fadeIn, fadeOut),
  )
}

/**
 * Purely visual overlay showing a pointing hand swiping along a wave with a fading trail.
 *
 * Has no pointer input handling, so touches pass through to the entropy box beneath it.
 *
 * @param cycleMs the time, in milliseconds, into the current swipe hint cycle.
 * @param reduceMotion if true, the hand does not move; the hand and a static wave trail fade in
 *   place instead.
 */
@Composable
internal fun EntropySwipeHint(
  cycleMs: Long,
  reduceMotion: Boolean,
  trailColor: Color,
  iconColor: Color,
  modifier: Modifier = Modifier,
) {
  val painter = rememberVectorPainter(Icons.Filled.TouchApp)
  Canvas(modifier = modifier) {
    val frame = swipeHintFrame(cycleMs, reduceMotion)
    val strokeWidthPx = 3.dp.toPx()
    val iconSizePx = 48.dp.toPx()

    if (frame.trailAlpha > 0f && frame.trailEnd > frame.trailStart) {
      // In reduce-motion mode the static wave is drawn uniformly; otherwise the trail fades toward
      // its tail.
      val segmentCount = if (reduceMotion) SWIPE_HINT_STATIC_SEGMENTS else SWIPE_HINT_TRAIL_SEGMENTS
      var previous = swipeHintWavePoint(frame.trailStart, size.width, size.height)
      for (i in 1..segmentCount) {
        val fraction = i.toFloat() / segmentCount.toFloat()
        val point =
          swipeHintWavePoint(
            frame.trailStart + (frame.trailEnd - frame.trailStart) * fraction,
            size.width,
            size.height,
          )
        val tailFade = if (reduceMotion) 1f else fraction
        drawLine(
          color = trailColor.copy(alpha = SWIPE_HINT_TRAIL_ALPHA * frame.trailAlpha * tailFade),
          start = previous,
          end = point,
          strokeWidth = strokeWidthPx,
          cap = StrokeCap.Round,
        )
        previous = point
      }
    }

    if (frame.handAlpha > 0f) {
      val fingertip = swipeHintWavePoint(frame.handProgress, size.width, size.height)
      drawHandIcon(
        painter,
        iconColor,
        fingertip,
        iconSizePx,
        SWIPE_HINT_ICON_ALPHA * frame.handAlpha,
      )
    }
  }
}

private fun DrawScope.drawHandIcon(
  painter: Painter,
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
