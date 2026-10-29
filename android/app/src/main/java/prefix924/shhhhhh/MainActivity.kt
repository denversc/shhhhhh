package prefix924.shhhhhh

import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.animation.doOnEnd
import java.security.PublicKey
import java.util.Base64
import kotlinx.coroutines.launch
import prefix924.shhhhhh.crypto.CryptoEngine
import prefix924.shhhhhh.crypto.CryptoKeyPair
import prefix924.shhhhhh.crypto.DecryptResult
import prefix924.shhhhhh.storage.AndroidAppStorage
import prefix924.shhhhhh.storage.AppStorage
import prefix924.shhhhhh.storage.ContactRecord
import prefix924.shhhhhh.ui.theme.ShhhhhhTheme

enum class EncryptButtonState {
  ENCRYPT,
  COPY,
}

enum class DecryptButtonState {
  DECRYPT,
  COPY_SECRET,
}

enum class DecryptError(val message: String) {
  NO_CANDIDATES(
    "No Base64URL ciphertext string of 166 or more characters was found in the text box."
  ),
  AUTH_FAILED(
    "Ciphertext was found, but it could not be decrypted with this device's private key. It may have been intended for a different recipient or corrupted."
  ),
}

internal val CryptoKeyPairSaver: Saver<CryptoKeyPair?, Any> =
  listSaver(
    save = { keyPair ->
      if (keyPair != null) {
        listOf(
          Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.privateKey),
          Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.publicKey),
        )
      } else {
        emptyList()
      }
    },
    restore = { list ->
      if (list.size >= 2) {
        CryptoKeyPair(
          privateKey = Base64.getUrlDecoder().decode(list[0]),
          publicKey = Base64.getUrlDecoder().decode(list[1]),
        )
      } else {
        null
      }
    },
  )

internal fun calculatePasteResult(
  currentValue: TextFieldValue,
  pasteText: String,
  isFocused: Boolean,
): TextFieldValue {
  val currentText = currentValue.text
  val (start, end) =
    if (isFocused) {
      val min = currentValue.selection.min.coerceIn(0, currentText.length)
      val max = currentValue.selection.max.coerceIn(0, currentText.length)
      min to max
    } else {
      currentText.length to currentText.length
    }
  val newText = currentText.replaceRange(start, end, pasteText)
  val newCursorPos = start + pasteText.length
  return TextFieldValue(text = newText, selection = TextRange(newCursorPos))
}

internal fun getClipboardText(context: Context, clipboardManager: ClipboardManager): String? {
  val description = clipboardManager.primaryClipDescription ?: return null
  val hasText =
    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
      description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) ||
      description.hasMimeType("text/*")
  if (!hasText) return null

  val clip = clipboardManager.primaryClip ?: return null
  if (clip.itemCount == 0) return null

  val item = clip.getItemAt(0)
  val itemText = item.text?.toString() ?: item.coerceToText(context)?.toString()
  return if (!itemText.isNullOrEmpty()) itemText else null
}

/**
 * Copies the raw Base64URL public key from [keyPair] to the clipboard without extra text or
 * template wrappers.
 *
 * @param clipboardManager System clipboard manager.
 * @param keyPair The user's key pair whose public key should be copied.
 * @param onFeedback Callback invoked with the feedback message to display to the user.
 * @return The raw 43-character Base64URL string copied to the clipboard.
 */
internal fun copyPublicKeyToClipboard(
  clipboardManager: ClipboardManager,
  keyPair: CryptoKeyPair,
  onFeedback: (String) -> Unit = {},
): String =
  copyPublicKeyToClipboard(
    clipboardManager = clipboardManager,
    publicKeyBase64Url = keyPair.publicKeyBase64Url,
    onFeedback = onFeedback,
  )

/**
 * Copies the raw Base64URL public key string to the clipboard without extra text or template
 * wrappers.
 *
 * @param clipboardManager System clipboard manager.
 * @param publicKeyBase64Url The raw 43-character Base64URL public key string.
 * @param onFeedback Callback invoked with the feedback message to display to the user.
 * @return The raw Base64URL string copied to the clipboard.
 */
internal fun copyPublicKeyToClipboard(
  clipboardManager: ClipboardManager,
  publicKeyBase64Url: String,
  onFeedback: (String) -> Unit = {},
): String {
  val clip = ClipData.newPlainText("shhhhhh_public_key", publicKeyBase64Url)
  clipboardManager.setPrimaryClip(clip)
  onFeedback("Copied public key")
  return publicKeyBase64Url
}

internal val PUBLIC_KEY_REGEX = Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])""")

/**
 * Validates whether the given string is a valid 43-character Base64URL-encoded 32-byte X25519
 * public key.
 *
 * @param publicKeyBase64Url The candidate string to validate.
 * @return True if [publicKeyBase64Url] is exactly 43 Base64URL characters and decodes to a valid
 *   X25519 public key.
 */
internal fun validatePublicKey(publicKeyBase64Url: String): Boolean {
  if (publicKeyBase64Url.length != 43) return false
  return try {
    val rawBytes = CryptoEngine.decodePublicKeyBase64Url(publicKeyBase64Url)
    if (rawBytes.size != CryptoEngine.KEY_SIZE_BYTES) return false
    val canonical = CryptoEngine.encodePublicKeyBase64Url(rawBytes)
    if (canonical != publicKeyBase64Url) return false
    CryptoEngine.rawBytesToPublicKey(rawBytes)
    true
  } catch (_: Exception) {
    false
  }
}

/**
 * Scans [input] text to find and extract a valid 43-character Base64URL X25519 public key, ignoring
 * surrounding conversational text, punctuation, or whitespace.
 *
 * @param input Raw text entered or pasted by the user.
 * @return The first valid 43-character Base64URL public key found, or null if none detected.
 */
internal fun extractPublicKey(input: String): String? {
  if (input.isBlank()) return null
  return PUBLIC_KEY_REGEX.findAll(input).map { it.value }.firstOrNull { validatePublicKey(it) }
}

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
        var userKeyPair by
          rememberSaveable(stateSaver = CryptoKeyPairSaver) {
            mutableStateOf(if (isReady) loadedKeyPair else null)
          }
        var hasCompletedOnboarding by rememberSaveable { mutableStateOf(isReady) }

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

@OptIn(
  ExperimentalMaterial3Api::class,
  ExperimentalFoundationApi::class,
  ExperimentalLayoutApi::class,
)
@Composable
fun MainScreen(
  userKeyPair: CryptoKeyPair,
  storage: AppStorage = AndroidAppStorage.create(LocalContext.current),
) {
  val context = LocalContext.current
  val view = LocalView.current
  val focusManager = LocalFocusManager.current
  val clipboardManager = remember {
    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  }
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val sheetSnackbarHostState = remember { SnackbarHostState() }

  var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
  val text = textFieldValue.text
  var isTextFieldFocused by remember { mutableStateOf(false) }
  var encryptState by remember { mutableStateOf(EncryptButtonState.ENCRYPT) }
  var decryptState by remember { mutableStateOf(DecryptButtonState.DECRYPT) }
  var overlayError by remember { mutableStateOf<DecryptError?>(null) }
  var dialogError by remember { mutableStateOf<DecryptError?>(null) }
  var contacts by remember { mutableStateOf(storage.getContacts()) }
  var selectedContact by remember { mutableStateOf<ContactRecord?>(null) }
  var showContactSheet by remember { mutableStateOf(false) }
  var showAddContactDialog by remember { mutableStateOf(false) }
  var decryptedSecrets by remember { mutableStateOf<List<String>>(emptyList()) }
  var secretSpans by remember { mutableStateOf<List<SecretSpan>>(emptyList()) }
  var showSecretPickerSheet by remember { mutableStateOf(false) }
  var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

  fun copyToClipboard(content: String, label: String, message: String) {
    val clip = ClipData.newPlainText(label, content)
    clipboardManager.setPrimaryClip(clip)
    scope.launch { snackbarHostState.showSnackbar(message) }
  }

  fun triggerDecryptError(error: DecryptError) {
    overlayError = error
    focusManager.clearFocus()
    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    topBar = {
      val isImeTargetVisible = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0
      Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
        AnimatedVisibility(
          visible = !isImeTargetVisible,
          enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
          exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
          CenterAlignedTopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(text = "shhhhhh", fontWeight = FontWeight.SemiBold) },
          )
        }
      }
    },
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .consumeWindowInsets(innerPadding)
          .imePadding()
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Shared Multi-line Text Box with Header Utility Bar
      Column(
        modifier =
          Modifier.weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
              width = 1.dp,
              color = MaterialTheme.colorScheme.outlineVariant,
              shape = RoundedCornerShape(12.dp),
            )
      ) {
        // Utility bar pinned at top of text box container
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(
            onClick = {
              textFieldValue = TextFieldValue("")
              focusManager.clearFocus()
              encryptState = EncryptButtonState.ENCRYPT
              decryptState = DecryptButtonState.DECRYPT
              overlayError = null
              dialogError = null
              decryptedSecrets = emptyList()
              secretSpans = emptyList()
              showSecretPickerSheet = false
            }
          ) {
            Icon(
              imageVector = Icons.Default.Clear,
              contentDescription = "Clear",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Clear", fontSize = 13.sp)
          }

          TextButton(
            onClick = {
              val pasteText = getClipboardText(context, clipboardManager)
              if (pasteText != null) {
                textFieldValue =
                  calculatePasteResult(
                    currentValue = textFieldValue,
                    pasteText = pasteText,
                    isFocused = isTextFieldFocused,
                  )
                overlayError = null
                dialogError = null
                if (encryptState == EncryptButtonState.COPY) {
                  encryptState = EncryptButtonState.ENCRYPT
                }
                if (decryptState == DecryptButtonState.COPY_SECRET) {
                  decryptState = DecryptButtonState.DECRYPT
                  decryptedSecrets = emptyList()
                  secretSpans = emptyList()
                }
                showSecretPickerSheet = false
              } else {
                scope.launch { snackbarHostState.showSnackbar("Clipboard is empty") }
              }
            }
          ) {
            Icon(
              imageVector = Icons.Default.ContentPaste,
              contentDescription = "Paste",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Paste", fontSize = 13.sp)
          }

          TextButton(onClick = { copyToClipboard(text, "shhhhhh_text", "Copied full text") }) {
            Icon(
              imageVector = Icons.Default.ContentCopy,
              contentDescription = "Copy",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Copy", fontSize = 13.sp)
          }
        }

        HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.5f))

        // Text Input Area (with zero-layout-shift Z-axis ErrorOverlay)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
          val highlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
          val highlightTextColor = MaterialTheme.colorScheme.onPrimaryContainer
          val visualTransformation =
            remember(decryptState, secretSpans, highlightColor, highlightTextColor) {
              if (decryptState == DecryptButtonState.COPY_SECRET && secretSpans.isNotEmpty()) {
                SecretHighlightVisualTransformation(
                  spans = secretSpans,
                  highlightColor = highlightColor,
                  textColor = highlightTextColor,
                  onSecretClick = { secret ->
                    copyToClipboard(secret, "shhhhhh_secret", "Copied secret: $secret")
                  },
                )
              } else {
                VisualTransformation.None
              }
            }

          Box(
            modifier =
              Modifier.fillMaxSize().padding(14.dp).pointerInput(decryptState, secretSpans) {
                if (decryptState == DecryptButtonState.COPY_SECRET && secretSpans.isNotEmpty()) {
                  awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val layout = textLayoutResult ?: return@awaitEachGesture
                    val charOffset = layout.getOffsetForPosition(down.position)
                    val matchedSpan =
                      secretSpans.firstOrNull { span ->
                        charOffset in span.range &&
                          layout.getBoundingBox(charOffset).inflate(6f).contains(down.position)
                      }
                    if (matchedSpan != null) {
                      down.consume()
                      val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                      if (up != null) {
                        up.consume()
                        copyToClipboard(
                          matchedSpan.secret,
                          "shhhhhh_secret",
                          "Copied secret: ${matchedSpan.secret}",
                        )
                      }
                    }
                  }
                }
              }
          ) {
            if (text.isEmpty()) {
              Text(
                text = "Type message to encrypt or paste text containing ciphertext here...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
              )
            }

            BasicTextField(
              value = textFieldValue,
              onValueChange = { newValue ->
                textFieldValue = newValue
                overlayError = null
                if (encryptState == EncryptButtonState.COPY) {
                  encryptState = EncryptButtonState.ENCRYPT
                }
                if (decryptState == DecryptButtonState.COPY_SECRET) {
                  decryptState = DecryptButtonState.DECRYPT
                  decryptedSecrets = emptyList()
                  secretSpans = emptyList()
                }
              },
              visualTransformation = visualTransformation,
              onTextLayout = { textLayoutResult = it },
              modifier =
                Modifier.fillMaxSize().onFocusChanged { isTextFieldFocused = it.isFocused },
              textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
            )
          }

          val currentOverlayError = overlayError
          if (currentOverlayError != null) {
            ErrorOverlay(
              onDismiss = { overlayError = null },
              onShowDetails = {
                dialogError = currentOverlayError
                overlayError = null
              },
            )
          }
        }
      }

      // Action Buttons Row (Fixed Dimensions, Zero Layout Shift)
      Row(
        modifier = Modifier.fillMaxWidth().height(50.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Encrypt / Morphing Button
        Box(
          modifier =
            Modifier.weight(1f)
              .height(50.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(
                if (encryptState == EncryptButtonState.ENCRYPT)
                  Color(0xFF1A73E8) // Vivid bright blue matching Decrypt green luminance
                else Color(0xFF5E35B1) // Vivid indigo/purple
              )
              .clickable {
                overlayError = null
                dialogError = null
                if (encryptState == EncryptButtonState.ENCRYPT) {
                  val currentRecipient = selectedContact
                  val recipientPublicKey: PublicKey =
                    if (currentRecipient == null) {
                      CryptoEngine.rawBytesToPublicKey(userKeyPair.publicKey)
                    } else {
                      val rawBytes =
                        CryptoEngine.decodePublicKeyBase64Url(currentRecipient.publicKeyBase64Url)
                      CryptoEngine.rawBytesToPublicKey(rawBytes)
                    }
                  val ciphertext =
                    CryptoEngine.encrypt(plaintext = text, recipientPublicKey = recipientPublicKey)
                  textFieldValue =
                    TextFieldValue(ciphertext, selection = TextRange(ciphertext.length))
                  focusManager.clearFocus()
                  encryptState = EncryptButtonState.COPY
                  decryptState = DecryptButtonState.DECRYPT
                  decryptedSecrets = emptyList()
                  secretSpans = emptyList()
                  showSecretPickerSheet = false
                } else {
                  copyToClipboard(text, "shhhhhh_ciphertext", "Copied ciphertext")
                }
              }
              .padding(horizontal = 12.dp),
          contentAlignment = Alignment.Center,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector =
                if (encryptState == EncryptButtonState.ENCRYPT) Icons.Default.Lock
                else Icons.Default.ContentCopy,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (encryptState == EncryptButtonState.ENCRYPT) "Encrypt" else "Copy",
              color = Color.White,
              fontSize = 16.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
            )
          }
        }

        // Decrypt / Morphing Button (Supports Tap and Long-Press)
        Box(
          modifier =
            Modifier.weight(1f)
              .height(50.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(
                if (decryptState == DecryptButtonState.DECRYPT) Color(0xFF2E7D32) // Forest green
                else Color(0xFFE65100) // Deep orange
              )
              .combinedClickable(
                onClick = {
                  if (decryptState == DecryptButtonState.DECRYPT) {
                    overlayError = null
                    dialogError = null
                    when (
                      val result =
                        CryptoEngine.decryptTextWithCandidates(
                          inputText = text,
                          recipientPrivateKey = userKeyPair.privateKey,
                        )
                    ) {
                      is DecryptResult.NoCandidates -> {
                        decryptState = DecryptButtonState.DECRYPT
                        decryptedSecrets = emptyList()
                        secretSpans = emptyList()
                        triggerDecryptError(DecryptError.NO_CANDIDATES)
                      }
                      is DecryptResult.AuthFailed -> {
                        decryptState = DecryptButtonState.DECRYPT
                        decryptedSecrets = emptyList()
                        secretSpans = emptyList()
                        triggerDecryptError(DecryptError.AUTH_FAILED)
                      }
                      is DecryptResult.Success -> {
                        textFieldValue =
                          TextFieldValue(result.text, selection = TextRange(result.text.length))
                        focusManager.clearFocus()
                        decryptedSecrets = result.secrets
                        secretSpans = findSecretSpans(result.text, result.secrets, result.ranges)
                        decryptState = DecryptButtonState.COPY_SECRET
                        encryptState = EncryptButtonState.ENCRYPT
                        overlayError = null
                        dialogError = null
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                      }
                    }
                  } else {
                    when (val action = resolveCopySecretAction(decryptedSecrets)) {
                      is CopySecretAction.CopySingle -> {
                        copyToClipboard(
                          action.secret,
                          "shhhhhh_secret",
                          "Copied secret: ${action.secret}",
                        )
                      }
                      is CopySecretAction.ShowPicker -> {
                        showSecretPickerSheet = true
                      }
                      is CopySecretAction.None -> {
                        if (text.isNotEmpty()) {
                          copyToClipboard(text, "shhhhhh_secret", "Copied secret: $text")
                        }
                      }
                    }
                  }
                },
                onLongClick = {
                  if (decryptState == DecryptButtonState.COPY_SECRET) {
                    copyToClipboard(text, "shhhhhh_full", "Copied full message")
                  }
                },
              )
              .padding(horizontal = 12.dp),
          contentAlignment = Alignment.Center,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector =
                if (decryptState == DecryptButtonState.DECRYPT) Icons.Default.LockOpen
                else Icons.Default.ContentCopy,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (decryptState == DecryptButtonState.DECRYPT) "Decrypt" else "Copy Secret",
              color = Color.White,
              fontSize = 16.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
            )
          }
        }
      }

      // Contact Selector Widget (Positioned Adjacent to Action Buttons)
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
              width = 1.dp,
              color = MaterialTheme.colorScheme.outlineVariant,
              shape = RoundedCornerShape(10.dp),
            )
            .combinedClickable(
              onClick = {
                contacts = storage.getContacts()
                showContactSheet = true
              }
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Default.AccountCircle,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Recipient",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = selectedContact?.name ?: "Myself (This Device)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
          )
        }

        Icon(
          imageVector = Icons.Default.KeyboardArrowDown,
          contentDescription = "Select Contact",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // Decryption Failure Detail Modal Dialog
    dialogError?.let { error ->
      AlertDialog(
        onDismissRequest = { dialogError = null },
        title = { Text(text = "Decryption Failed") },
        text = { Text(text = error.message) },
        confirmButton = { TextButton(onClick = { dialogError = null }) { Text(text = "OK") } },
      )
    }

    // Contact Selection & Management Sheet (Modal)
    if (showContactSheet) {
      ContactSelectionSheet(
        userKeyPair = userKeyPair,
        selectedContact = selectedContact,
        contacts = contacts,
        onSelectContact = { contact -> selectedContact = contact },
        onOpenAddContact = { showAddContactDialog = true },
        onDismissRequest = { showContactSheet = false },
        onCopyPublicKey = {
          copyPublicKeyToClipboard(
            clipboardManager = clipboardManager,
            keyPair = userKeyPair,
            onFeedback = { message ->
              scope.launch {
                snackbarHostState.showSnackbar(message)
                sheetSnackbarHostState.showSnackbar(message)
              }
            },
          )
          view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        },
        sheetSnackbarHostState = sheetSnackbarHostState,
      )
    }

    // Add Contact Modal Form (Screen 7)
    if (showAddContactDialog) {
      AddContactDialog(
        onDismissRequest = { showAddContactDialog = false },
        onSaveContact = { newContact ->
          storage.saveContact(newContact)
          contacts = storage.getContacts()
          selectedContact = newContact
          showAddContactDialog = false
          showContactSheet = false
          view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
          scope.launch { snackbarHostState.showSnackbar("Added contact: ${newContact.name}") }
        },
      )
    }

    // Multi-Secret Picker Bottom Sheet
    if (showSecretPickerSheet) {
      SecretPickerSheet(
        secrets = decryptedSecrets,
        onDismissRequest = { showSecretPickerSheet = false },
        onSelectSecret = { selectedSecret ->
          showSecretPickerSheet = false
          copyToClipboard(selectedSecret, "shhhhhh_secret", "Copied secret: $selectedSecret")
        },
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSelectionSheet(
  userKeyPair: CryptoKeyPair,
  selectedContact: ContactRecord?,
  contacts: List<ContactRecord>,
  onSelectContact: (ContactRecord?) -> Unit,
  onOpenAddContact: () -> Unit,
  onDismissRequest: () -> Unit,
  onCopyPublicKey: () -> Unit,
  modifier: Modifier = Modifier,
  sheetSnackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
  val sheetState = rememberModalBottomSheetState()
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    modifier = modifier,
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          text = "Contacts & Settings",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )

        // MY PUBLIC KEY Section
        MyPublicKeySection(
          publicKeyBase64Url = userKeyPair.publicKeyBase64Url,
          onCopyPublicKey = onCopyPublicKey,
        )

        HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.5f))

        // CONTACTS Section / Selection
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "CONTACTS",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )

          // "Myself (This Device)" Item
          Surface(
            onClick = {
              onSelectContact(null)
              onDismissRequest()
            },
            shape = RoundedCornerShape(8.dp),
            color =
              if (selectedContact == null)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
              else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            border =
              BorderStroke(
                width = 1.dp,
                color =
                  if (selectedContact == null) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                  else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
              ),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (selectedContact == null) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = "Selected",
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
              } else {
                Spacer(modifier = Modifier.size(20.dp))
              }
              Spacer(modifier = Modifier.width(12.dp))
              Text(
                text = "Myself (This Device)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight =
                  if (selectedContact == null) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }

          // Saved Contacts Items
          contacts.forEach { contact ->
            val isSelected = selectedContact?.id == contact.id
            Surface(
              onClick = {
                onSelectContact(contact)
                onDismissRequest()
              },
              shape = RoundedCornerShape(8.dp),
              color =
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
              border =
                BorderStroke(
                  width = 1.dp,
                  color =
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                ),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                if (isSelected) {
                  Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                  )
                } else {
                  Spacer(modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = contact.name,
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(4.dp))

          // [ + Add New Contact ] Button
          OutlinedButton(
            onClick = onOpenAddContact,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Add,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Add New Contact", fontWeight = FontWeight.Medium)
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
          onClick = onDismissRequest,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(10.dp),
        ) {
          Text("Close")
        }
      }

      SnackbarHost(
        hostState = sheetSnackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
      )
    }
  }
}

/** Backward compatibility overload for [ContactSelectionSheet] with string selectedContact. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSelectionSheet(
  userKeyPair: CryptoKeyPair,
  selectedContact: String,
  onDismissRequest: () -> Unit,
  onCopyPublicKey: () -> Unit,
  modifier: Modifier = Modifier,
  sheetSnackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) =
  ContactSelectionSheet(
    userKeyPair = userKeyPair,
    selectedContact = null,
    contacts = emptyList(),
    onSelectContact = {},
    onOpenAddContact = {},
    onDismissRequest = onDismissRequest,
    onCopyPublicKey = onCopyPublicKey,
    modifier = modifier,
    sheetSnackbarHostState = sheetSnackbarHostState,
  )

@Composable
fun AddContactDialog(
  onDismissRequest: () -> Unit,
  onSaveContact: (ContactRecord) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val clipboardManager = remember {
    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  }
  var name by remember { mutableStateOf("") }
  var publicKeyInput by remember { mutableStateOf("") }
  val extractedKey = remember(publicKeyInput) { extractPublicKey(publicKeyInput) }
  val isFormValid = name.isNotBlank() && extractedKey != null

  AlertDialog(
    onDismissRequest = onDismissRequest,
    title = {
      Text(
        text = "Add New Contact",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Contact Name") },
          placeholder = { Text("e.g. Wife, Alice") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
          value = publicKeyInput,
          onValueChange = { publicKeyInput = it },
          label = { Text("Public Key") },
          placeholder = { Text("Pasted text or key...") },
          minLines = 2,
          maxLines = 4,
          modifier = Modifier.fillMaxWidth(),
          isError = publicKeyInput.isNotBlank() && extractedKey == null,
        )

        OutlinedButton(
          onClick = {
            val clipText = getClipboardText(context, clipboardManager)
            if (clipText != null) {
              publicKeyInput = clipText
            }
          },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(8.dp),
        ) {
          Icon(
            imageVector = Icons.Default.ContentPaste,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text("Paste from Clipboard")
        }

        // Smart Key Extraction feedback
        when {
          extractedKey != null -> {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = Color(0xFF2E7D32).copy(alpha = 0.12f),
              border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.3f)),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Default.CheckCircle,
                  contentDescription = null,
                  tint = Color(0xFF2E7D32),
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                  Text(
                    text = "Valid key detected",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = Color(0xFF2E7D32),
                  )
                  Text(
                    text = extractedKey,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF2E7D32),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            }
          }
          publicKeyInput.isNotBlank() -> {
            Text(
              text = "No valid 43-character public key detected",
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          else -> {
            Text(
              text = "Smart extractor automatically detects and extracts the 43-character key",
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val key = extractedKey
          if (key != null && name.isNotBlank()) {
            onSaveContact(ContactRecord(name = name.trim(), publicKeyBase64Url = key))
          }
        },
        enabled = isFormValid,
        shape = RoundedCornerShape(8.dp),
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismissRequest, shape = RoundedCornerShape(8.dp)) { Text("Cancel") }
    },
    modifier = modifier,
  )
}

@Composable
fun MyPublicKeySection(
  publicKeyBase64Url: String,
  onCopyPublicKey: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(
      text = "MY PUBLIC KEY",
      style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
    )

    Surface(
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
      border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = publicKeyBase64Url,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(14.dp),
      )
    }

    Button(
      onClick = onCopyPublicKey,
      modifier = Modifier.fillMaxWidth().height(48.dp),
      shape = RoundedCornerShape(10.dp),
      colors =
        ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8), contentColor = Color.White),
    ) {
      Icon(
        imageVector = Icons.Default.ContentCopy,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(text = "Copy My Public Key", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretPickerSheet(
  secrets: List<String>,
  onDismissRequest: () -> Unit,
  onSelectSecret: (String) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState()
  ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 24.dp)
          .padding(bottom = 32.dp, top = 8.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = "Select Secret to Copy",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )

      secrets.forEachIndexed { index, secret ->
        Surface(
          onClick = { onSelectSecret(secret) },
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = secret,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.Medium,
              modifier = Modifier.weight(1f),
              maxLines = 3,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
              imageVector = Icons.Default.ContentCopy,
              contentDescription = "Copy secret",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
fun ErrorOverlay(onDismiss: () -> Unit, onShowDetails: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onDismiss,
        ),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      onClick = onShowDetails,
      shape = CircleShape,
      color = Color(0xFFD32F2F),
      contentColor = Color.White,
      shadowElevation = 6.dp,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        Icon(
          imageVector = Icons.Default.Warning,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Error",
          color = Color.White,
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}
