package prefix924.shhhhhh

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Base64
import kotlinx.coroutines.launch
import prefix924.shhhhhh.crypto.CryptoEngine
import prefix924.shhhhhh.crypto.CryptoKeyPair
import prefix924.shhhhhh.crypto.DecryptResult
import prefix924.shhhhhh.storage.AndroidAppStorage
import prefix924.shhhhhh.storage.AppStorage
import prefix924.shhhhhh.ui.theme.ShhhhhhTheme

enum class EncryptButtonState {
    ENCRYPT,
    COPY
}

enum class DecryptButtonState {
    DECRYPT,
    COPY_SECRET
}

enum class DecryptError(val message: String) {
    NO_CANDIDATES(
        "No Base64URL ciphertext string of 166 or more characters was found in the text box."
    ),
    AUTH_FAILED(
        "Ciphertext was found, but it could not be decrypted with this device's private key. It may have been intended for a different recipient or corrupted."
    )
}

internal val CryptoKeyPairSaver: Saver<CryptoKeyPair?, Any> = listSaver(
    save = { keyPair ->
        if (keyPair != null) {
            listOf(
                Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.privateKey),
                Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.publicKey)
            )
        } else {
            emptyList()
        }
    },
    restore = { list ->
        if (list.size >= 2) {
            CryptoKeyPair(
                privateKey = Base64.getUrlDecoder().decode(list[0]),
                publicKey = Base64.getUrlDecoder().decode(list[1])
            )
        } else {
            null
        }
    }
)

internal fun calculatePasteResult(
    currentValue: TextFieldValue,
    pasteText: String,
    isFocused: Boolean
): TextFieldValue {
    val currentText = currentValue.text
    val (start, end) = if (isFocused) {
        val min = currentValue.selection.min.coerceIn(0, currentText.length)
        val max = currentValue.selection.max.coerceIn(0, currentText.length)
        min to max
    } else {
        currentText.length to currentText.length
    }
    val newText = currentText.replaceRange(start, end, pasteText)
    val newCursorPos = start + pasteText.length
    return TextFieldValue(
        text = newText,
        selection = TextRange(newCursorPos)
    )
}

internal fun getClipboardText(context: Context, clipboardManager: ClipboardManager): String? {
    val description = clipboardManager.primaryClipDescription ?: return null
    val hasText = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) ||
        description.hasMimeType("text/*")
    if (!hasText) return null

    val clip = clipboardManager.primaryClip ?: return null
    if (clip.itemCount == 0) return null

    val item = clip.getItemAt(0)
    val itemText = item.text?.toString() ?: item.coerceToText(context)?.toString()
    return if (!itemText.isNullOrEmpty()) itemText else null
}

class MainActivity : ComponentActivity() {
    private val appStorage: AppStorage by lazy {
        AndroidAppStorage.create(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val loadedKeyPair = appStorage.loadKeyPair()
        val completedOnboarding = appStorage.hasCompletedOnboarding()
        val isReady = completedOnboarding && loadedKeyPair != null

        setContent {
            ShhhhhhTheme {
                var userKeyPair by rememberSaveable(stateSaver = CryptoKeyPairSaver) {
                    mutableStateOf(if (isReady) loadedKeyPair else null)
                }
                var hasCompletedOnboarding by rememberSaveable {
                    mutableStateOf(isReady)
                }

                Crossfade(
                    targetState = hasCompletedOnboarding && userKeyPair != null,
                    label = "OnboardingToMainTransition"
                ) { completed ->
                    val keyPair = userKeyPair
                    if (completed && keyPair != null) {
                        MainScreen(userKeyPair = keyPair)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(userKeyPair: CryptoKeyPair) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val text = textFieldValue.text
    var isTextFieldFocused by remember { mutableStateOf(false) }
    var encryptState by remember { mutableStateOf(EncryptButtonState.ENCRYPT) }
    var decryptState by remember { mutableStateOf(DecryptButtonState.DECRYPT) }
    var overlayError by remember { mutableStateOf<DecryptError?>(null) }
    var dialogError by remember { mutableStateOf<DecryptError?>(null) }
    var selectedContact by remember { mutableStateOf("Myself (This Device)") }
    var showContactSheet by remember { mutableStateOf(false) }
    var decryptedSecrets by remember { mutableStateOf<List<String>>(emptyList()) }
    var secretSpans by remember { mutableStateOf<List<SecretSpan>>(emptyList()) }
    var showSecretPickerSheet by remember { mutableStateOf(false) }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    fun copyToClipboard(content: String, label: String, message: String) {
        val clip = ClipData.newPlainText(label, content)
        clipboardManager.setPrimaryClip(clip)
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    fun triggerDecryptError(error: DecryptError) {
        overlayError = error
        focusManager.clearFocus()
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "shhhhhh",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Shared Multi-line Text Box with Header Utility Bar
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                // Utility bar pinned at top of text box container
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
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
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Clear", fontSize = 13.sp)
                    }

                    TextButton(
                        onClick = {
                            val pasteText = getClipboardText(context, clipboardManager)
                            if (pasteText != null) {
                                textFieldValue = calculatePasteResult(
                                    currentValue = textFieldValue,
                                    pasteText = pasteText,
                                    isFocused = isTextFieldFocused
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
                                scope.launch {
                                    snackbarHostState.showSnackbar("Clipboard is empty")
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Paste", fontSize = 13.sp)
                    }

                    TextButton(
                        onClick = {
                            copyToClipboard(text, "shhhhhh_text", "Copied full text")
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Copy", fontSize = 13.sp)
                    }
                }

                HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.5f))

                // Text Input Area (with zero-layout-shift Z-axis ErrorOverlay)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val highlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    val highlightTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                    val visualTransformation = remember(decryptState, secretSpans, highlightColor, highlightTextColor) {
                        if (decryptState == DecryptButtonState.COPY_SECRET && secretSpans.isNotEmpty()) {
                            SecretHighlightVisualTransformation(
                                spans = secretSpans,
                                highlightColor = highlightColor,
                                textColor = highlightTextColor,
                                onSecretClick = { secret ->
                                    copyToClipboard(secret, "shhhhhh_secret", "Copied secret: $secret")
                                }
                            )
                        } else {
                            VisualTransformation.None
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                            .pointerInput(decryptState, secretSpans) {
                                if (decryptState == DecryptButtonState.COPY_SECRET && secretSpans.isNotEmpty()) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                        val layout = textLayoutResult ?: return@awaitEachGesture
                                        val charOffset = layout.getOffsetForPosition(down.position)
                                        val matchedSpan = secretSpans.firstOrNull { span ->
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
                                                    "Copied secret: ${matchedSpan.secret}"
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                            modifier = Modifier
                                .fillMaxSize()
                                .onFocusChanged { isTextFieldFocused = it.isFocused },
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp
                            )
                        )
                    }

                    val currentOverlayError = overlayError
                    if (currentOverlayError != null) {
                        ErrorOverlay(
                            onDismiss = { overlayError = null },
                            onShowDetails = {
                                dialogError = currentOverlayError
                                overlayError = null
                            }
                        )
                    }
                }
            }

            // Action Buttons Row (Fixed Dimensions, Zero Layout Shift)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Encrypt / Morphing Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (encryptState == EncryptButtonState.ENCRYPT)
                                Color(0xFF1A73E8) // Vivid bright blue matching Decrypt green luminance
                            else
                                Color(0xFF5E35B1) // Vivid indigo/purple
                        )
                        .clickable {
                            overlayError = null
                            dialogError = null
                            if (encryptState == EncryptButtonState.ENCRYPT) {
                                val recipientPublicKey = when (selectedContact) {
                                    "Myself (This Device)" -> userKeyPair.publicKey
                                    else -> userKeyPair.publicKey
                                }
                                val ciphertext = CryptoEngine.encrypt(
                                    plaintext = text,
                                    recipientPublicKey = recipientPublicKey
                                )
                                textFieldValue = TextFieldValue(ciphertext, selection = TextRange(ciphertext.length))
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
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (encryptState == EncryptButtonState.ENCRYPT)
                                Icons.Default.Lock
                            else
                                Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (encryptState == EncryptButtonState.ENCRYPT) "Encrypt" else "Copy",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                // Decrypt / Morphing Button (Supports Tap and Long-Press)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (decryptState == DecryptButtonState.DECRYPT)
                                Color(0xFF2E7D32) // Forest green
                            else
                                Color(0xFFE65100) // Deep orange
                        )
                        .combinedClickable(
                            onClick = {
                                if (decryptState == DecryptButtonState.DECRYPT) {
                                    overlayError = null
                                    dialogError = null
                                    when (val result = CryptoEngine.decryptTextWithCandidates(
                                        inputText = text,
                                        recipientPrivateKey = userKeyPair.privateKey
                                    )) {
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
                                            textFieldValue = TextFieldValue(result.text, selection = TextRange(result.text.length))
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
                                                "Copied secret: ${action.secret}"
                                            )
                                        }
                                        is CopySecretAction.ShowPicker -> {
                                            showSecretPickerSheet = true
                                        }
                                        is CopySecretAction.None -> {
                                            if (text.isNotEmpty()) {
                                                copyToClipboard(
                                                    text,
                                                    "shhhhhh_secret",
                                                    "Copied secret: $text"
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                if (decryptState == DecryptButtonState.COPY_SECRET) {
                                    copyToClipboard(text, "shhhhhh_full", "Copied full message")
                                }
                            }
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (decryptState == DecryptButtonState.DECRYPT)
                                Icons.Default.LockOpen
                            else
                                Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (decryptState == DecryptButtonState.DECRYPT) "Decrypt" else "Copy Secret",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            // Contact Selector Widget (Positioned Adjacent to Action Buttons)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .combinedClickable(
                        onClick = { showContactSheet = true }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Recipient",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = selectedContact,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Select Contact",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Decryption Failure Detail Modal Dialog
        dialogError?.let { error ->
            AlertDialog(
                onDismissRequest = { dialogError = null },
                title = {
                    Text(text = "Decryption Failed")
                },
                text = {
                    Text(text = error.message)
                },
                confirmButton = {
                    TextButton(onClick = { dialogError = null }) {
                        Text(text = "OK")
                    }
                }
            )
        }

        // Contact Sheet Modal Placeholder (Piece 2)
        if (showContactSheet) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { showContactSheet = false },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Contact Selection Sheet (Piece 2)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Selected: $selectedContact",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { showContactSheet = false }) {
                        Text("Close")
                    }
                }
            }
        }

        // Multi-Secret Picker Bottom Sheet
        if (showSecretPickerSheet) {
            SecretPickerSheet(
                secrets = decryptedSecrets,
                onDismissRequest = { showSecretPickerSheet = false },
                onSelectSecret = { selectedSecret ->
                    showSecretPickerSheet = false
                    copyToClipboard(
                        selectedSecret,
                        "shhhhhh_secret",
                        "Copied secret: $selectedSecret"
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretPickerSheet(
    secrets: List<String>,
    onDismissRequest: () -> Unit,
    onSelectSecret: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp, top = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Select Secret to Copy",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            secrets.forEachIndexed { index, secret ->
                Surface(
                    onClick = {
                        onSelectSecret(secret)
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = secret,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 3
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy secret",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorOverlay(
    onDismiss: () -> Unit,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = onShowDetails,
            shape = CircleShape,
            color = Color(0xFFD32F2F),
            contentColor = Color.White,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Error",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
