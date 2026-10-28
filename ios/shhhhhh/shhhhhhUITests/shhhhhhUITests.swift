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
