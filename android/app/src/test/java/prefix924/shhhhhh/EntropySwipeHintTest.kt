package prefix924.shhhhhh

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class EntropySwipeHintTest :
  FunSpec({
    test("swipeHintEaseInOut hits endpoints and midpoint") {
      swipeHintEaseInOut(0f) shouldBe 0f
      swipeHintEaseInOut(1f) shouldBe 1f
      swipeHintEaseInOut(0.5f) shouldBe (0.5f plusOrMinus 1e-6f)
    }

    test("swipeHintEaseInOut clamps out-of-range input") {
      swipeHintEaseInOut(-1f) shouldBe 0f
      swipeHintEaseInOut(2f) shouldBe 1f
    }

    test("swipeHintEaseInOut accelerates toward center and decelerates toward end") {
      val step = 0.1f
      val startSpeed = swipeHintEaseInOut(step) - swipeHintEaseInOut(0f)
      val centerSpeed = swipeHintEaseInOut(0.5f + step / 2) - swipeHintEaseInOut(0.5f - step / 2)
      val endSpeed = swipeHintEaseInOut(1f) - swipeHintEaseInOut(1f - step)
      centerSpeed shouldBeGreaterThan startSpeed
      centerSpeed shouldBeGreaterThan endSpeed
      // Monotonic.
      var previous = 0f
      for (i in 1..100) {
        val value = swipeHintEaseInOut(i / 100f)
        value shouldBeGreaterThan previous - 1e-6f
        previous = value
      }
    }

    test("swipeHintWavePoint is inset, left to right, and crosses the vertical center") {
      val width = 1000f
      val height = 400f
      val start = swipeHintWavePoint(0f, width, height)
      val middle = swipeHintWavePoint(0.5f, width, height)
      val end = swipeHintWavePoint(1f, width, height)

      start.x shouldBe (width * SWIPE_HINT_INSET_FRACTION plusOrMinus 1e-3f)
      end.x shouldBe (width * (1f - SWIPE_HINT_INSET_FRACTION) plusOrMinus 1e-3f)
      start.y shouldBe (height / 2f plusOrMinus 1e-3f)
      middle.y shouldBe (height / 2f plusOrMinus 1e-3f)
      end.y shouldBe (height / 2f plusOrMinus 1e-3f)
      start.x shouldBeLessThan middle.x
      middle.x shouldBeLessThan end.x
    }

    test("swipeHintWavePoint amplitude is 15 percent of the height") {
      val height = 400f
      val crest = swipeHintWavePoint(0.25f, 1000f, height)
      val trough = swipeHintWavePoint(0.75f, 1000f, height)
      crest.y shouldBe (height / 2f - 0.15f * height plusOrMinus 1e-3f)
      trough.y shouldBe (height / 2f + 0.15f * height plusOrMinus 1e-3f)
    }

    test("swipeHintPhase is null before 5 seconds of idle") {
      swipeHintPhase(0L).shouldBeNull()
      swipeHintPhase(4_999L).shouldBeNull()
    }

    test("swipeHintPhase plays for 1 second every 5 seconds start-to-start") {
      swipeHintPhase(5_000L) shouldBe 0f
      swipeHintPhase(5_500L) shouldBe 0.5f
      swipeHintPhase(5_999L) shouldBe (0.999f plusOrMinus 1e-6f)
      swipeHintPhase(6_000L).shouldBeNull()
      swipeHintPhase(9_999L).shouldBeNull()
      swipeHintPhase(10_000L) shouldBe 0f
      swipeHintPhase(15_250L) shouldBe 0.25f
    }

    test("swipeHintDelayUntilNextStartMs counts down to each 5 second start") {
      swipeHintDelayUntilNextStartMs(0L) shouldBe 5_000L
      swipeHintDelayUntilNextStartMs(1_234L) shouldBe 3_766L
      swipeHintDelayUntilNextStartMs(5_000L) shouldBe 0L
      swipeHintDelayUntilNextStartMs(6_000L) shouldBe 4_000L
      swipeHintDelayUntilNextStartMs(9_999L) shouldBe 1L
      swipeHintDelayUntilNextStartMs(10_000L) shouldBe 0L
    }

    test("swipeHintEnvelope fades in and out at the ends") {
      swipeHintEnvelope(0f) shouldBe 0f
      swipeHintEnvelope(0.5f) shouldBe 1f
      swipeHintEnvelope(1f) shouldBe (0f plusOrMinus 1e-6f)
    }
  })
