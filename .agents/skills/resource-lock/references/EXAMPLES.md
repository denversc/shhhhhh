# Resource Lock Workflow Examples

This guide illustrates real-world scenarios for coordinating shared resources between distinct Antigravity conversations.

All commands invoke `scripts/lock.py` explicitly as an argument to `python3`:
```bash
python3 .agents/skills/resource-lock/scripts/lock.py <subcommand>
```

---

## Scenario A: One-Shot Command Execution (Recommended)

When Conversation A and Conversation B both need to build and run an iOS or Android app, each agent wraps only its active device command.

### Conversation A:
```bash
python3 .agents/skills/resource-lock/scripts/lock.py run --resource "ios-simulator" -- \
  xcrun simctl install booted ios/build/shhhhhh.app
```

### Conversation B (running concurrently in another worktree):
```bash
python3 .agents/skills/resource-lock/scripts/lock.py run --resource "ios-simulator" -- \
  xcrun simctl launch booted com.example.shhhhhh
```

**Result:**
If Conversation A is currently installing, Conversation B's command waits (polling up to the `--wait` timeout, default 60s). As soon as Conversation A completes, Conversation B acquires the simulator and runs without conflict.

---

## Scenario B: Multi-Step Interactive Session

When an agent needs to perform an interactive multi-step workflow (e.g., launching an app, inspecting UI hierarchy, taking screenshots, and verifying results) without another conversation interrupting the simulator in between steps:

### Step 1: Claim Lease
```bash
python3 .agents/skills/resource-lock/scripts/lock.py acquire --resource "ios-simulator" --lease 300
```
*Output:*
```text
Acquired exclusive lease on resource 'ios-simulator'.
Holder PID : 64512
Duration   : 300.0s (auto-expires at 00:05:00)
Release via: python3 .agents/skills/resource-lock/scripts/lock.py release --resource ios-simulator
```

### Step 2: Perform Conversational Steps
Execute regular device commands knowing no other conversation can obtain a lock on `"ios-simulator"`:
```bash
xcrun simctl launch booted com.example.shhhhhh
xcrun simctl io booted screenshot /tmp/screen1.png
```

### Step 3: Release Lease
```bash
python3 .agents/skills/resource-lock/scripts/lock.py release --resource "ios-simulator"
```

---

## Scenario C: Handling Arbitrary Resource Names

Resources are not limited to mobile devices. Any string can serve as a resource identifier:

```bash
# Custom test harness or database port
python3 .agents/skills/resource-lock/scripts/lock.py run --resource "test-db-port-5432" -- npm test

# Arbitrary resource names
python3 .agents/skills/resource-lock/scripts/lock.py run --resource "zzyzx" -- make run-heavy-task
```

Names are automatically normalized: `"iOS Simulator"`, `"ios-simulator"`, and `"IOS_SIMULATOR"` all resolve to the same underlying lock.
