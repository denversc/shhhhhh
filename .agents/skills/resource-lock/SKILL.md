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

1. **Identify the Resource Name**: Extract the resource name (e.g., `ios-simulator`, `android-device`, `foobar`). Resource names are case-insensitive and whitespace/underscores are automatically normalized to hyphens.
2. **Choose the Granularity**:
   - If performing **a single command or self-contained test script**: Use `run` to wrap the command.
   - If performing a **multi-turn exploratory or interactive procedure**: Call `acquire` before the first command touching the resource, perform your actions across turns, and call `release` as soon as your task concludes.
3. **Handle Contested Resources**: By default, commands wait up to 60 seconds (`--wait 60`) for an active holder to finish. If the wait times out, report the holder PID and current status to the user.

---

## Command Reference

All commands are executed using `python3 <path-to-skill>/scripts/lock.py`.

### 1. Wrapping a Command (`run`)

Executes a command under exclusive lock, automatically freeing it when the process exits.

```bash
python3 .agents/skills/resource-lock/scripts/lock.py run --resource <RESOURCE_NAME> [--wait <SECONDS>] -- <COMMAND...>
```

**Examples:**
```bash
# Launch app in iOS Simulator under exclusive lock
python3 .agents/skills/resource-lock/scripts/lock.py run --resource "ios-simulator" -- xcrun simctl launch booted com.example.shhhhhh

# Run an adb install command on an Android device with a 90-second wait timeout
python3 .agents/skills/resource-lock/scripts/lock.py run --resource "android-device" --wait 90 -- ./gradlew installDebug

# Lock an arbitrary custom resource
python3 .agents/skills/resource-lock/scripts/lock.py run --resource "foobar" -- python3 -m pytest tests/e2e/
```

### 2. Acquiring a Multi-Turn Lease (`acquire`)

Acquires a background lock that persists across multiple agent turns until explicitly released or until the lease duration expires (default: 300 seconds / 5 minutes).

```bash
python3 .agents/skills/resource-lock/scripts/lock.py acquire --resource <RESOURCE_NAME> [--lease <SECONDS>] [--wait <SECONDS>]
```

**Example:**
```bash
python3 .agents/skills/resource-lock/scripts/lock.py acquire --resource "ios-simulator" --lease 300
```

### 3. Releasing a Lease (`release`)

Releases an active background lease so other conversations can claim the resource.

```bash
python3 .agents/skills/resource-lock/scripts/lock.py release --resource <RESOURCE_NAME>
```

**Example:**
```bash
python3 .agents/skills/resource-lock/scripts/lock.py release --resource "ios-simulator"
```

### 4. Checking Lock Status (`status`)

Inspects lock state, active holders, elapsed time, and lease expiration.

```bash
# Check status of a specific resource
python3 .agents/skills/resource-lock/scripts/lock.py status --resource <RESOURCE_NAME>

# Check all active resource locks on the system
python3 .agents/skills/resource-lock/scripts/lock.py status
```

---

## Lock Mechanism & Fault Tolerance

* **Kernel-Level Locks (`flock`)**: Advisory locks are tied to the process's open file table in the kernel. If a process terminates unexpectedly (even via `SIGKILL`), the OS closes the file descriptor and releases the lock immediately.
* **Shared Across Worktrees**: Lock files reside in `/tmp/antigravity_locks/`, making them host-wide and shared seamlessly across all Git worktrees.
* **Stale Metadata Self-Healing**: If a holder process crashes, subsequent lock requests detect that the file descriptor is free, atomically acquire the lock, and purge stale metadata.

See [references/EXAMPLES.md](references/EXAMPLES.md) for more workflow patterns.
