# Agent Guidelines (`AGENTS.md`)

This document provides instructions and conventions for autonomous AI agents working in this
repository (`shhhhhh`).

---

## Git Commit Philosophy & Format

### Frequency & Philosophy

- **Commit frequently and liberally**: Do not wait for an entire task to be complete before creating
  a commit. High-frequency commits ensure a fine-grained, traceable git history and maximize coding
  velocity.
- **Do not block intermediate commits**: Intermediate commits do **not** need to have code
  formatted, tests passing, or even the code successfully compiling.
- **Follow-up checkpoints**: Reserve running tests, fixing broken builds, and formatting code for
  appropriate checkpoints during the task (e.g., dedicated follow-up commits to fix tests or apply
  formatting).

### Commit Message Format

For the specific commit message format rules (subject line structure `<filename>: <brief summary>`
and rationale body guidelines), see
[.agents/rules/git_commit_format.md](.agents/rules/git_commit_format.md).

---

## Git Worktrees

- **Default to the Current Workspace**: Agents should work directly in the current workspace by
  default.
- **Requested Worktrees**: Only create or switch to a worktree under `worktrees/` (e.g.
  `worktrees/<branch-name>`) when explicitly instructed by the user or when coordinating parallel,
  concurrent tasks.
- **Fast-Forward Merging & Linear History**: When merging back into the target branch from git
  worktrees, the merge **MUST** be a fast-forward-only merge (`git merge --ff-only`), in order to
  maintain a linear git commit history.
- **Rebase Retry Strategy**:
  - If a fast-forward merge cannot be done, the worktree branch **MUST** be rebased on top of the
    target branch, then try the fast-forward merge again.
  - Try up to 4 times in total; give up and abort if the 4th attempt fails.
  - Wait a random number of milliseconds between 100 and 500 (`100ms`–`500ms`) before re-attempting
    the merge to coordinate with other AI agents competing for the branch.

---

## Resource Locking

When interacting with shared hardware or software resources (such as simulators or devices), agents
must coordinate using the mutual-exclusion skill located at
`.agents/skills/resource-lock/scripts/lock.py`.

### Lock Directory

- Agents **MUST** specify `.agents/resource-locks` as the lock directory.

### Mandatory Resource Names

Agents **MUST** use the following exact lock names when coordinating access to these specific
resource types:

- `android-device`: Physical Android device attached via USB/ADB.
- `android-emulator`: Android Virtual Device (AVD) / emulator instance.
- `ios-device`: Physical iOS device connected via USB/Xcode.
- `ios-emulator`: iOS Simulator instance.

> [!NOTE] Other resource names may be used for other resources, especially if instructed to use an
> explicit resource name.

### Granularity & Usage

Agents have full discretion over whether to lock per command or maintain an active lease across a
multi-step session.

- **Status Inspection Example**:
  ```bash
  python3 .agents/skills/resource-lock/scripts/lock.py status --lock-dir .agents/resource-locks
  ```

---

## Building and Running

Common build, test, and lifecycle commands are encapsulated in scripts located in the `scripts/`
directory.

### Android

- **Compile Sources**:

  ```bash
  scripts/android_compile.zsh
  ```

  > [!TIP] Use `scripts/android_compile.zsh` for quick compilation checks instead of building full
  > APK artifacts.

- **Run Unit Tests**:

  ```bash
  scripts/android_unit_tests.zsh
  ```

- **Install Debug Build to Device/Emulator**:

  ```bash
  scripts/android_install.zsh
  ```

- **Launch Main Activity via ADB**:

  ```bash
  scripts/android_launch.zsh
  ```

- **Run Instrumentation Tests**:

  ```bash
  scripts/android_instrumentation_tests.zsh
  ```

- **Clear App Data / State**:
  ```bash
  scripts/android_clear.zsh
  ```

### iOS

- **Compile Project & Tests**:

  ```bash
  scripts/ios_compile.zsh
  ```

- **Install Debug Build to Simulator**:

  ```bash
  scripts/ios_install.zsh
  ```

- **Run Unit Tests**:

  ```bash
  scripts/ios_unit_tests.zsh
  ```

  > [!TIP] Running unit tests alone with `scripts/ios_unit_tests.zsh` is an order of magnitude
  > faster than running the full test suite or UI tests.

- **Run UI / Integration Tests**:
  ```bash
  scripts/ios_ui_tests.zsh
  ```

---

## Code Formatting

Code formatting **MUST** be done before declaring a task to be "complete".

Note that formatting is **NOT** required in order to commit to git, so agents are encouraged to, if
they deem it would materially reduce the overall time to complete the task, save code formatting to
the end and commit it in a single commit.

### Formatters

- **JSON, Markdown, TOML, TypeScript, and YAML** (`.json`, `.md`, `.toml`, `.ts`, `.yaml`, `.yml`):

  ```bash
  scripts/prettier.zsh
  ```

- **Kotlin and Kotlin Gradle Scripts** (`.kt`, `.kts`):

  ```bash
  scripts/spotless.zsh
  ```

- **Swift** (`.swift`):
  ```bash
  scripts/swift_format.zsh
  ```
