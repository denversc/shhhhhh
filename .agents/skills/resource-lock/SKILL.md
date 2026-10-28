---
name: resource-lock
description: >-
  Acquire and manage exclusive mutual-exclusion locks on shared hardware or software
  resources (such as an iOS Simulator, attached Android device, port, or any arbitrary
  named resource like "foobar" or "zzyzx") across distinct Antigravity conversations.
  Use this skill whenever instructed to coordinate exclusive access to a resource, hold
  a lock (e.g., "hold the lock on the ios simulator when you require it"), or run commands
  safely without colliding with other agents.
compatibility: Requires Python 3.8+ with standard fcntl on macOS/Linux.
---

# Resource Lock

The `resource-lock` skill provides advisory mutual exclusion across concurrent Antigravity agent conversations running on the same host. It relies on POSIX kernel file locking (`fcntl.flock`), ensuring that crashed processes or aborted tool calls automatically release their locks immediately.

All locking operations operate on **arbitrary resource names** (e.g. `ios-simulator`, `android-device`, `foobar`, `zzyzx`) without needing domain-specific knowledge of what the resource is.

> [!NOTE]
> The locking script `scripts/lock.py` is **not executable**. You must always invoke it as an argument to the Python interpreter:
> ```bash
> python3 <path-to-skill>/scripts/lock.py <subcommand> ...
> ```

---

## Required & Optional Parameters

* **`--conversation-id` / `-c` (Required)**: An opaque identifier for your Antigravity conversation (found in conversation metadata, e.g. `a6dc63ae-bce3-4d54-8c26-391b4ca2495e`). Passed to `run`, `acquire`, and `release`.
* **`--description` / `-d` (Optional)**: An arbitrary human-friendly string describing the purpose of the lock being held (e.g., `"Running Xcode UI tests on iPhone 16"`).
* **`--force` / `-f` (Optional on `release`)**: Allows releasing an active lock even if the caller's conversation ID does not match the active holder's ID.

---

## Real-Time JSON Status Reporting on stdout

When a lock acquisition **waits** for a held resource or **fails** (times out), `lock.py` writes a structured JSON object to `stdout`:

```json
{
  "event": "waiting",
  "resource": "ios-simulator",
  "conversation_id": "conv-alpha",
  "holder_conversation_id": "conv-alpha",
  "acquired_ms_ago": 12450,
  "lease_duration_ms": 300000,
  "lease_ms": 300000,
  "description": "Running UI tests",
  "mode": "lease"
}
```

* **Event De-duplication**: When waiting for a contested lock, the JSON object is emitted to `stdout` **ONCE** when waiting begins, and re-emitted only if ownership transfers to a different conversation while waiting.
* **Failure Event**: If the wait timeout elapses without acquiring the lock, a final JSON object with `"event": "failed"` and `"reason"` is written to `stdout`.
* **Process-Scoped Commands (`run`)**: When the lock is held by a `run` command (rather than a timed lease), `lease_duration_ms` / `lease_ms` is set to `null`.

---

## Modes of Operation

This skill supports two complementary locking patterns:

1. **Automatic Command Wrapping (`run`)**: Recommended for single one-off commands (e.g., building, installing an app, taking a screenshot, running a test suite). The lock is held exclusively during execution and released immediately when the command terminates.
2. **Session Lease (`acquire` & `release`)**: Recommended for multi-step workflows where device state must be preserved across separate conversational turns. Spawns a background holder process with an automatic lease timeout to prevent deadlocks.

---

## When the User Asks to "Hold the Lock"

When the user says something like:
> *"hold the lock on the ios simulator when you require it"*
> *"hold the ai lock on <resource> when you need it"*

Follow these behavioral guidelines:

1. **Obtain Conversation ID**: Find your active Conversation ID from your system prompt/metadata.
2. **Identify the Resource Name**: Extract the resource name (e.g., `ios-simulator`, `android-device`, `foobar`). Resource names are case-insensitive and whitespace/underscores are automatically normalized to hyphens.
3. **Choose the Granularity**:
   - If performing **a single command or self-contained test script**: Use `run` with `--conversation-id` to wrap the command.
   - If performing a **multi-turn exploratory or interactive procedure**: Call `acquire` with `--conversation-id` before the first command touching the resource, perform your actions across turns, and call `release` with your `--conversation-id` as soon as your task concludes.
4. **Handle Contested Resources**: By default, commands wait up to 60 seconds (`--wait 60`) for an active holder to finish. Inspect the JSON status emitted to `stdout` to see which conversation currently holds the resource.

---

## Command Reference

All commands are executed using `python3 <path-to-skill>/scripts/lock.py`.

### 1. Wrapping a Command (`run`)

Executes a command under exclusive lock, automatically freeing it when the process exits.

```bash
python3 .agents/skills/resource-lock/scripts/lock.py run \
  --conversation-id "<CONVERSATION_ID>" \
  --resource "<RESOURCE_NAME>" \
  [--description "<DESCRIPTION>"] \
  [--wait <SECONDS>] \
  -- <COMMAND...>
```

**Examples:**
```bash
# Launch app in iOS Simulator under exclusive lock
python3 .agents/skills/resource-lock/scripts/lock.py run \
  --conversation-id "a6dc63ae-bce3-4d54-8c26-391b4ca2495e" \
  --resource "ios-simulator" \
  --description "Launching app for smoke test" \
  -- xcrun simctl launch booted com.example.shhhhhh

# Run an adb install command on an Android device with a 90-second wait timeout
python3 .agents/skills/resource-lock/scripts/lock.py run \
  --conversation-id "a6dc63ae-bce3-4d54-8c26-391b4ca2495e" \
  --resource "android-device" \
  --wait 90 \
  -- ./gradlew installDebug
```

### 2. Acquiring a Multi-Turn Lease (`acquire`)

Acquires a background lock that persists across multiple agent turns until explicitly released or until the lease duration expires (default: 300 seconds / 5 minutes).

```bash
python3 .agents/skills/resource-lock/scripts/lock.py acquire \
  --conversation-id "<CONVERSATION_ID>" \
  --resource "<RESOURCE_NAME>" \
  [--description "<DESCRIPTION>"] \
  [--lease <SECONDS>] \
  [--wait <SECONDS>]
```

**Example:**
```bash
python3 .agents/skills/resource-lock/scripts/lock.py acquire \
  --conversation-id "a6dc63ae-bce3-4d54-8c26-391b4ca2495e" \
  --resource "ios-simulator" \
  --description "Interactive UI debugging session" \
  --lease 300
```

### 3. Releasing a Lease (`release`)

Releases an active background lease. By default, only the conversation that acquired the lock can release it. Use `--force` to override if needed.

```bash
python3 .agents/skills/resource-lock/scripts/lock.py release \
  --conversation-id "<CONVERSATION_ID>" \
  --resource "<RESOURCE_NAME>" \
  [--force]
```

**Example:**
```bash
python3 .agents/skills/resource-lock/scripts/lock.py release \
  --conversation-id "a6dc63ae-bce3-4d54-8c26-391b4ca2495e" \
  --resource "ios-simulator"
```

### 4. Checking Lock Status (`status`)

Inspects lock state, active holders, conversation IDs, descriptions, and elapsed lease times.

```bash
# Check status of a specific resource
python3 .agents/skills/resource-lock/scripts/lock.py status --resource "<RESOURCE_NAME>"

# Check all active resource locks on the system
python3 .agents/skills/resource-lock/scripts/lock.py status
```

---

## Lock Mechanism & Fault Tolerance

* **Kernel-Level Locks (`flock`)**: Advisory locks are tied to the process's open file table in the kernel. If a process terminates unexpectedly (even via `SIGKILL`), the OS closes the file descriptor and releases the lock immediately.
* **Shared Across Worktrees**: Lock files reside in `/tmp/antigravity_locks/`, making them host-wide and shared seamlessly across all Git worktrees.
* **Stale Metadata Self-Healing**: If a holder process crashes, subsequent lock requests detect that the file descriptor is free, atomically acquire the lock, and purge stale metadata.

See [references/EXAMPLES.md](references/EXAMPLES.md) for more workflow patterns.
