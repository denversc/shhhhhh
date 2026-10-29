# Agent Guidelines (`AGENTS.md`)

This document provides instructions and conventions for autonomous AI agents working in this repository (`shhhhhh`).

---

## 1. Git Commit Philosophy & Format

### Frequency & Philosophy
* **Commit frequently and liberally**: Do not wait for an entire task to be complete before creating a commit. High-frequency commits ensure a fine-grained, traceable git history and maximize coding velocity.
* **Do not block intermediate commits**: Intermediate commits do **not** need to have code formatted, tests passing, or even the code successfully compiling.
* **Follow-up checkpoints**: Reserve running tests, fixing broken builds, and formatting code for appropriate checkpoints during the task (e.g., dedicated follow-up commits to fix tests or apply formatting).

### Commit Message Format
For the specific commit message format rules (subject line structure `<filename>: <brief summary>` and rationale body guidelines), see [.agents/rules/git_commit_format.md](.agents/rules/git_commit_format.md).

---

## 2. Git Worktrees

* **Default to the Current Workspace**: Agents should work directly in the current workspace by default.
* **Requested Worktrees**: Only create or switch to a worktree under `worktrees/` (e.g. `worktrees/<branch-name>`) when explicitly instructed by the user or when coordinating parallel, concurrent tasks.
* **Fast-Forward Merging & Linear History**: When merging back into the target branch from git worktrees, the merge **MUST** be a fast-forward-only merge (`git merge --ff-only`), in order to maintain a linear git commit history.
* **Rebase Retry Strategy**:
  * If a fast-forward merge cannot be done, the worktree branch **MUST** be rebased on top of the target branch, then try the fast-forward merge again.
  * Try up to 4 times in total; give up and abort if the 4th attempt fails.
  * Wait a random number of milliseconds between 100 and 500 (`100ms`–`500ms`) before re-attempting the merge to coordinate with other AI agents competing for the branch.

---

## 3. Resource Locking

When interacting with shared hardware or software resources (such as simulators or devices), agents must coordinate using the mutual-exclusion skill located at `.agents/skills/resource-lock/scripts/lock.py`.

### Lock Directory
* Agents **MUST** specify `.agents/resource-locks` as the lock directory.

### Mandatory Resource Names
Agents **MUST** use the following exact lock names when coordinating access to these specific resource types:
* `android-device`: Physical Android device attached via USB/ADB.
* `android-emulator`: Android Virtual Device (AVD) / emulator instance.
* `ios-device`: Physical iOS device connected via USB/Xcode.
* `ios-emulator`: iOS Simulator instance.

> [!NOTE]
> Other resource names may be used for other resources, especially if instructed to use an explicit resource name.

### Granularity & Usage
Agents have full discretion over whether to lock per command or maintain an active lease across a multi-step session.

* **Status Inspection Example**:
  ```bash
  python3 .agents/skills/resource-lock/scripts/lock.py status --lock-dir .agents/resource-locks
  ```

---

## 4. Building and Running

### Android (`android/`)
* **Compile Sources**:
  ```bash
  ./android/gradlew -p android compileDebugSources
  ```
  > [!TIP]
  > Recommend `compileDebugSources` over `assembleDebug` because `compileDebugSources` is much faster. Only use `assembleDebug` if the APK artifact is needed or testing an aspect of APK generation is required.

* **Assemble Debug APK**:
  ```bash
  ./android/gradlew -p android assembleDebug
  ```

* **Run Unit Tests**:
  ```bash
  ./android/gradlew -p android testDebugUnitTest
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
* **Build Project**:
  ```bash
  xcodebuild -project ios/shhhhhh/shhhhhh.xcodeproj -scheme shhhhhh -destination "<DESTINATION>" build
  ```
* **Run Unit Tests**:
  ```bash
  xcodebuild -project ios/shhhhhh/shhhhhh.xcodeproj -scheme shhhhhh -destination "<DESTINATION>" -only-testing:shhhhhhTests test
  ```
  > [!TIP]
  > Running unit tests alone with `-only-testing:shhhhhhTests` is an order of magnitude faster than running the full test suite or UI tests.

* **Run UI / Integration Tests**:
  ```bash
  xcodebuild -project ios/shhhhhh/shhhhhh.xcodeproj -scheme shhhhhh -destination "<DESTINATION>" -only-testing:shhhhhhUITests test
  ```
