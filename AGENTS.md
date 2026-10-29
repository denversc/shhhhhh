# Agent Guidelines (`AGENTS.md`)

This document provides instructions and conventions for autonomous AI agents working in this repository (`shhhhhh`).

---

## 1. Git Commit Philosophy & Message Format

### Frequency & Philosophy
* **Commit frequently and liberally**: Do not wait for an entire task to be complete before creating a commit. High-frequency commits ensure a fine-grained, traceable git history and maximize coding velocity.
* **Do not block intermediate commits**: Intermediate commits do **not** need to have code formatted, tests passing, or even the code successfully compiling.
* **Follow-up checkpoints**: Reserve running tests, fixing broken builds, and formatting code for appropriate checkpoints during the task (e.g., dedicated follow-up commits to fix tests or apply formatting).

### Commit Message Format
All commits **must** adhere strictly to the following structure:

1. **Subject Line**:
   * Must begin with the base name of the changed file or the **most important** file affected by the commit, followed by a colon, a space, and a brief summary.
   * Format: `<filename>: <brief summary>`
   * *Determining the most important file*: If `AppStorage.kt` is modified alongside its unit tests in `AppStorageTest.kt`, `AppStorage.kt` is generally the most important file. The exception is when the entire purpose of the commit was to modify `AppStorageTest.kt` and changes to `AppStorage.kt` were ancillary.
   * Examples:
     * `MainActivity.kt: add splash screen exit animation`
     * `ContentView.swift: add Paste button with spread utility bar alignment`
     * `themes.xml: configure seamless background and system bars`

2. **Rationale Body**:
   * If a thought process or non-obvious reasoning went into the change, add **1 or 2 paragraphs** below the summary line explaining the rationale.
   * This rationale is especially critical when the reason for the change is not self-evident.
   * *Example*: If a commit fixes a test failure or compiler error, include the relevant excerpt of the failure message.

3. **Mandatory Commit Flags**:
   * You **MUST** specify `--no-gpg-sign` with every `git commit` invocation to avoid signing errors in automated environments:
     ```bash
     git commit --no-gpg-sign -m "<filename>: <brief summary>"
     ```

---

## 2. Git Worktrees

* **Default to the Current Workspace**: Agents should work directly in the current workspace by default.
* **Requested Worktrees**: Only create or switch to a worktree under `worktrees/` (e.g. `worktrees/<branch-name>`) when explicitly instructed by the user or when coordinating parallel, concurrent tasks.
* **Existing Helper Scripts**:
  * `worktrees/al.sh`: Builds and launches the Android app onto a connected device/emulator.
  * `worktrees/il.sh`: Builds and launches the iOS app onto a simulator.

---

## 3. Resource Locking

When interacting with shared hardware or software resources (such as simulators or devices), agents must coordinate using the mutual-exclusion skill located at `.agents/skills/resource-lock/scripts/lock.py`.

### Lock Directory
* Agents **MUST** specify `.agents/resource-locks` as the lock directory (which is also the default configured in `lock.py`).
* All lock files within `.agents/resource-locks` are ignored by git in `.gitignore`.

### Mandatory Resource Names
Agents **MUST** use one of the following exact lock names when coordinating access to these resource types:
* `android-device`: Physical Android device attached via USB/ADB.
* `android-emulator`: Android Virtual Device (AVD) / emulator instance.
* `ios-device`: Physical iOS device connected via USB/Xcode.
* `ios-emulator`: iOS Simulator instance.

### Granularity & Usage
Agents have full discretion over whether to lock per command or maintain an active lease across a multi-step session:

* **Single-Command Execution** (`run`):
  Acquires the lock, runs the command, and releases the lock immediately upon completion or error:
  ```bash
  python3 .agents/skills/resource-lock/scripts/lock.py run \
    --lock-dir .agents/resource-locks \
    -c "<CONVERSATION_ID>" \
    -r ios-emulator \
    -d "Run iOS unit tests" \
    -- xcodebuild ... test
  ```

* **Session Leases** (`acquire` / `release`):
  Acquires a time-limited background lease (default 300s) across multiple conversational steps:
  ```bash
  # Acquire lease
  python3 .agents/skills/resource-lock/scripts/lock.py acquire \
    --lock-dir .agents/resource-locks \
    -c "<CONVERSATION_ID>" \
    -r android-emulator \
    -d "Manual debugging session"

  # Release lease when finished
  python3 .agents/skills/resource-lock/scripts/lock.py release \
    --lock-dir .agents/resource-locks \
    -c "<CONVERSATION_ID>" \
    -r android-emulator
  ```

* **Status Inspection** (`status`):
  ```bash
  python3 .agents/skills/resource-lock/scripts/lock.py status --lock-dir .agents/resource-locks
  ```

---

## 4. Building and Running

### Android (`android/`)
* **Run Unit Tests**:
  ```bash
  ./android/gradlew -p android test
  ```
* **Assemble Debug APK**:
  ```bash
  ./android/gradlew -p android assembleDebug
  ```
* **Install Debug Build to Device/Emulator**:
  ```bash
  ./android/gradlew -p android app:installDebug
  ```
* **Launch Main Activity via ADB**:
  ```bash
  adb shell am start -n prefix924.shhhhhh/.MainActivity
  ```

### iOS (`ios/`)
* **Project**: `ios/shhhhhh/shhhhhh.xcodeproj`
* **Scheme**: `shhhhhh`
* **Destination Selection**: Destination selection is left to agent discretion based on task requirements (e.g. `platform=iOS Simulator,id=booted`, `platform=iOS Simulator,name=iPhone 18 Pro`, or a physical device destination).
* **Build Project**:
  ```bash
  xcodebuild -project ios/shhhhhh/shhhhhh.xcodeproj -scheme shhhhhh -destination "<DESTINATION>" build
  ```
* **Run Tests**:
  ```bash
  xcodebuild -project ios/shhhhhh/shhhhhh.xcodeproj -scheme shhhhhh -destination "<DESTINATION>" test
  ```
* **Install and Launch on Simulator**:
  ```bash
  xcrun simctl install booted <path-to-app>
  xcrun simctl launch booted prefix924.shhhhhh
  ```
