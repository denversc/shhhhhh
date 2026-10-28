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
    func testOnboardingUIElementsPresent() throws {
        let app = XCUIApplication()
        app.launch()

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
        let app = XCUIApplication()
        app.launch()

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
        let app = XCUIApplication()
        app.launch()

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
        let app = XCUIApplication()
        app.launch()

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
}
