package prefix924.shhhhhh

import android.content.ClipboardManager
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val TAP_ENTROPY_INCREMENT = 0.013f
internal const val MOVE_ENTROPY_INCREMENT = 0.0017f
internal const val MIN_MOVE_DISTANCE_PX = 3f
private const val MAX_VISUAL_SEGMENTS = 1800
private const val MAX_VISUAL_DOTS = 400
private const val MIN_KEYGEN_SPINNER_MS = 650L

internal class EntropyAccumulator {
    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    private var sampleCount: Int = 0

    @Synchronized
    fun addSample(
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        timestampNanos: Long
    ) {
        val buffer = ByteBuffer.allocate(24)
            .putFloat(x)
            .putFloat(y)
            .putFloat(dx)
            .putFloat(dy)
            .putLong(timestampNanos)
        digest.update(buffer.array())
        sampleCount++
    }

    @Synchronized
    fun getSampleCount(): Int = sampleCount

    @Synchronized
    fun generateCombinedSeed(secureRandom: SecureRandom = SecureRandom()): ByteArray {
        val userDigest = digest.digest()
        val systemEntropy = ByteArray(32).also { secureRandom.nextBytes(it) }
        val combiner = MessageDigest.getInstance("SHA-256")
        combiner.update(userDigest)
        combiner.update(systemEntropy)
        return combiner.digest()
    }
}

private data class StrokeSegment(
    val start: Offset,
    val end: Offset
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    val entropyAccumulator = remember { EntropyAccumulator() }
    val segments = remember { mutableStateListOf<StrokeSegment>() }
    val dots = remember { mutableStateListOf<Offset>() }

    var progress by rememberSaveable { mutableFloatStateOf(0f) }
    var isKeyReady by rememberSaveable { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }

    var showRestoreDialog by rememberSaveable { mutableStateOf(false) }
    var backupInput by rememberSaveable { mutableStateOf("") }
    var restoreError by rememberSaveable { mutableStateOf<String?>(null) }

    val hasReachedFullEntropy = progress >= 1f

    LaunchedEffect(hasReachedFullEntropy, isKeyReady) {
        if (hasReachedFullEntropy && !isKeyReady) {
            isGenerating = true
            try {
                withContext(Dispatchers.Default) {
                    val startTimeMs = System.currentTimeMillis()
                    entropyAccumulator.generateCombinedSeed()
                    val elapsedMs = System.currentTimeMillis() - startTimeMs
                    val remainingMs = MIN_KEYGEN_SPINNER_MS - elapsedMs
                    if (remainingMs > 0L) {
                        delay(remainingMs)
                    }
                }
                isKeyReady = true
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } finally {
                isGenerating = false
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "shhhhhh",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header & Instructions
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Welcome to shhhhhh",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Private end-to-end encryption for your text messages. To initialize your key, scribble or tap randomly in the box:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Interactive Entropy Canvas
            val strokeColor = Color(0xFF1A73E8)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .pointerInput(hasReachedFullEntropy) {
                        if (hasReachedFullEntropy) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (progress >= 1f) return@awaitEachGesture
                            down.consume()

                            var lastPosition = down.position
                            var dragged = false

                            entropyAccumulator.addSample(
                                x = lastPosition.x,
                                y = lastPosition.y,
                                dx = 0f,
                                dy = 0f,
                                timestampNanos = System.nanoTime()
                            )
                            if (dots.size >= MAX_VISUAL_DOTS) {
                                dots.removeAt(0)
                            }
                            dots.add(lastPosition)
                            progress = (progress + TAP_ENTROPY_INCREMENT).coerceAtMost(1f)

                            while (progress < 1f) {
                                val event = awaitPointerEvent()
                                val pointerChange = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break

                                if (!pointerChange.pressed) {
                                    pointerChange.consume()
                                    break
                                }

                                val currentPosition = pointerChange.position
                                val dx = currentPosition.x - lastPosition.x
                                val dy = currentPosition.y - lastPosition.y
                                val distance = hypot(dx, dy)

                                if (distance >= MIN_MOVE_DISTANCE_PX) {
                                    if (!dragged && dots.isNotEmpty()) {
                                        dots.removeAt(dots.lastIndex)
                                    }
                                    dragged = true

                                    entropyAccumulator.addSample(
                                        x = currentPosition.x,
                                        y = currentPosition.y,
                                        dx = dx,
                                        dy = dy,
                                        timestampNanos = System.nanoTime()
                                    )
                                    if (segments.size >= MAX_VISUAL_SEGMENTS) {
                                        segments.removeAt(0)
                                    }
                                    segments.add(
                                        StrokeSegment(
                                            start = lastPosition,
                                            end = currentPosition
                                        )
                                    )
                                    lastPosition = currentPosition
                                    progress = (progress + MOVE_ENTROPY_INCREMENT).coerceAtMost(1f)
                                }
                                pointerChange.consume()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (segments.isEmpty() && dots.isEmpty() && progress == 0f) {
                    Text(
                        text = "Scribble or tap randomly here",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidthPx = 3.dp.toPx()
                    val dotRadiusPx = 4.dp.toPx()

                    val totalSegments = segments.size
                    segments.forEachIndexed { index, segment ->
                        val ageRatio = if (totalSegments <= 1) {
                            1f
                        } else {
                            (index + 1).toFloat() / totalSegments.toFloat()
                        }
                        drawLine(
                            color = strokeColor.copy(alpha = 0.25f + 0.65f * ageRatio),
                            start = segment.start,
                            end = segment.end,
                            strokeWidth = strokeWidthPx,
                            cap = StrokeCap.Round
                        )
                    }

                    val totalDots = dots.size
                    dots.forEachIndexed { index, point ->
                        val ageRatio = if (totalDots <= 1) {
                            1f
                        } else {
                            (index + 1).toFloat() / totalDots.toFloat()
                        }
                        drawCircle(
                            color = strokeColor.copy(alpha = 0.35f + 0.55f * ageRatio),
                            radius = dotRadiusPx,
                            center = point
                        )
                    }
                }
            }

            // Entropy Progress Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Entropy Collection",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${(progress * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = Color(0xFF1A73E8),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Primary Action / Key Generation Status Button (50.dp height, 10.dp corner radius)
            Button(
                onClick = onComplete,
                enabled = isKeyReady,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A73E8),
                    contentColor = Color.White,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                when {
                    isKeyReady -> {
                        Text(
                            text = "Get Started",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    isGenerating || hasReachedFullEntropy -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Generating Key Pair...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    else -> {
                        Text(
                            text = "Scribble or Tap to Collect Entropy",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.5f))

            // Secondary Action: Restore from Backup
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = {
                        restoreError = null
                        showRestoreDialog = true
                    },
                    enabled = !isGenerating && !(hasReachedFullEntropy && !isKeyReady)
                ) {
                    Text(
                        text = "Restore from Backup",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Restore from Paper Backup Dialog
        if (showRestoreDialog) {
            AlertDialog(
                onDismissRequest = {
                    showRestoreDialog = false
                    restoreError = null
                },
                title = {
                    Text(text = "Restore from Backup")
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Paste your Paper Backup bundle below to restore your keys and contacts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = backupInput,
                            onValueChange = { updated ->
                                backupInput = updated
                                if (restoreError != null) {
                                    restoreError = null
                                }
                            },
                            placeholder = {
                                Text(text = "Paste Paper Backup string here...")
                            },
                            minLines = 3,
                            maxLines = 5,
                            isError = restoreError != null,
                            supportingText = restoreError?.let { errorMessage ->
                                {
                                    Text(
                                        text = errorMessage,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedButton(
                            onClick = {
                                val pasted = clipboardManager.primaryClip
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    .orEmpty()
                                if (pasted.isNotEmpty()) {
                                    backupInput = pasted
                                    restoreError = null
                                } else {
                                    restoreError = "Clipboard is empty."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Paste from Clipboard")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showRestoreDialog = false
                            backupInput = ""
                            restoreError = null
                        }
                    ) {
                        Text(text = "Cancel")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (backupInput.isBlank()) {
                                restoreError = "Please paste a backup bundle before restoring."
                            } else {
                                showRestoreDialog = false
                                backupInput = ""
                                restoreError = null
                                onComplete()
                            }
                        }
                    ) {
                        Text(text = "Restore")
                    }
                }
            )
        }
    }
}
