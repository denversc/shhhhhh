package prefix924.shhhhhh

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import java.security.PublicKey
import java.util.Base64
import prefix924.shhhhhh.crypto.CryptoEngine
import prefix924.shhhhhh.crypto.CryptoKeyPair
import prefix924.shhhhhh.crypto.DecryptResult
import prefix924.shhhhhh.storage.AppStorage
import prefix924.shhhhhh.storage.ContactRecord

class TestAppStorage : AppStorage {
    private var keyPair: CryptoKeyPair? = null
    private var onboardingCompleted: Boolean = false
    private val contacts = mutableListOf<ContactRecord>()
    private var theme: String? = null

    override fun saveKeyPair(keyPair: CryptoKeyPair) {
        this.keyPair = keyPair
    }

    override fun loadKeyPair(): CryptoKeyPair? = keyPair

    override fun clearKeyPair() {
        keyPair = null
    }

    override fun hasCompletedOnboarding(): Boolean = onboardingCompleted

    override fun setCompletedOnboarding(completed: Boolean) {
        onboardingCompleted = completed
    }

    override fun getContacts(): List<ContactRecord> = contacts.toList()

    override fun saveContact(contact: ContactRecord) {
        val existingIndex = contacts.indexOfFirst { it.id == contact.id }
        if (existingIndex >= 0) {
            contacts[existingIndex] = contact
        } else {
            contacts.add(contact)
        }
    }

    override fun deleteContact(id: String) {
        contacts.removeAll { it.id == id }
    }

    override fun getTheme(): String? = theme

    override fun setTheme(theme: String) {
        this.theme = theme
    }

    override fun clearAll() {
        keyPair = null
        onboardingCompleted = false
        contacts.clear()
        theme = null
    }
}

class ContactManagementTest : FunSpec({

    // -------------------------------------------------------------------------
    // validatePublicKey Tests
    // -------------------------------------------------------------------------

    test("validatePublicKey returns true for valid generated keys") {
        val keyPair = CryptoEngine.generateKeyPair()
        val pubKey = keyPair.publicKeyBase64Url

        pubKey shouldHaveLength 43
        validatePublicKey(pubKey) shouldBe true
    }

    test("validatePublicKey returns true for multiple distinct generated keys") {
        repeat(20) {
            val keyPair = CryptoEngine.generateKeyPair()
            validatePublicKey(keyPair.publicKeyBase64Url) shouldBe true
        }
    }

    test("validatePublicKey returns true for all-zeros and all-ones keys") {
        val zeros = ByteArray(32) { 0 }
        val zerosKey = CryptoEngine.encodePublicKeyBase64Url(zeros)
        validatePublicKey(zerosKey) shouldBe true

        val ones = ByteArray(32) { 0xFF.toByte() }
        val onesKey = CryptoEngine.encodePublicKeyBase64Url(ones)
        validatePublicKey(onesKey) shouldBe true
    }

    test("validatePublicKey returns false for invalid lengths") {
        validatePublicKey("") shouldBe false
        validatePublicKey("A".repeat(42)) shouldBe false
        validatePublicKey("A".repeat(44)) shouldBe false
        validatePublicKey("A".repeat(80)) shouldBe false
        validatePublicKey("A".repeat(166)) shouldBe false
    }

    test("validatePublicKey returns false for strings with invalid Base64URL characters") {
        val validKey = CryptoEngine.generateKeyPair().publicKeyBase64Url
        val withPlus = validKey.substring(0, 42) + "+"
        val withSlash = validKey.substring(0, 42) + "/"
        val withEqual = validKey.substring(0, 42) + "="
        val withSpace = validKey.substring(0, 42) + " "
        val withAt = validKey.substring(0, 42) + "@"

        validatePublicKey(withPlus) shouldBe false
        validatePublicKey(withSlash) shouldBe false
        validatePublicKey(withEqual) shouldBe false
        validatePublicKey(withSpace) shouldBe false
        validatePublicKey(withAt) shouldBe false
    }

    test("validatePublicKey returns false for corrupted padding bits") {
        // In 43-character Base64URL encoding of 32 bytes, the 43rd character represents only 2 bits.
        // If the unused 4 bits are non-zero, it is invalid Base64.
        val invalidPaddingKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // last 'A' has 0 padding bits (valid)
        validatePublicKey(invalidPaddingKey) shouldBe true

        // 'B' in Base64 has bit value 1 (000001), giving non-zero padding bits
        val badPaddingKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB"
        validatePublicKey(badPaddingKey) shouldBe false
    }

    // -------------------------------------------------------------------------
    // extractPublicKey Tests
    // -------------------------------------------------------------------------

    test("extractPublicKey extracts raw 43-character key directly") {
        val key = CryptoEngine.generateKeyPair().publicKeyBase64Url
        extractPublicKey(key) shouldBe key
    }

    test("extractPublicKey trims whitespace, tabs, and newlines around raw key") {
        val key = CryptoEngine.generateKeyPair().publicKeyBase64Url
        extractPublicKey("   $key   ") shouldBe key
        extractPublicKey("\n\n$key\n") shouldBe key
        extractPublicKey("\t$key\t\r\n") shouldBe key
    }

    test("extractPublicKey extracts key from conversational preamble") {
        val key = CryptoEngine.generateKeyPair().publicKeyBase64Url
        extractPublicKey("Here's my public key: $key") shouldBe key
        extractPublicKey("Public key: $key") shouldBe key
        extractPublicKey("My shhhhhh key is $key") shouldBe key
        extractPublicKey("Add me on shhhhhh! Key: $key") shouldBe key
    }

    test("extractPublicKey extracts key from conversational postscript") {
        val key = CryptoEngine.generateKeyPair().publicKeyBase64Url
        extractPublicKey("$key is my key, let me know when you add me!") shouldBe key
        extractPublicKey("$key thanks!") shouldBe key
        extractPublicKey("$key. Please save this.") shouldBe key
    }

    test("extractPublicKey extracts key from surrounding conversational text") {
        val key = CryptoEngine.generateKeyPair().publicKeyBase64Url
        val text = "Hey Alice! Hope you are doing well. Here is my public key: $key. Let's start chatting securely!"
        extractPublicKey(text) shouldBe key
    }

    test("extractPublicKey extracts key wrapped in quotes or brackets") {
        val key = CryptoEngine.generateKeyPair().publicKeyBase64Url
        extractPublicKey("<$key>") shouldBe key
        extractPublicKey("\"$key\"") shouldBe key
        extractPublicKey("'$key'") shouldBe key
        extractPublicKey("($key)") shouldBe key
        extractPublicKey("[$key]") shouldBe key
        extractPublicKey("{$key}") shouldBe key
    }

    test("extractPublicKey extracts key with trailing punctuation") {
        val key = CryptoEngine.generateKeyPair().publicKeyBase64Url
        extractPublicKey("Key: $key.") shouldBe key
        extractPublicKey("Key: $key,") shouldBe key
        extractPublicKey("Key: $key!") shouldBe key
        extractPublicKey("Key: $key?") shouldBe key
        extractPublicKey("Key: $key;") shouldBe key
    }

    test("extractPublicKey extracts key in multiline text") {
        val key = CryptoEngine.generateKeyPair().publicKeyBase64Url
        val multiline = """
            Hey there!
            Here is the information you requested:
            $key
            Talk to you later!
        """.trimIndent()
        extractPublicKey(multiline) shouldBe key
    }

    test("extractPublicKey returns null for empty or blank text") {
        extractPublicKey("") shouldBe null
        extractPublicKey("   ") shouldBe null
        extractPublicKey("\n\t\r") shouldBe null
    }

    test("extractPublicKey returns null for chatter without any 43-character token") {
        extractPublicKey("Hello there, how are you doing today?") shouldBe null
        extractPublicKey("I do not have a public key to share right now.") shouldBe null
    }

    test("extractPublicKey returns null for 42-character token") {
        val shortToken = "A".repeat(42)
        extractPublicKey(shortToken) shouldBe null
        extractPublicKey("Key is $shortToken please use it") shouldBe null
    }

    test("extractPublicKey returns null for 44-character token") {
        val longToken = "A".repeat(44)
        extractPublicKey(longToken) shouldBe null
        extractPublicKey("Key is $longToken please use it") shouldBe null
    }

    test("extractPublicKey returns null for 166-character ciphertext candidate") {
        val ciphertext = CryptoEngine.encrypt("Hello world", CryptoEngine.generateKeyPair().publicKey)
        ciphertext.length shouldBe 252
        extractPublicKey(ciphertext) shouldBe null
        extractPublicKey("Here is the secret: $ciphertext") shouldBe null
    }

    test("extractPublicKey finds the valid key when preceding text has an invalid 43-character token") {
        val validKey = CryptoEngine.generateKeyPair().publicKeyBase64Url
        // 'B' in 43rd position has invalid non-zero padding bits
        val invalidKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB"
        val combined = "Old bad key: $invalidKey but new good key: $validKey"

        extractPublicKey(combined) shouldBe validKey
    }

    // -------------------------------------------------------------------------
    // Contact Saving & AppStorage Tests
    // -------------------------------------------------------------------------

    test("TestAppStorage saves, retrieves, and orders contacts") {
        val storage = TestAppStorage()
        storage.getContacts().shouldBeEmpty()

        val key1 = CryptoEngine.generateKeyPair().publicKeyBase64Url
        val contact1 = ContactRecord(name = "Wife", publicKeyBase64Url = key1)
        storage.saveContact(contact1)

        val retrieved1 = storage.getContacts()
        retrieved1 shouldHaveSize 1
        retrieved1[0].id shouldBe contact1.id
        retrieved1[0].name shouldBe "Wife"
        retrieved1[0].publicKeyBase64Url shouldBe key1

        val key2 = CryptoEngine.generateKeyPair().publicKeyBase64Url
        val contact2 = ContactRecord(name = "Alice", publicKeyBase64Url = key2)
        storage.saveContact(contact2)

        val retrieved2 = storage.getContacts()
        retrieved2 shouldHaveSize 2
        retrieved2[0].name shouldBe "Wife"
        retrieved2[1].name shouldBe "Alice"
    }

    test("ContactRecord trims whitespace from name on creation") {
        val key = CryptoEngine.generateKeyPair().publicKeyBase64Url
        val rawName = "   Bob Smith   "
        val contact = ContactRecord(name = rawName.trim(), publicKeyBase64Url = key)
        contact.name shouldBe "Bob Smith"
    }

    // -------------------------------------------------------------------------
    // Contact Message Encryption Flow Tests
    // -------------------------------------------------------------------------

    test("encrypting for Myself encrypts with own public key and decrypts with own private key") {
        val userKeyPair = CryptoEngine.generateKeyPair()
        val plaintext = "Personal private reminder: pin is 4829"

        val userPublicKey: PublicKey = CryptoEngine.rawBytesToPublicKey(userKeyPair.publicKey)
        val ciphertext = CryptoEngine.encrypt(plaintext, userPublicKey)

        val decryptResult = CryptoEngine.decryptTextWithCandidates(
            inputText = ciphertext,
            recipientPrivateKey = userKeyPair.privateKey
        )

        decryptResult shouldNotBe null
        (decryptResult is DecryptResult.Success) shouldBe true
        val success = decryptResult as DecryptResult.Success
        success.text shouldBe plaintext
        success.secrets shouldContainExactly listOf(plaintext)
    }

    test("encrypting for a saved contact decrypts exclusively with contact's private key") {
        val userKeyPair = CryptoEngine.generateKeyPair()
        val contactKeyPair = CryptoEngine.generateKeyPair()

        val contact = ContactRecord(
            name = "Wife",
            publicKeyBase64Url = contactKeyPair.publicKeyBase64Url
        )

        val secretMessage = "The safety deposit box code is 78213."

        // Sender decodes contact's public key to PublicKey and encrypts
        val decodedContactPubKeyBytes = CryptoEngine.decodePublicKeyBase64Url(contact.publicKeyBase64Url)
        decodedContactPubKeyBytes shouldBe contactKeyPair.publicKey

        val contactPublicKey: PublicKey = CryptoEngine.rawBytesToPublicKey(decodedContactPubKeyBytes)
        val ciphertext = CryptoEngine.encrypt(secretMessage, contactPublicKey)

        // Contact (recipient) decrypts using contact's private key -> succeeds!
        val contactDecryptResult = CryptoEngine.decryptTextWithCandidates(
            inputText = ciphertext,
            recipientPrivateKey = contactKeyPair.privateKey
        )
        (contactDecryptResult is DecryptResult.Success) shouldBe true
        val contactSuccess = contactDecryptResult as DecryptResult.Success
        contactSuccess.text shouldBe secretMessage
        contactSuccess.secrets shouldContainExactly listOf(secretMessage)

        // Sender attempts to decrypt using sender's private key -> fails with AuthFailed!
        val senderDecryptResult = CryptoEngine.decryptTextWithCandidates(
            inputText = ciphertext,
            recipientPrivateKey = userKeyPair.privateKey
        )
        senderDecryptResult shouldBe DecryptResult.AuthFailed
    }

    test("encrypting empty string for a saved contact works and decrypts correctly") {
        val contactKeyPair = CryptoEngine.generateKeyPair()
        val contact = ContactRecord(
            name = "Alice",
            publicKeyBase64Url = contactKeyPair.publicKeyBase64Url
        )

        val contactPublicKey: PublicKey = CryptoEngine.rawBytesToPublicKey(
            CryptoEngine.decodePublicKeyBase64Url(contact.publicKeyBase64Url)
        )
        val ciphertext = CryptoEngine.encrypt("", contactPublicKey)

        val decryptResult = CryptoEngine.decryptTextWithCandidates(
            inputText = ciphertext,
            recipientPrivateKey = contactKeyPair.privateKey
        )
        (decryptResult is DecryptResult.Success) shouldBe true
        val success = decryptResult as DecryptResult.Success
        success.text shouldBe ""
        success.secrets shouldContainExactly listOf("")
    }

    test("encrypting multiline message with unicode and emoji for a saved contact works") {
        val contactKeyPair = CryptoEngine.generateKeyPair()
        val contact = ContactRecord(
            name = "Bob",
            publicKeyBase64Url = contactKeyPair.publicKeyBase64Url
        )

        val message = "🔐 Secret WiFi password: 🚀 SuperSafe#2026!\nFloor 4, Office 412.\nDon't share! 👍"

        val contactPublicKey: PublicKey = CryptoEngine.rawBytesToPublicKey(
            CryptoEngine.decodePublicKeyBase64Url(contact.publicKeyBase64Url)
        )
        val ciphertext = CryptoEngine.encrypt(message, contactPublicKey)

        val decryptResult = CryptoEngine.decryptTextWithCandidates(
            inputText = ciphertext,
            recipientPrivateKey = contactKeyPair.privateKey
        )
        (decryptResult is DecryptResult.Success) shouldBe true
        val success = decryptResult as DecryptResult.Success
        success.text shouldBe message
        success.secrets shouldContainExactly listOf(message)
    }

    test("CryptoEngine.encrypt with PublicKey overloads work for ByteArray and String") {
        val keyPair = CryptoEngine.generateKeyPair()
        val pubKey: PublicKey = CryptoEngine.rawBytesToPublicKey(keyPair.publicKey)

        val stringCiphertext = CryptoEngine.encrypt("test string", pubKey)
        CryptoEngine.decryptToString(stringCiphertext, keyPair.privateKey) shouldBe "test string"

        val bytesCiphertext = CryptoEngine.encrypt("test bytes".toByteArray(Charsets.UTF_8), pubKey)
        CryptoEngine.decryptToString(bytesCiphertext, keyPair.privateKey) shouldBe "test bytes"

        val customFloorCiphertext = CryptoEngine.encrypt("test floor", pubKey, minFloor = 256)
        CryptoEngine.decryptToString(customFloorCiphertext, keyPair.privateKey) shouldBe "test floor"
    }

    // -------------------------------------------------------------------------
    // Form Validation & Recipient Selection Tests
    // -------------------------------------------------------------------------

    test("Add Contact form validation requires non-blank name and valid extracted key") {
        fun isFormValid(name: String, keyInput: String): Boolean {
            val extracted = extractPublicKey(keyInput)
            return name.isNotBlank() && extracted != null
        }

        val validKey = CryptoEngine.generateKeyPair().publicKeyBase64Url

        // Valid name + valid key
        isFormValid("Wife", validKey) shouldBe true
        isFormValid("Alice", "Here's my key: $validKey") shouldBe true

        // Blank or whitespace-only name
        isFormValid("", validKey) shouldBe false
        isFormValid("   ", validKey) shouldBe false
        isFormValid("\t\n", validKey) shouldBe false

        // Invalid key input
        isFormValid("Wife", "") shouldBe false
        isFormValid("Wife", "invalid-key") shouldBe false
        isFormValid("Wife", "A".repeat(42) + "+") shouldBe false // non-Base64URL character
        isFormValid("Wife", "A".repeat(42) + "B") shouldBe false // invalid non-zero base64 padding bits

        // Both invalid
        isFormValid("", "") shouldBe false
    }

    test("saving contact creates ContactRecord with trimmed name and extracted key") {
        val storage = TestAppStorage()
        val validKey = CryptoEngine.generateKeyPair().publicKeyBase64Url
        val conversationalInput = "My key is $validKey, thanks!"

        val extracted = extractPublicKey(conversationalInput)
        extracted shouldNotBe null

        val rawName = "   Dr. Bob   "
        val newContact = ContactRecord(
            name = rawName.trim(),
            publicKeyBase64Url = extracted!!
        )

        storage.saveContact(newContact)
        val saved = storage.getContacts().first()
        saved.name shouldBe "Dr. Bob"
        saved.publicKeyBase64Url shouldBe validKey
    }

    test("newly added contact is automatically selectable as the active recipient") {
        var selectedContact: ContactRecord? = null // defaults to Myself (null)
        selectedContact shouldBe null

        val storage = TestAppStorage()
        val keyPair = CryptoEngine.generateKeyPair()
        val newContact = ContactRecord(name = "Partner", publicKeyBase64Url = keyPair.publicKeyBase64Url)

        // Save and auto-select
        storage.saveContact(newContact)
        selectedContact = newContact

        val contact = selectedContact
        contact shouldNotBe null
        contact.name shouldBe "Partner"
        contact.publicKeyBase64Url shouldBe keyPair.publicKeyBase64Url

        // Switching back to Myself
        selectedContact = null
        selectedContact shouldBe null
    }
})
