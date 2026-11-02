package prefix924.shhhhhh

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import prefix924.shhhhhh.storage.ContactRecord

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
  var name by rememberSaveable { mutableStateOf("") }
  var publicKeyInput by rememberSaveable { mutableStateOf("") }
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
