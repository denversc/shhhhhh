package prefix924.shhhhhh.storage

import android.content.SharedPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import prefix924.shhhhhh.crypto.CryptoEngine

class FakeSharedPreferences : SharedPreferences {
  val data = mutableMapOf<String, Any>()

  override fun getAll(): MutableMap<String, *> = HashMap(data)

  override fun getString(key: String?, defValue: String?): String? =
    data[key] as? String ?: defValue

  @Suppress("UNCHECKED_CAST")
  override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
    data[key] as? MutableSet<String> ?: defValues

  override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue

  override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue

  override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue

  override fun getBoolean(key: String?, defValue: Boolean): Boolean =
    data[key] as? Boolean ?: defValue

  override fun contains(key: String?): Boolean = data.containsKey(key)

  override fun edit(): SharedPreferences.Editor = FakeEditor(this)

  override fun registerOnSharedPreferenceChangeListener(
    listener: SharedPreferences.OnSharedPreferenceChangeListener?
  ) {}

  override fun unregisterOnSharedPreferenceChangeListener(
    listener: SharedPreferences.OnSharedPreferenceChangeListener?
  ) {}

  class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
    private val temp = mutableMapOf<String, Any?>()
    private var clearRequested = false

    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
      if (key != null) temp[key] = value
      return this
    }

    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
      if (key != null) temp[key] = values
      return this
    }

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
      if (key != null) temp[key] = value
      return this
    }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
      if (key != null) temp[key] = value
      return this
    }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
      if (key != null) temp[key] = value
      return this
    }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
      if (key != null) temp[key] = value
      return this
    }

    override fun remove(key: String?): SharedPreferences.Editor {
      if (key != null) temp[key] = null
      return this
    }

    override fun clear(): SharedPreferences.Editor {
      clearRequested = true
      return this
    }

    override fun commit(): Boolean {
      apply()
      return true
    }

    override fun apply() {
      if (clearRequested) {
        prefs.data.clear()
      }
      for ((k, v) in temp) {
        if (v == null) {
          prefs.data.remove(k)
        } else {
          prefs.data[k] = v
        }
      }
    }
  }
}

class AppStorageTest :
  FunSpec({
    val random = SecureRandom()

    // -------------------------------------------------------------------------
    // AesGcmCipher Tests
    // -------------------------------------------------------------------------

    test("AesGcmCipher successfully round-trips 32-byte private key scalar") {
      val masterKey = InMemoryMasterKeyProvider.generateSecretKey()
      val privateKey = ByteArray(32).also { random.nextBytes(it) }

      val encrypted = AesGcmCipher.encrypt(privateKey, masterKey)
      encrypted.size shouldBe
        (AesGcmCipher.GCM_IV_LENGTH_BYTES + 32 + AesGcmCipher.GCM_TAG_LENGTH_BYTES)

      val decrypted = AesGcmCipher.decrypt(encrypted, masterKey)
      decrypted shouldBe privateKey
    }

    test("AesGcmCipher successfully round-trips empty and arbitrary length payloads") {
      val masterKey = InMemoryMasterKeyProvider.generateSecretKey()
      val emptyBytes = ByteArray(0)
      val encryptedEmpty = AesGcmCipher.encrypt(emptyBytes, masterKey)
      AesGcmCipher.decrypt(encryptedEmpty, masterKey) shouldBe emptyBytes

      val largePayload = ByteArray(1024).also { random.nextBytes(it) }
      val encryptedLarge = AesGcmCipher.encrypt(largePayload, masterKey)
      AesGcmCipher.decrypt(encryptedLarge, masterKey) shouldBe largePayload
    }

    test("AesGcmCipher produces distinct ciphertexts and IVs across encrypt calls") {
      val masterKey = InMemoryMasterKeyProvider.generateSecretKey()
      val plaintext = "shhhhhh_sensitive_key_scalar".toByteArray(Charsets.UTF_8)

      val cipher1 = AesGcmCipher.encrypt(plaintext, masterKey)
      val cipher2 = AesGcmCipher.encrypt(plaintext, masterKey)

      cipher1 shouldNotBe cipher2
      val iv1 = cipher1.copyOfRange(0, AesGcmCipher.GCM_IV_LENGTH_BYTES)
      val iv2 = cipher2.copyOfRange(0, AesGcmCipher.GCM_IV_LENGTH_BYTES)
      iv1 shouldNotBe iv2

      AesGcmCipher.decrypt(cipher1, masterKey) shouldBe plaintext
      AesGcmCipher.decrypt(cipher2, masterKey) shouldBe plaintext
    }

    test("AesGcmCipher encrypt works with explicit SecureRandom") {
      val masterKey = InMemoryMasterKeyProvider.generateSecretKey()
      val plaintext = "test_with_explicit_random".toByteArray(Charsets.UTF_8)
      val customRandom = SecureRandom()

      val encrypted = AesGcmCipher.encrypt(plaintext, masterKey, customRandom)
      encrypted.size shouldBe
        (AesGcmCipher.GCM_IV_LENGTH_BYTES + plaintext.size + AesGcmCipher.GCM_TAG_LENGTH_BYTES)

      val decrypted = AesGcmCipher.decrypt(encrypted, masterKey)
      decrypted shouldBe plaintext
    }

    test("AesGcmCipher throws AEADBadTagException when ciphertext body is tampered with") {
      val masterKey = InMemoryMasterKeyProvider.generateSecretKey()
      val plaintext = ByteArray(32).also { random.nextBytes(it) }
      val encrypted = AesGcmCipher.encrypt(plaintext, masterKey)

      // Flip bit in ciphertext body (after 12-byte IV)
      encrypted[15] = (encrypted[15].toInt() xor 0x01).toByte()

      shouldThrow<AEADBadTagException> { AesGcmCipher.decrypt(encrypted, masterKey) }
    }

    test("AesGcmCipher throws AEADBadTagException when IV is tampered with") {
      val masterKey = InMemoryMasterKeyProvider.generateSecretKey()
      val plaintext = ByteArray(32).also { random.nextBytes(it) }
      val encrypted = AesGcmCipher.encrypt(plaintext, masterKey)

      // Flip bit in IV
      encrypted[0] = (encrypted[0].toInt() xor 0x01).toByte()

      shouldThrow<AEADBadTagException> { AesGcmCipher.decrypt(encrypted, masterKey) }
    }

    test("AesGcmCipher throws AEADBadTagException when decrypted with wrong key") {
      val masterKey1 = InMemoryMasterKeyProvider.generateSecretKey()
      val masterKey2 = InMemoryMasterKeyProvider.generateSecretKey()
      val plaintext = ByteArray(32).also { random.nextBytes(it) }
      val encrypted = AesGcmCipher.encrypt(plaintext, masterKey1)

      shouldThrow<AEADBadTagException> { AesGcmCipher.decrypt(encrypted, masterKey2) }
    }

    test("AesGcmCipher throws IllegalArgumentException when ciphertext is shorter than overhead") {
      val masterKey = InMemoryMasterKeyProvider.generateSecretKey()
      val shortBuffer = ByteArray(20) // Minimum is 12 + 16 = 28 bytes

      shouldThrow<IllegalArgumentException> { AesGcmCipher.decrypt(shortBuffer, masterKey) }
    }

    // -------------------------------------------------------------------------
    // ContactSerialization Tests
    // -------------------------------------------------------------------------

    test("ContactSerialization round-trips empty list") {
      val serialized = ContactSerialization.serialize(emptyList())
      serialized shouldBe "[]"

      ContactSerialization.deserialize(serialized).shouldBeEmpty()
      ContactSerialization.deserialize("").shouldBeEmpty()
      ContactSerialization.deserialize("   ").shouldBeEmpty()
      ContactSerialization.deserialize("[ ]").shouldBeEmpty()
      ContactSerialization.deserialize("  [   ]  ").shouldBeEmpty()
    }

    test("ContactSerialization round-trips single contact") {
      val contact =
        ContactRecord(
          id = "c1b97940-d996-48cb-8f15-fec719c8f074",
          name = "Alice",
          publicKeyBase64Url = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          createdAt = 1700000000000L,
        )

      val json = ContactSerialization.serialize(listOf(contact))
      val deserialized = ContactSerialization.deserialize(json)

      deserialized shouldHaveSize 1
      deserialized[0] shouldBe contact
    }

    test("ContactSerialization round-trips multiple contacts in order") {
      val c1 =
        ContactRecord(id = "1", name = "Alice", publicKeyBase64Url = "key1", createdAt = 100L)
      val c2 = ContactRecord(id = "2", name = "Bob", publicKeyBase64Url = "key2", createdAt = 200L)
      val c3 =
        ContactRecord(id = "3", name = "Charlie", publicKeyBase64Url = "key3", createdAt = 300L)

      val list = listOf(c1, c2, c3)
      val json = ContactSerialization.serialize(list)
      val deserialized = ContactSerialization.deserialize(json)

      deserialized shouldContainExactly list
    }

    test("ContactSerialization handles commas, quotes, backslashes, newlines, and emojis") {
      val contacts =
        listOf(
          ContactRecord(
            id = "commas",
            name = "Smith, John Jr., Esq.",
            publicKeyBase64Url = "key_comma",
            createdAt = 10L,
          ),
          ContactRecord(
            id = "quotes",
            name = "Alice \"The Brain\" Johnson",
            publicKeyBase64Url = "key_quotes",
            createdAt = 20L,
          ),
          ContactRecord(
            id = "escapes",
            name = "Path\\To\\Folder\nLine2\tTabbed\rCarriage",
            publicKeyBase64Url = "key_escapes",
            createdAt = 30L,
          ),
          ContactRecord(
            id = "emojis",
            name = "Denver 🕵️‍♂️ 🔒 ✨ こんにちは 🚀",
            publicKeyBase64Url = "key_emojis",
            createdAt = 40L,
          ),
        )

      val json = ContactSerialization.serialize(contacts)
      val deserialized = ContactSerialization.deserialize(json)

      deserialized shouldContainExactly contacts
    }

    test("ContactSerialization throws on malformed JSON") {
      shouldThrow<IllegalArgumentException> { ContactSerialization.deserialize("not a json array") }
      shouldThrow<IllegalArgumentException> { ContactSerialization.deserialize("{\"id\":\"1\"}") }
    }

    // -------------------------------------------------------------------------
    // EncryptedAppStorage Tests
    // -------------------------------------------------------------------------

    test("EncryptedAppStorage returns clean defaults") {
      val prefs = FakeSharedPreferences()
      val masterKeyProvider = InMemoryMasterKeyProvider()
      val storage = EncryptedAppStorage(prefs, masterKeyProvider)

      storage.loadKeyPair() shouldBe null
      storage.hasCompletedOnboarding() shouldBe false
      storage.getContacts().shouldBeEmpty()
      storage.getTheme() shouldBe null
    }

    test("EncryptedAppStorage saves, loads, and clears CryptoKeyPair") {
      val prefs = FakeSharedPreferences()
      val masterKeyProvider = InMemoryMasterKeyProvider()
      val storage = EncryptedAppStorage(prefs, masterKeyProvider)

      val keyPair = CryptoEngine.generateKeyPair()
      storage.saveKeyPair(keyPair)

      val loaded = storage.loadKeyPair()
      loaded shouldNotBe null
      loaded!!.privateKey shouldBe keyPair.privateKey
      loaded.publicKey shouldBe keyPair.publicKey
      loaded.publicKeyBase64Url shouldBe keyPair.publicKeyBase64Url

      // Raw encrypted value stored in SharedPreferences is ciphertext, not plaintext private key
      val storedEncryptedPriv = prefs.getString(EncryptedAppStorage.KEY_ENCRYPTED_PRIVATE_KEY, null)
      storedEncryptedPriv shouldNotBe null
      val decodedStored = Base64.getUrlDecoder().decode(storedEncryptedPriv)
      decodedStored shouldNotBe keyPair.privateKey

      // Public key is stored in unpadded Base64URL
      prefs.getString(EncryptedAppStorage.KEY_PUBLIC_KEY, null) shouldBe keyPair.publicKeyBase64Url

      // Clear key pair
      storage.clearKeyPair()
      storage.loadKeyPair() shouldBe null
      prefs.getString(EncryptedAppStorage.KEY_ENCRYPTED_PRIVATE_KEY, null) shouldBe null
      prefs.getString(EncryptedAppStorage.KEY_PUBLIC_KEY, null) shouldBe null
    }

    test("EncryptedAppStorage sets and retrieves onboarding completion flag") {
      val prefs = FakeSharedPreferences()
      val masterKeyProvider = InMemoryMasterKeyProvider()
      val storage = EncryptedAppStorage(prefs, masterKeyProvider)

      storage.hasCompletedOnboarding() shouldBe false
      storage.setCompletedOnboarding(true)
      storage.hasCompletedOnboarding() shouldBe true

      storage.setCompletedOnboarding(false)
      storage.hasCompletedOnboarding() shouldBe false
    }

    test("EncryptedAppStorage performs CRUD operations on contacts and sorts by createdAt") {
      val prefs = FakeSharedPreferences()
      val masterKeyProvider = InMemoryMasterKeyProvider()
      val storage = EncryptedAppStorage(prefs, masterKeyProvider)

      val c1 = ContactRecord(id = "c1", name = "Bob", publicKeyBase64Url = "keyB", createdAt = 200L)
      val c2 =
        ContactRecord(id = "c2", name = "Alice", publicKeyBase64Url = "keyA", createdAt = 100L)

      // Save contacts in unsorted order
      storage.saveContact(c1)
      storage.saveContact(c2)

      // Retrieved contacts must be ordered by createdAt (c2 then c1)
      val contacts = storage.getContacts()
      contacts shouldHaveSize 2
      contacts[0] shouldBe c2
      contacts[1] shouldBe c1

      // Update contact c1
      val updatedC1 = c1.copy(name = "Robert")
      storage.saveContact(updatedC1)
      val updatedContacts = storage.getContacts()
      updatedContacts shouldHaveSize 2
      updatedContacts[1].name shouldBe "Robert"
      updatedContacts[1].id shouldBe "c1"

      // Delete contact c2
      storage.deleteContact("c2")
      val remaining = storage.getContacts()
      remaining shouldHaveSize 1
      remaining[0] shouldBe updatedC1

      // Delete non-existent contact
      storage.deleteContact("non-existent")
      storage.getContacts() shouldHaveSize 1
    }

    test("EncryptedAppStorage gracefully handles tampered ciphertext by returning null") {
      val prefs = FakeSharedPreferences()
      val masterKeyProvider = InMemoryMasterKeyProvider()
      val storage = EncryptedAppStorage(prefs, masterKeyProvider)

      val keyPair = CryptoEngine.generateKeyPair()
      storage.saveKeyPair(keyPair)

      // Tamper with encrypted private key in SharedPreferences
      val validB64 = prefs.getString(EncryptedAppStorage.KEY_ENCRYPTED_PRIVATE_KEY, null)!!
      val bytes = Base64.getUrlDecoder().decode(validB64)
      bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x55).toByte()
      prefs.data[EncryptedAppStorage.KEY_ENCRYPTED_PRIVATE_KEY] =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

      // Must gracefully return null without throwing AEADBadTagException
      storage.loadKeyPair() shouldBe null

      // Corrupt with invalid base64 characters
      prefs.data[EncryptedAppStorage.KEY_ENCRYPTED_PRIVATE_KEY] = "!!! Not Valid Base64 !!!"
      storage.loadKeyPair() shouldBe null
    }

    test("EncryptedAppStorage gracefully handles corrupted contacts JSON by returning empty list") {
      val prefs = FakeSharedPreferences()
      val masterKeyProvider = InMemoryMasterKeyProvider()
      val storage = EncryptedAppStorage(prefs, masterKeyProvider)

      prefs.data[EncryptedAppStorage.KEY_CONTACTS] = "corrupted_contacts_json_not_array"
      storage.getContacts().shouldBeEmpty()
    }

    test("EncryptedAppStorage persists and clears theme preference and clearAll wipes all data") {
      val prefs = FakeSharedPreferences()
      val masterKeyProvider = InMemoryMasterKeyProvider()
      val storage = EncryptedAppStorage(prefs, masterKeyProvider)

      storage.setTheme("DARK")
      storage.getTheme() shouldBe "DARK"

      val keyPair = CryptoEngine.generateKeyPair()
      storage.saveKeyPair(keyPair)
      storage.setCompletedOnboarding(true)
      storage.saveContact(
        ContactRecord(id = "1", name = "Test", publicKeyBase64Url = "key", createdAt = 1L)
      )

      storage.clearAll()

      storage.loadKeyPair() shouldBe null
      storage.hasCompletedOnboarding() shouldBe false
      storage.getContacts().shouldBeEmpty()
      storage.getTheme() shouldBe null
    }
  })
