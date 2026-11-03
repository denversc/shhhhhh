package prefix924.shhhhhh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenInstrumentedTest {
  @get:Rule val composeTestRule = createComposeRule()

  private fun setOnboardingContent() {
    composeTestRule.setContent { OnboardingScreen(onComplete = {}) }
  }

  private fun tapEntropyBox() {
    composeTestRule.onNodeWithTag(ENTROPY_CANVAS_TAG).performTouchInput { click(center) }
  }

  @Test
  fun progressSectionIsHiddenAtLaunchAndVisibleAfterFirstTap() {
    setOnboardingContent()

    composeTestRule.onNodeWithTag(ENTROPY_PROGRESS_SECTION_TAG).assertDoesNotExist()
    composeTestRule.onNodeWithText("Entropy Collection").assertDoesNotExist()
    composeTestRule.onNodeWithTag(ENTROPY_PERCENTAGE_TEXT_TAG).assertDoesNotExist()
    composeTestRule.onNodeWithTag(ENTROPY_PROGRESS_BAR_TAG).assertDoesNotExist()

    tapEntropyBox()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(ENTROPY_PROGRESS_SECTION_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithText("Entropy Collection").assertIsDisplayed()
    composeTestRule.onNodeWithTag(ENTROPY_PERCENTAGE_TEXT_TAG).assertIsDisplayed()
    composeTestRule.onNodeWithTag(ENTROPY_PROGRESS_BAR_TAG).assertIsDisplayed()
  }

  @Test
  fun progressSectionIsAboveEntropyBoxAndBoxDoesNotMove() {
    setOnboardingContent()
    val boxBoundsBefore =
      composeTestRule.onNodeWithTag(ENTROPY_CANVAS_TAG).getUnclippedBoundsInRoot()

    tapEntropyBox()
    composeTestRule.waitForIdle()

    val sectionBounds =
      composeTestRule.onNodeWithTag(ENTROPY_PROGRESS_SECTION_TAG).getUnclippedBoundsInRoot()
    val boxBoundsAfter =
      composeTestRule.onNodeWithTag(ENTROPY_CANVAS_TAG).getUnclippedBoundsInRoot()

    assertTrue(
      "progress section bottom (${sectionBounds.bottom}) must be above entropy box top " +
        "(${boxBoundsAfter.top})",
      sectionBounds.bottom <= boxBoundsAfter.top,
    )
    assertEquals(boxBoundsBefore, boxBoundsAfter)
  }

  @Test
  fun swipeHintAppearsAfterIdleAndDisappearsOnTouch() {
    composeTestRule.mainClock.autoAdvance = false
    setOnboardingContent()
    composeTestRule.mainClock.advanceTimeByFrame()

    composeTestRule.mainClock.advanceTimeBy(4_000L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertDoesNotExist()

    composeTestRule.mainClock.advanceTimeBy(1_300L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertExists()

    // Touching the box hides the hint immediately.
    composeTestRule.onNodeWithTag(ENTROPY_CANVAS_TAG).performTouchInput { down(center) }
    composeTestRule.mainClock.advanceTimeByFrame()
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertDoesNotExist()

    // While the finger stays down, the hint never shows.
    composeTestRule.mainClock.advanceTimeBy(6_000L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertDoesNotExist()

    // Lifting the finger restarts the full idle countdown.
    composeTestRule.onNodeWithTag(ENTROPY_CANVAS_TAG).performTouchInput { up() }
    composeTestRule.mainClock.advanceTimeByFrame()
    composeTestRule.mainClock.advanceTimeBy(4_000L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertDoesNotExist()
    composeTestRule.mainClock.advanceTimeBy(1_300L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertExists()
  }

  @Test
  fun swipeHintPersistsThroughoutEachCycleWhileIdle() {
    composeTestRule.mainClock.autoAdvance = false
    setOnboardingContent()
    composeTestRule.mainClock.advanceTimeByFrame()

    composeTestRule.mainClock.advanceTimeBy(4_500L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertDoesNotExist()

    // Sweeping.
    composeTestRule.mainClock.advanceTimeBy(1_000L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertExists()

    // Parked at the end of the path after the 2 s sweep, ~4 s into the cycle.
    composeTestRule.mainClock.advanceTimeBy(3_500L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertExists()

    // Into the next cycle (10 s after the last input) and its parked phase.
    composeTestRule.mainClock.advanceTimeBy(1_500L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertExists()
    composeTestRule.mainClock.advanceTimeBy(3_000L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertExists()

    // Touching while the hand is parked hides it immediately.
    composeTestRule.onNodeWithTag(ENTROPY_CANVAS_TAG).performTouchInput { down(center) }
    composeTestRule.mainClock.advanceTimeByFrame()
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertDoesNotExist()
    composeTestRule.onNodeWithTag(ENTROPY_CANVAS_TAG).performTouchInput { up() }
    composeTestRule.mainClock.advanceTimeByFrame()
    composeTestRule.mainClock.advanceTimeBy(4_500L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertDoesNotExist()
  }

  @Test
  fun swipeHintIsHiddenWhileRestoreDialogIsOpen() {
    composeTestRule.mainClock.autoAdvance = false
    setOnboardingContent()
    composeTestRule.mainClock.advanceTimeByFrame()

    composeTestRule.mainClock.advanceTimeBy(5_300L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertExists()

    composeTestRule.mainClock.autoAdvance = true
    composeTestRule.onNodeWithText("Restore from Backup").performClick()
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertDoesNotExist()

    composeTestRule.mainClock.advanceTimeBy(11_000L)
    composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertDoesNotExist()
  }

  @Test
  fun swipeHintNeverAppearsOnceEntropyIsFull() {
    setOnboardingContent()

    val taps = 80
    repeat(taps) { index ->
      composeTestRule.onNodeWithTag(ENTROPY_CANVAS_TAG).performTouchInput {
        val fraction = (index + 1).toFloat() / (taps + 1).toFloat()
        click(percentOffset(fraction, 1f - fraction))
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("100%").assertExists()

    composeTestRule.mainClock.autoAdvance = false
    repeat(6) {
      composeTestRule.mainClock.advanceTimeBy(2_000L)
      composeTestRule.onNodeWithTag(ENTROPY_SWIPE_HINT_TAG).assertDoesNotExist()
    }
  }
}
