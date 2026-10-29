package prefix924.shhhhhh.storage

import android.content.SharedPreferences
import java.util.Base64
import prefix924.shhhhhh.crypto.CryptoEngine
import prefix924.shhhhhh.crypto.CryptoKeyPair

/**
 * Production [AppStorage] implementation backed by Android [SharedPreferences] and AES-256-GCM
 * encryption at rest via [MasterKeyProvider].
 *
 * Persists:
 * - User X25519 identity key pair (private key encrypted with AES-256-GCM, public key in Base64URL)
 * - Onboarding completion flag
 * - Serialized contacts list via [ContactSerialization]
 * - Appearance theme preference
 */
class EncryptedAppStorage(
  private val sharedPreferences: SharedPreferences,
  private val masterKeyProvider: MasterKeyProvider,
) : AppStorage {

  private val contactsLock = Any()

  override fun saveKeyPair(keyPair: CryptoKeyPair) {
    val masterKey = masterKeyProvider.getOrCreateMasterKey()
    val encryptedPrivateBytes = AesGcmCipher.encrypt(keyPair.privateKey, masterKey)
    val encryptedPrivateBase64Url =
      Base64.getUrlEncoder().withoutPadding().encodeToString(encryptedPrivateBytes)

    sharedPreferences
      .edit()
      .putString(KEY_ENCRYPTED_PRIVATE_KEY, encryptedPrivateBase64Url)
      .putString(KEY_PUBLIC_KEY, keyPair.publicKeyBase64Url)
      .apply()
  }

  override fun loadKeyPair(): CryptoKeyPair? {
    val encryptedPrivB64 =
      sharedPreferences.getString(KEY_ENCRYPTED_PRIVATE_KEY, null) ?: return null
    val pubB64 = sharedPreferences.getString(KEY_PUBLIC_KEY, null)

    return try {
      val encryptedBytes = Base64.getUrlDecoder().decode(encryptedPrivB64)
      val masterKey = masterKeyProvider.getOrCreateMasterKey()
      val decrypted = AesGcmCipher.decrypt(encryptedBytes, masterKey)

      val rawPrivate =
        when (decrypted.size) {
          CryptoEngine.KEY_SIZE_BYTES -> decrypted
          43 -> Base64.getUrlDecoder().decode(decrypted)
          else -> {
            val str = String(decrypted, Charsets.UTF_8)
            val decoded = Base64.getUrlDecoder().decode(str)
            if (decoded.size == CryptoEngine.KEY_SIZE_BYTES) decoded else return null
          }
        }
      if (rawPrivate.size != CryptoEngine.KEY_SIZE_BYTES) return null

      val rawPublic =
        if (!pubB64.isNullOrBlank()) {
          try {
            val decodedPub = Base64.getUrlDecoder().decode(pubB64)
            if (decodedPub.size == CryptoEngine.KEY_SIZE_BYTES) {
              decodedPub
            } else {
              CryptoEngine.derivePublicKey(rawPrivate)
            }
          } catch (_: Exception) {
            CryptoEngine.derivePublicKey(rawPrivate)
          }
        } else {
          CryptoEngine.derivePublicKey(rawPrivate)
        }

      CryptoKeyPair(privateKey = rawPrivate, publicKey = rawPublic)
    } catch (_: Exception) {
      // Tampered ciphertext, bad auth tag, corrupted base64, etc.
      null
    }
  }

  override fun clearKeyPair() {
    sharedPreferences.edit().remove(KEY_ENCRYPTED_PRIVATE_KEY).remove(KEY_PUBLIC_KEY).apply()
  }

  override fun hasCompletedOnboarding(): Boolean {
    return sharedPreferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
  }

  override fun setCompletedOnboarding(completed: Boolean) {
    sharedPreferences.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
  }

  override fun getContacts(): List<ContactRecord> {
    val rawJson = sharedPreferences.getString(KEY_CONTACTS, null) ?: return emptyList()
    return try {
      ContactSerialization.deserialize(rawJson).sortedBy { it.createdAt }
    } catch (_: Exception) {
      emptyList()
    }
  }

  override fun saveContact(contact: ContactRecord) {
    synchronized(contactsLock) {
      val contacts = getContacts().toMutableList()
      val existingIndex = contacts.indexOfFirst { it.id == contact.id }
      if (existingIndex >= 0) {
        contacts[existingIndex] = contact
      } else {
        contacts.add(contact)
      }
      val json = ContactSerialization.serialize(contacts)
      sharedPreferences.edit().putString(KEY_CONTACTS, json).apply()
    }
  }

  override fun deleteContact(id: String) {
    synchronized(contactsLock) {
      val contacts = getContacts().toMutableList()
      val removed = contacts.removeAll { it.id == id }
      if (removed) {
        val json = ContactSerialization.serialize(contacts)
        sharedPreferences.edit().putString(KEY_CONTACTS, json).apply()
      }
    }
  }

  override fun getTheme(): String? {
    return sharedPreferences.getString(KEY_THEME, null)
  }

  override fun setTheme(theme: String) {
    sharedPreferences.edit().putString(KEY_THEME, theme).apply()
  }

  override fun clearAll() {
    sharedPreferences.edit().clear().apply()
  }

  companion object {
    const val KEY_ENCRYPTED_PRIVATE_KEY = "encrypted_private_key"
    const val KEY_PUBLIC_KEY = "public_key"
    const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    const val KEY_CONTACTS = "contacts_json"
    const val KEY_THEME = "theme_preference"
  }
}
