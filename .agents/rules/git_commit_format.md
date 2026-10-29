---
trigger: manual
description: "Git commit message format and conventions for AI agents"
---

# Git Commit Message Format

This rule defines the mandatory structure and conventions for git commit messages created by AI
agents in this repository.

## 1. Subject Line

- Must begin with the base name of the changed file or the **most important** file affected by the
  commit, followed by a colon, a space, and a brief summary.
- Format: `<filename>: <brief summary>`
- **Determining the most important file**: If `AppStorage.kt` is modified alongside its unit tests
  in `AppStorageTest.kt`, `AppStorage.kt` is generally the most important file. The exception is
  when the entire purpose of the commit was to modify `AppStorageTest.kt` and changes to
  `AppStorage.kt` were ancillary.
- Examples:
  - `MainActivity.kt: add splash screen exit animation`
  - `ContentView.swift: add Paste button with spread utility bar alignment`
  - `themes.xml: configure seamless background and system bars`

## 2. Rationale Body

- If a thought process or non-obvious reasoning went into the change, add **1 or 2 paragraphs**
  below the summary line explaining the rationale.
- This rationale is especially critical when the reason for the change is not self-evident.
- _Example_: If a commit fixes a test failure or compiler error, include the relevant excerpt of the
  failure message.
