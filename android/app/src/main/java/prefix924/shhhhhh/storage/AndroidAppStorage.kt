package prefix924.shhhhhh.storage

import android.content.Context

/**
 * Factory for creating [AppStorage] instances backed by device-encrypted storage
 * and the hardware/TEE [AndroidKeyStoreMasterKeyProvider].
 */
object AndroidAppStorage {
    const val PREFS_NAME = "prefix924_shhhhhh_secure_prefs"

    fun create(
        context: Context,
        masterKeyProvider: MasterKeyProvider = AndroidKeyStoreMasterKeyProvider()
    ): AppStorage {
        val appContext = context.applicationContext ?: context
        val sharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return EncryptedAppStorage(
            sharedPreferences = sharedPreferences,
            masterKeyProvider = masterKeyProvider
        )
    }
}
