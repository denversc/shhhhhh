package prefix924.shhhhhh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import prefix924.shhhhhh.ui.theme.ShhhhhhTheme

private const val SAMPLE_CIPHERTEXT =
    "xK89_mZ2Q1pA0-BvL81kLz0P82kL9xK89mZ2Q1pA0-BvL81kLz0P82kL9xK89mZ2Q1pA0-BvL81kLz0P82kL9"
private const val SAMPLE_PLAINTEXT = "BankPassword123!"
private val CIPHERTEXT_CANDIDATE_REGEX = Regex("[A-Za-z0-9_-]{80,}")

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
        "No Base64URL ciphertext string of 80 or more characters was found in the text box."
    ),
    AUTH_FAILED(
        "Ciphertext was found, but it could not be decrypted with this device's private key. It may have been intended for a different recipient or corrupted."
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShhhhhhTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var text by remember { mutableStateOf("") }
    var encryptState by remember { mutableStateOf(EncryptButtonState.ENCRYPT) }
    var decryptState by remember { mutableStateOf(DecryptButtonState.DECRYPT) }
    var overlayError by remember { mutableStateOf<DecryptError?>(null) }
    var dialogError by remember { mutableStateOf<DecryptError?>(null) }
    var selectedContact by remember { mutableStateOf("Myself (This Device)") }
    var showContactSheet by remember { mutableStateOf(false) }

    fun copyToClipboard(content: String, label: String, message: String) {
        val clip = ClipData.newPlainText(label, content)
        clipboardManager.setPrimaryClip(clip)
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    fun triggerDecryptError(error: DecryptError) {
        overlayError = error
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
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            text = ""
                            encryptState = EncryptButtonState.ENCRYPT
                            decryptState = DecryptButtonState.DECRYPT
                            overlayError = null
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

                    Spacer(modifier = Modifier.width(8.dp))

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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                text = "Type message to encrypt or paste text containing ciphertext here...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }

                        BasicTextField(
                            value = text,
                            onValueChange = { newText ->
                                text = newText
                                overlayError = null
                                if (encryptState == EncryptButtonState.COPY) {
                                    encryptState = EncryptButtonState.ENCRYPT
                                }
                                if (decryptState == DecryptButtonState.COPY_SECRET) {
                                    decryptState = DecryptButtonState.DECRYPT
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
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
                            if (encryptState == EncryptButtonState.ENCRYPT) {
                                text = SAMPLE_CIPHERTEXT
                                encryptState = EncryptButtonState.COPY
                                decryptState = DecryptButtonState.DECRYPT
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
                                    val matches = CIPHERTEXT_CANDIDATE_REGEX.findAll(text).toList()
                                    if (matches.isEmpty()) {
                                        decryptState = DecryptButtonState.DECRYPT
                                        encryptState = EncryptButtonState.ENCRYPT
                                        triggerDecryptError(DecryptError.NO_CANDIDATES)
                                    } else {
                                        var anyDecrypted = false
                                        val replacedText = CIPHERTEXT_CANDIDATE_REGEX.replace(text) { match ->
                                            if (match.value == SAMPLE_CIPHERTEXT) {
                                                anyDecrypted = true
                                                SAMPLE_PLAINTEXT
                                            } else {
                                                match.value
                                            }
                                        }
                                        if (anyDecrypted) {
                                            text = replacedText
                                            decryptState = DecryptButtonState.COPY_SECRET
                                            encryptState = EncryptButtonState.ENCRYPT
                                            overlayError = null
                                            dialogError = null
                                        } else {
                                            decryptState = DecryptButtonState.DECRYPT
                                            encryptState = EncryptButtonState.ENCRYPT
                                            triggerDecryptError(DecryptError.AUTH_FAILED)
                                        }
                                    }
                                } else {
                                    copyToClipboard(
                                        SAMPLE_PLAINTEXT,
                                        "shhhhhh_secret",
                                        "Copied secret: $SAMPLE_PLAINTEXT"
                                    )
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
