package prefix924.shhhhhh

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import prefix924.shhhhhh.crypto.CryptoKeyPair
import prefix924.shhhhhh.storage.ContactRecord

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
