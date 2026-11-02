package prefix924.shhhhhh

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.animation.doOnEnd
import prefix924.shhhhhh.storage.AndroidAppStorage
import prefix924.shhhhhh.storage.AppStorage
import prefix924.shhhhhh.ui.theme.ShhhhhhTheme

class MainActivity : ComponentActivity() {
  private val appStorage: AppStorage by lazy { AndroidAppStorage.create(applicationContext) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    splashScreen.setOnExitAnimationListener { splashScreenView ->
      val fadeOut =
        ObjectAnimator.ofFloat(splashScreenView, View.ALPHA, 1f, 0f).apply {
          interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
          duration = 250L
          doOnEnd { splashScreenView.remove() }
        }
      fadeOut.start()
    }

    val loadedKeyPair = appStorage.loadKeyPair()
    val completedOnboarding = appStorage.hasCompletedOnboarding()
    val isReady = completedOnboarding && loadedKeyPair != null

    setContent {
      ShhhhhhTheme {
        var userKeyPair by remember { mutableStateOf(if (isReady) loadedKeyPair else null) }
        var hasCompletedOnboarding by remember { mutableStateOf(isReady) }

        Crossfade(
          targetState = hasCompletedOnboarding && userKeyPair != null,
          label = "OnboardingToMainTransition",
        ) { completed ->
          val keyPair = userKeyPair
          if (completed && keyPair != null) {
            MainScreen(userKeyPair = keyPair, storage = appStorage)
          } else {
            OnboardingScreen(
              onComplete = { kp ->
                appStorage.saveKeyPair(kp)
                appStorage.setCompletedOnboarding(true)
                userKeyPair = kp
                hasCompletedOnboarding = true
              }
            )
          }
        }
      }
    }
  }
}
