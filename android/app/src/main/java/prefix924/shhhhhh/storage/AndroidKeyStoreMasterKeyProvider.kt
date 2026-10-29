package prefix924.shhhhhh.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [MasterKeyProvider] backed by hardware-backed or TEE AndroidKeyStore. Generates an AES-256-GCM
 * master key protected by the device secure element.
 */
class AndroidKeyStoreMasterKeyProvider(private val keyAlias: String = DEFAULT_KEY_ALIAS) :
  MasterKeyProvider {

  override fun getOrCreateMasterKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    if (keyStore.containsAlias(keyAlias)) {
      (keyStore.getKey(keyAlias, null) as? SecretKey)?.let {
        return it
      }
      (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let {
        return it.secretKey
      }
    }

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec =
      KeyGenParameterSpec.Builder(
          keyAlias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val DEFAULT_KEY_ALIAS = "prefix924_shhhhhh_master_key"
  }
}
