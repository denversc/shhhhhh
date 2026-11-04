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

    test("swipeHintCycleMs is null before 5 seconds of idle") {
      swipeHintCycleMs(0L).shouldBeNull()
      swipeHintCycleMs(4_999L).shouldBeNull()
    }

    test("swipeHintCycleMs restarts every 5 seconds start-to-start and never ends while idle") {
      swipeHintCycleMs(5_000L) shouldBe 0L
      swipeHintCycleMs(6_000L) shouldBe 1_000L
      swipeHintCycleMs(9_000L) shouldBe 4_000L
      swipeHintCycleMs(9_999L) shouldBe 4_999L
      swipeHintCycleMs(10_000L) shouldBe 0L
      swipeHintCycleMs(15_250L) shouldBe 250L
    }

    test("swipeHintIdleDelayMs waits until the first cycle and while the hand is parked") {
      swipeHintIdleDelayMs(0L) shouldBe 5_000L
      swipeHintIdleDelayMs(1_234L) shouldBe 3_766L
      // Sweeping: render every frame.
      swipeHintIdleDelayMs(5_000L) shouldBe 0L
      swipeHintIdleDelayMs(6_999L) shouldBe 0L
      // Parked: wait until the fade-out starts at 4.8 s into the cycle.
      swipeHintIdleDelayMs(7_000L) shouldBe 2_800L
      swipeHintIdleDelayMs(9_000L) shouldBe 800L
      // Fading out: render every frame.
      swipeHintIdleDelayMs(9_800L) shouldBe 0L
      swipeHintIdleDelayMs(9_999L) shouldBe 0L
      swipeHintIdleDelayMs(12_000L) shouldBe 2_800L
    }

    test("swipeHintFrame sweeps left to right over 2 seconds with a trail") {
      val start = swipeHintFrame(0L, reduceMotion = false)
      start.handProgress shouldBe 0f
      start.handAlpha shouldBe 0f

      val middle = swipeHintFrame(SWIPE_HINT_SWEEP_MS / 2, reduceMotion = false)
      middle.handProgress shouldBe (0.5f plusOrMinus 1e-6f)
      middle.handAlpha shouldBe 1f
      middle.trailAlpha shouldBe 1f
      middle.trailEnd shouldBe middle.handProgress
      middle.trailStart shouldBeLessThan middle.trailEnd

      val nearEnd = swipeHintFrame(SWIPE_HINT_SWEEP_MS - 1, reduceMotion = false)
      nearEnd.handProgress shouldBeGreaterThan 0.99f
      nearEnd.handAlpha shouldBe 1f
      nearEnd.trailAlpha shouldBeLessThan 0.01f
    }

    test("swipeHintFrame parks the hand fully visible at the end of the path") {
      for (cycleMs in listOf(SWIPE_HINT_SWEEP_MS, 3_000L, 4_000L, 4_799L)) {
        for (reduceMotion in listOf(false, true)) {
          val frame = swipeHintFrame(cycleMs, reduceMotion)
          frame.handProgress shouldBe 1f
          frame.handAlpha shouldBe 1f
          frame.trailAlpha shouldBe 0f
        }
      }
    }

    test("swipeHintFrame fades the parked hand out just before the next sweep") {
      for (reduceMotion in listOf(false, true)) {
        swipeHintFrame(4_800L, reduceMotion).handAlpha shouldBe 1f
        swipeHintFrame(4_900L, reduceMotion).handAlpha shouldBe (0.5f plusOrMinus 1e-6f)
        swipeHintFrame(4_999L, reduceMotion).handAlpha shouldBeLessThan 0.01f
        swipeHintFrame(4_999L, reduceMotion).handProgress shouldBe 1f
      }
    }

    test("swipeHintFrame in reduce motion fades in place with a static wave") {
      val start = swipeHintFrame(0L, reduceMotion = true)
      start.handAlpha shouldBe 0f
      start.trailAlpha shouldBe 0f

      for (cycleMs in listOf(0L, 500L, 1_000L, 1_999L)) {
        val frame = swipeHintFrame(cycleMs, reduceMotion = true)
        frame.handProgress shouldBe 1f
        frame.trailStart shouldBe 0f
        frame.trailEnd shouldBe 1f
      }
      swipeHintFrame(500L, reduceMotion = true).handAlpha shouldBe (0.5f plusOrMinus 1e-6f)
      swipeHintFrame(1_000L, reduceMotion = true).handAlpha shouldBe 1f
      swipeHintFrame(1_000L, reduceMotion = true).trailAlpha shouldBe (1f plusOrMinus 1e-6f)
      swipeHintFrame(1_999L, reduceMotion = true).handAlpha shouldBe 1f
      swipeHintFrame(1_999L, reduceMotion = true).trailAlpha shouldBeLessThan 0.01f
    }
  })
