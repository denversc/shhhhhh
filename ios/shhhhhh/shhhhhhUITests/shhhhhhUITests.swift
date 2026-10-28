//
//  shhhhhhUITests.swift
//  shhhhhhUITests
//
//  Created by Denver Coneybeare on 2026-10-27.
//

import XCTest

final class shhhhhhUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    private func launchApp(resetStorage: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        if resetStorage {
            app.launchArguments = ["-reset-storage"]
        }
        app.launch()
        return app
    }

    @MainActor
    func testOnboardingUIElementsPresent() throws {
        let app = launchApp()

        XCTAssertTrue(app.staticTexts["shhhhhh"].exists)
        XCTAssertTrue(app.staticTexts["Welcome to shhhhhh"].exists)
        XCTAssertTrue(app.staticTexts["Private end-to-end encryption for your text messages. To initialize your key, scribble or tap randomly in the box:"].exists)
        XCTAssertTrue(app.staticTexts["Scribble or tap randomly here"].exists)
        XCTAssertTrue(app.staticTexts["Entropy Collection"].exists)
        XCTAssertTrue(app.staticTexts["0%"].exists)

        let actionButton = app.buttons["keygen_action_button"]
        XCTAssertTrue(actionButton.exists)
        XCTAssertFalse(actionButton.isEnabled)
        XCTAssertTrue(actionButton.label.contains("Scribble or Tap to Collect Entropy"))

        let restoreButton = app.buttons["restore_backup_button"]
        XCTAssertTrue(restoreButton.exists)
        XCTAssertTrue(restoreButton.isEnabled)
    }

    @MainActor
    func testRestoreFromBackupValidationAndFlow() throws {
        let app = launchApp()

        let restoreButton = app.buttons["restore_backup_button"]
        XCTAssertTrue(restoreButton.waitForExistence(timeout: 5.0))
        restoreButton.tap()

        // Verify sheet elements
        let restoreSheetTitle = app.staticTexts["Restore from Backup"]
        XCTAssertTrue(restoreSheetTitle.waitForExistence(timeout: 5.0))

        let confirmRestoreButton = app.buttons["confirm_restore_button"]
        XCTAssertTrue(confirmRestoreButton.exists)

        // Attempt restore with empty input -> validation error
        confirmRestoreButton.tap()
        let errorText = app.staticTexts["restore_error_text"]
        XCTAssertTrue(errorText.waitForExistence(timeout: 3.0))
        XCTAssertEqual(errorText.label, "Please paste a backup bundle before restoring.")

        // Enter backup text into the editor
        let textEditor = app.textViews["restore_text_editor"]
        XCTAssertTrue(textEditor.exists)
        textEditor.tap()
        textEditor.typeText("sample-paper-backup-string-x25519-identity-payload")

        // Confirm restore
        confirmRestoreButton.tap()

        // Verify transition to main screen
        let recipientLabel = app.staticTexts["Recipient"]
        XCTAssertTrue(recipientLabel.waitForExistence(timeout: 5.0))
        XCTAssertTrue(app.buttons["Encrypt"].exists)
        XCTAssertTrue(app.buttons["Decrypt"].exists)

        // Verify live Encrypt and Decrypt flow
        let mainEditor = app.textViews["main_text_editor"]
        XCTAssertTrue(mainEditor.waitForExistence(timeout: 5.0))
        mainEditor.tap()
        mainEditor.typeText("SecretMeetingAtNoon")

        let encryptButton = app.buttons["Encrypt"]
        encryptButton.tap()

        let copyCiphertextButton = app.buttons["Copy Ciphertext"]
        XCTAssertTrue(copyCiphertextButton.waitForExistence(timeout: 5.0))

        let decryptButton = app.buttons["Decrypt"]
        decryptButton.tap()

        let copySecretButton = app.buttons["Copy Secret"]
        XCTAssertTrue(copySecretButton.waitForExistence(timeout: 5.0))

        // 1. Single-secret 1-tap copy
        copySecretButton.tap()
        let copiedSecretBanner = app.staticTexts["Copied secret: SecretMeetingAtNoon"]
        XCTAssertTrue(copiedSecretBanner.waitForExistence(timeout: 3.0))

        // 2. Long-press [Copy Secret] copies full verbatim message
        copySecretButton.press(forDuration: 0.6)
        let copiedFullBanner = app.staticTexts["Copied full message"]
        XCTAssertTrue(copiedFullBanner.waitForExistence(timeout: 3.0))

        // 3. Directly tapping secret span in text box copies secret
        let spanCoordinate = mainEditor.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.05))
        spanCoordinate.tap()
        XCTAssertTrue(copiedSecretBanner.waitForExistence(timeout: 3.0))

        // 4. Typing text clears secret spans and reverts button back to Decrypt
        mainEditor.tap()
        mainEditor.typeText(" editing")
        let revertedDecryptButton = app.buttons["Decrypt"]
        XCTAssertTrue(revertedDecryptButton.waitForExistence(timeout: 3.0))
    }

    @MainActor
    func testMultiSecretSelectionSheetFlow() throws {
        let app = launchApp()

        // Restore known deterministic backup key
        let restoreButton = app.buttons["restore_backup_button"]
        XCTAssertTrue(restoreButton.waitForExistence(timeout: 5.0))
        restoreButton.tap()

        let textEditor = app.textViews["restore_text_editor"]
        XCTAssertTrue(textEditor.waitForExistence(timeout: 5.0))
        textEditor.tap()
        textEditor.typeText("multi-secret-deterministic-test-key")

        let confirmRestoreButton = app.buttons["confirm_restore_button"]
        confirmRestoreButton.tap()

        let mainEditor = app.textViews["main_text_editor"]
        XCTAssertTrue(mainEditor.waitForExistence(timeout: 5.0))

        // Encrypt secret A
        mainEditor.tap()
        mainEditor.typeText("SecretAlpha")
        let encryptButton = app.buttons["Encrypt"]
        encryptButton.tap()
        let copyCiphertextButton = app.buttons["Copy Ciphertext"]
        XCTAssertTrue(copyCiphertextButton.waitForExistence(timeout: 5.0))
        guard let ctA = mainEditor.value as? String, !ctA.isEmpty else {
            XCTFail("Failed to retrieve ciphertext A")
            return
        }

        // Clear and encrypt secret B
        app.buttons["Clear"].tap()
        mainEditor.tap()
        mainEditor.typeText("SecretBeta")
        app.buttons["Encrypt"].tap()
        XCTAssertTrue(copyCiphertextButton.waitForExistence(timeout: 5.0))
        guard let ctB = mainEditor.value as? String, !ctB.isEmpty else {
            XCTFail("Failed to retrieve ciphertext B")
            return
        }

        // Clear and assemble message with both ciphertexts
        app.buttons["Clear"].tap()
        mainEditor.tap()
        mainEditor.typeText("One: " + ctA + " Two: " + ctB)

        // Decrypt
        app.buttons["Decrypt"].tap()

        let copySecretButton = app.buttons["Copy Secret"]
        XCTAssertTrue(copySecretButton.waitForExistence(timeout: 5.0))

        // Tapping [Copy Secret] with multiple secrets opens the picker sheet
        copySecretButton.tap()

        let sheetTitle = app.staticTexts["Select Secret to Copy"]
        XCTAssertTrue(sheetTitle.waitForExistence(timeout: 5.0))

        let firstItem = app.buttons["secret_picker_item_0"]
        XCTAssertTrue(firstItem.waitForExistence(timeout: 5.0))
        XCTAssertTrue(app.staticTexts["SecretAlpha"].exists)
        XCTAssertTrue(app.staticTexts["SecretBeta"].exists)

        // Select the second secret from the picker sheet
        let secondItem = app.buttons["secret_picker_item_1"]
        XCTAssertTrue(secondItem.exists)
        secondItem.tap()

        // Sheet is dismissed and feedback banner shows the selected secret
        let copiedBetaBanner = app.staticTexts["Copied secret: SecretBeta"]
        XCTAssertTrue(copiedBetaBanner.waitForExistence(timeout: 3.0))
        XCTAssertFalse(sheetTitle.exists)
    }

    @MainActor
    func testEntropyCollectionAndKeyGenerationFlow() throws {
        let app = launchApp()

        let canvas = app.otherElements["entropy_canvas"]
        XCTAssertTrue(canvas.waitForExistence(timeout: 5.0))

        let coordinateA = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.2))
        let coordinateB = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.8))
        let coordinateC = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.2))
        let coordinateD = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.8))

        // Multiple drags across different vectors until 100% is reached
        for _ in 0..<15 {
            coordinateA.press(forDuration: 0.05, thenDragTo: coordinateB)
            coordinateC.press(forDuration: 0.05, thenDragTo: coordinateD)
            coordinateB.press(forDuration: 0.05, thenDragTo: coordinateA)
            coordinateD.press(forDuration: 0.05, thenDragTo: coordinateC)

            let actionButton = app.buttons["keygen_action_button"]
            if actionButton.label == "Get Started" {
                break
            }
        }

        let actionButton = app.buttons["keygen_action_button"]
        let predicate = NSPredicate(format: "label == 'Get Started' AND isEnabled == true")
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: actionButton)
        let result = XCTWaiter.wait(for: [expectation], timeout: 10.0)
        XCTAssertEqual(result, .completed)

        actionButton.tap()

        let recipientLabel = app.staticTexts["Recipient"]
        XCTAssertTrue(recipientLabel.waitForExistence(timeout: 5.0))
        XCTAssertTrue(app.buttons["Encrypt"].exists)
        XCTAssertTrue(app.buttons["Decrypt"].exists)
    }

    @MainActor
    func testColdStartRestorationIntoMainScreen() throws {
        let app = launchApp(resetStorage: true)

        let restoreButton = app.buttons["restore_backup_button"]
        XCTAssertTrue(restoreButton.waitForExistence(timeout: 5.0))
        restoreButton.tap()

        let textEditor = app.textViews["restore_text_editor"]
        XCTAssertTrue(textEditor.waitForExistence(timeout: 5.0))
        textEditor.tap()
        textEditor.typeText("cold-start-persistence-test-key")

        let confirmRestoreButton = app.buttons["confirm_restore_button"]
        confirmRestoreButton.tap()

        let recipientLabel = app.staticTexts["Recipient"]
        XCTAssertTrue(recipientLabel.waitForExistence(timeout: 5.0))

        // Cold restart: terminate app and relaunch without -reset-storage
        app.terminate()
        let secondApp = launchApp(resetStorage: false)

        // Verify state is restored directly into MainScreen
        XCTAssertTrue(secondApp.staticTexts["Recipient"].waitForExistence(timeout: 5.0))
        XCTAssertTrue(secondApp.buttons["Encrypt"].exists)
        XCTAssertTrue(secondApp.buttons["Decrypt"].exists)
    }

    @MainActor
    func testCopyMyPublicKeyInContactSheet() throws {
        let app = launchApp(resetStorage: true)

        let restoreButton = app.buttons["restore_backup_button"]
        XCTAssertTrue(restoreButton.waitForExistence(timeout: 5.0))
        restoreButton.tap()

        let textEditor = app.textViews["restore_text_editor"]
        XCTAssertTrue(textEditor.waitForExistence(timeout: 5.0))
        textEditor.tap()
        textEditor.typeText("public-key-copy-test-key")

        let confirmRestoreButton = app.buttons["confirm_restore_button"]
        confirmRestoreButton.tap()

        // Tap Recipient widget to open Contact Selection sheet
        let recipientButton = app.buttons["recipient_selector_button"]
        XCTAssertTrue(recipientButton.waitForExistence(timeout: 5.0))
        recipientButton.tap()

        // Verify "MY PUBLIC KEY" header and text exists
        let myPubKeyHeader = app.staticTexts["MY PUBLIC KEY"]
        XCTAssertTrue(myPubKeyHeader.waitForExistence(timeout: 5.0))

        let pubKeyText = app.staticTexts["my_public_key_text"]
        XCTAssertTrue(pubKeyText.exists)
        XCTAssertEqual(pubKeyText.label.count, 43)

        // Verify "Copy My Public Key" button exists and tap it
        let copyButton = app.buttons["copy_my_public_key_button"]
        XCTAssertTrue(copyButton.exists)
        copyButton.tap()

        // Verify transient visual feedback banner appears
        let copiedBanner = app.staticTexts["Copied public key"]
        XCTAssertTrue(copiedBanner.waitForExistence(timeout: 3.0))

        // Verify close button dismisses sheet
        let closeButton = app.buttons["sheet_close_button"]
        XCTAssertTrue(closeButton.exists)
        closeButton.tap()

        XCTAssertFalse(myPubKeyHeader.exists)
    }

    @MainActor
    func testContactSelectionAndImportFlow() throws {
        let app = launchApp(resetStorage: true)

        // Restore known backup to enter main screen
        let restoreButton = app.buttons["restore_backup_button"]
        XCTAssertTrue(restoreButton.waitForExistence(timeout: 5.0))
        restoreButton.tap()

        let textEditor = app.textViews["restore_text_editor"]
        XCTAssertTrue(textEditor.waitForExistence(timeout: 5.0))
        textEditor.tap()
        textEditor.typeText("contact-import-flow-key")

        let confirmRestoreButton = app.buttons["confirm_restore_button"]
        confirmRestoreButton.tap()

        // Verify recipient button on main screen displays Myself (This Device)
        let recipientButton = app.buttons["recipient_selector_button"]
        XCTAssertTrue(recipientButton.waitForExistence(timeout: 5.0))
        XCTAssertTrue(app.staticTexts["Myself (This Device)"].exists)

        // 1. Open Contact Selection Sheet
        recipientButton.tap()

        let sheetTitle = app.staticTexts["Contacts & Settings"]
        XCTAssertTrue(sheetTitle.waitForExistence(timeout: 5.0))
        XCTAssertTrue(app.buttons["contact_item_myself"].exists)
        XCTAssertTrue(app.buttons["copy_my_public_key_button"].exists)
        XCTAssertTrue(app.staticTexts["my_public_key_text"].exists)

        // 2. Open Add Contact Modal
        let addNewContactButton = app.buttons["add_new_contact_button"]
        XCTAssertTrue(addNewContactButton.exists)
        addNewContactButton.tap()

        let addContactTitle = app.navigationBars["Add New Contact"]
        XCTAssertTrue(addContactTitle.waitForExistence(timeout: 5.0))

        let saveButton = app.buttons["add_contact_save_button"]
        XCTAssertTrue(saveButton.exists)
        XCTAssertFalse(saveButton.isEnabled)

        // 3. Enter contact name
        let nameField = app.textFields["add_contact_name_input"]
        XCTAssertTrue(nameField.exists)
        nameField.tap()
        nameField.typeText("Alice")

        // Save still disabled without key
        XCTAssertFalse(saveButton.isEnabled)

        // 4. Enter valid key embedded in conversational text (test smart extraction)
        let keyField = app.textViews["add_contact_key_input"].exists ? app.textViews["add_contact_key_input"] : app.textFields["add_contact_key_input"]
        XCTAssertTrue(keyField.exists)
        keyField.tap()
        keyField.typeText("Hey! Here is my public key: f78YxIRZu8I5DQCMDQ-WD4hZvYtObgQ7_0SsFgpnkEc Thanks!")

        // Verified smart extractor detected the key
        let feedback = app.descendants(matching: .any)["add_contact_feedback_text"]
        XCTAssertTrue(feedback.waitForExistence(timeout: 3.0))
        XCTAssertTrue(saveButton.isEnabled)

        // 5. Save contact and verify dismissal back to MainScreen
        saveButton.tap()

        let mainEditor = app.textViews["main_text_editor"]
        XCTAssertTrue(mainEditor.waitForExistence(timeout: 5.0))
        XCTAssertTrue(app.staticTexts["Alice"].waitForExistence(timeout: 5.0))

        // 6. Test message encryption to Alice
        mainEditor.tap()
        mainEditor.typeText("SecretPayloadForAlice")

        let encryptButton = app.buttons["Encrypt"]
        encryptButton.tap()

        let copyCiphertextButton = app.buttons["Copy Ciphertext"]
        XCTAssertTrue(copyCiphertextButton.waitForExistence(timeout: 5.0))

        // 7. Re-open sheet and switch back to Myself
        recipientButton.tap()
        XCTAssertTrue(sheetTitle.waitForExistence(timeout: 5.0))
        app.buttons["contact_item_myself"].tap()

        XCTAssertTrue(app.staticTexts["Myself (This Device)"].waitForExistence(timeout: 5.0))
    }
}
