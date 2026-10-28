# Resource Lock Workflow Examples

This guide illustrates real-world scenarios for coordinating shared resources between distinct Antigravity conversations.

All commands invoke `scripts/lock.py` explicitly as an argument to `python3`:
```bash
python3 .agents/skills/resource-lock/scripts/lock.py <subcommand> --conversation-id "<ID>" ...
```

---

## Scenario A: One-Shot Command Execution with Description

When Conversation A and Conversation B both need to build and run an iOS or Android app, each agent wraps only its active device command.

### Conversation A (`conv-1111`):
```bash
python3 .agents/skills/resource-lock/scripts/lock.py run \
  --conversation-id "conv-1111" \
  --resource "ios-simulator" \
  --description "Installing debug build" \
  -- xcrun simctl install booted ios/build/shhhhhh.app
```

### Conversation B (`conv-2222` running concurrently in another worktree):
```bash
python3 .agents/skills/resource-lock/scripts/lock.py run \
  --conversation-id "conv-2222" \
  --resource "ios-simulator" \
  --description "Launching application" \
  --wait 30 \
  -- xcrun simctl launch booted com.example.shhhhhh
```

**Result on stdout for Conversation B while Conversation A is installing:**
```json
{"event": "waiting", "resource": "ios-simulator", "conversation_id": "conv-1111", "holder_conversation_id": "conv-1111", "acquired_ms_ago": 1420, "lease_duration_ms": null, "lease_ms": null, "description": "Installing debug build", "mode": "run"}
```
As soon as Conversation A completes, Conversation B automatically acquires the lock and runs without any further waiting output.

---

## Scenario B: Multi-Step Interactive Session

When an agent needs to perform an interactive multi-step workflow without another conversation interrupting the simulator in between steps:

### Step 1: Claim Lease
```bash
python3 .agents/skills/resource-lock/scripts/lock.py acquire \
  --conversation-id "conv-3333" \
  --resource "ios-simulator" \
  --description "UI snapshot verification" \
  --lease 300
```
*Output:*
```text
Acquired exclusive lease on resource 'ios-simulator'.
Conversation: conv-3333
Description : UI snapshot verification
Holder PID  : 64512
Duration    : 300.0s (auto-expires at 00:15:00)
Release via : python3 .agents/skills/resource-lock/scripts/lock.py release --conversation-id conv-3333 --resource ios-simulator
```

### Step 2: Perform Conversational Steps
Execute regular device commands knowing no other conversation can obtain a lock on `"ios-simulator"`:
```bash
xcrun simctl launch booted com.example.shhhhhh
xcrun simctl io booted screenshot /tmp/screen1.png
```

### Step 3: Release Lease
```bash
python3 .agents/skills/resource-lock/scripts/lock.py release \
  --conversation-id "conv-3333" \
  --resource "ios-simulator"
```

If another conversation attempts to release this lease, it is rejected unless `--force` is provided:
```bash
python3 .agents/skills/resource-lock/scripts/lock.py release \
  --conversation-id "conv-other" \
  --resource "ios-simulator"
# Error: Resource 'ios-simulator' is currently held by conversation 'conv-3333', not 'conv-other'. Use --force to override.
```

---

## Scenario C: Handling Arbitrary Resource Names

Resources are not limited to mobile devices. Any string can serve as a resource identifier:

```bash
# Custom test harness or database port
python3 .agents/skills/resource-lock/scripts/lock.py run \
  --conversation-id "conv-4444" \
  --resource "test-db-port-5432" \
  --description "Running migration tests" \
  -- npm test

# Arbitrary resource names
python3 .agents/skills/resource-lock/scripts/lock.py run \
  --conversation-id "conv-5555" \
  --resource "zzyzx" \
  --description "Heavy compilation job" \
  -- make run-heavy-task
```

Names are automatically normalized: `"iOS Simulator"`, `"ios-simulator"`, and `"IOS_SIMULATOR"` all resolve to the same underlying lock.
