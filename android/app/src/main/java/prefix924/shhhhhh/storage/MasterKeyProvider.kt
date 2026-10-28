package prefix924.shhhhhh.storage

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Provider interface for acquiring or generating an AES-256 master key for encryption at rest.
 */
fun interface MasterKeyProvider {
    fun getOrCreateMasterKey(): SecretKey
}

/**
 * MasterKeyProvider implementation backed by an in-memory AES-256 key.
 * Used for unit tests, previews, and non-Android environments.
 */
class InMemoryMasterKeyProvider(
    private val secretKey: SecretKey = generateSecretKey()
) : MasterKeyProvider {

    override fun getOrCreateMasterKey(): SecretKey = secretKey

    companion object {
        fun generateSecretKey(): SecretKey {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            return keyGen.generateKey()
        }
    }
}
