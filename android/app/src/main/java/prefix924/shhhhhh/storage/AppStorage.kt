package prefix924.shhhhhh.storage

import prefix924.shhhhhh.crypto.CryptoKeyPair

/**
 * Storage interface for managing user cryptographic identity keys and onboarding state.
 */
interface KeyStorage {
    /**
     * Securely saves the user's asymmetric key pair to persistent storage.
     */
    fun saveKeyPair(keyPair: CryptoKeyPair)

    /**
     * Loads the user's asymmetric key pair from persistent storage, or null if not yet generated or corrupted.
     */
    fun loadKeyPair(): CryptoKeyPair?

    /**
     * Clears the user's key pair from storage.
     */
    fun clearKeyPair()

    /**
     * Checks if the first-launch onboarding ceremony has been completed.
     */
    fun hasCompletedOnboarding(): Boolean

    /**
     * Sets whether first-launch onboarding has been completed.
     */
    fun setCompletedOnboarding(completed: Boolean)
}

/**
 * Storage interface for managing saved contacts.
 */
interface ContactStorage {
    /**
     * Retrieves all saved contacts, ordered by creation time.
     */
    fun getContacts(): List<ContactRecord>

    /**
     * Saves or updates a contact record.
     */
    fun saveContact(contact: ContactRecord)

    /**
     * Deletes a contact record by its unique ID.
     */
    fun deleteContact(id: String)
}

/**
 * Unified application storage interface combining key storage, contacts, and preferences.
 */
interface AppStorage : KeyStorage, ContactStorage {
    /**
     * Gets preferred appearance mode ("SYSTEM", "LIGHT", "DARK"), or null if unset.
     */
    fun getTheme(): String?

    /**
     * Saves preferred appearance mode.
     */
    fun setTheme(theme: String)

    /**
     * Wipes all persisted application state.
     */
    fun clearAll()
}
