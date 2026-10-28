"""
lock.py - Mutual exclusion and resource locking using POSIX fcntl.flock.

Coordinates exclusive access to shared resources (e.g., iOS Simulator, Android
devices, ports, or custom names) across concurrent Antigravity agent conversations
and background processes on the same host.
"""

import argparse
import fcntl
import json
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

LOCK_DIR = Path("/tmp/antigravity_locks")


def sanitize_resource_name(raw: str) -> str:
    """Normalize resource names so aliases resolve to the same lockfile."""
    cleaned = raw.strip().lower()
    cleaned = re.sub(r"[\s_]+", "-", cleaned)
    cleaned = re.sub(r"[^a-z0-9\-]", "", cleaned)
    cleaned = re.sub(r"-+", "-", cleaned).strip("-")
    if not cleaned:
        raise ValueError(f"Invalid resource name: '{raw}'")
    return cleaned


def get_lock_paths(resource_name: str) -> Tuple[Path, Path]:
    LOCK_DIR.mkdir(parents=True, exist_ok=True)
    sanitized = sanitize_resource_name(resource_name)
    lock_file = LOCK_DIR / f"{sanitized}.lock"
    meta_file = LOCK_DIR / f"{sanitized}.meta.json"
    return lock_file, meta_file


def read_metadata(meta_file: Path) -> Optional[Dict[str, Any]]:
    if not meta_file.exists():
        return None
    try:
        with open(meta_file, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def write_metadata(meta_file: Path, data: Dict[str, Any]) -> None:
    try:
        temp_file = meta_file.with_suffix(".tmp")
        with open(temp_file, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
        temp_file.replace(meta_file)
    except Exception as e:
        sys.stderr.write(f"Warning: Failed to write metadata to {meta_file}: {e}\n")


def remove_metadata(meta_file: Path) -> None:
    try:
        if meta_file.exists():
            meta_file.unlink()
    except Exception:
        pass


def is_lock_held(lock_file: Path) -> bool:
    """Test non-blockingly if a lock is currently held by any process."""
    if not lock_file.exists():
        return False
    try:
        fd = os.open(lock_file, os.O_RDWR | os.O_CREAT, 0o666)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            # Acquired successfully, meaning it was NOT held
            fcntl.flock(fd, fcntl.LOCK_UN)
            return False
        except (BlockingIOError, OSError):
            return True
        finally:
            os.close(fd)
    except Exception:
        return False


def emit_json_event(
    event: str,
    resource: str,
    meta: Optional[Dict[str, Any]],
    reason: Optional[str] = None,
) -> None:
    """Write structured event notification to stdout in JSON format."""
    now = time.time()
    holder_conv = meta.get("conversation_id") if meta else None
    acq_at = meta.get("acquired_at") if meta else None
    acq_ms_ago = int((now - acq_at) * 1000) if acq_at is not None else None

    lease_sec = meta.get("lease_seconds") if meta else None
    lease_ms = int(lease_sec * 1000) if lease_sec is not None else None

    payload = {
        "event": event,
        "resource": resource,
        "conversation_id": holder_conv,
        "holder_conversation_id": holder_conv,
        "acquired_ms_ago": acq_ms_ago,
        "lease_duration_ms": lease_ms,
        "lease_ms": lease_ms,
        "description": meta.get("description") if meta else None,
        "mode": meta.get("mode") if meta else None,
    }
    if reason:
        payload["reason"] = reason

    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


def wait_and_acquire_lock_fd(
    lock_file: Path,
    meta_file: Path,
    resource: str,
    wait_timeout: float,
) -> Optional[int]:
    """
    Attempt to acquire exclusive lock on lock_file.
    If held, emits a JSON status to stdout once, and re-emits only if lock ownership
    transfers to another conversation.
    Returns open file descriptor on success, or None on timeout.
    """
    start_time = time.time()
    fd = os.open(lock_file, os.O_RDWR | os.O_CREAT, 0o666)
    last_reported_key: Optional[Tuple[Optional[str], Optional[float]]] = None

    while True:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return fd
        except (BlockingIOError, OSError):
            elapsed = time.time() - start_time
            if elapsed >= wait_timeout:
                os.close(fd)
                meta = read_metadata(meta_file)
                emit_json_event(
                    event="failed",
                    resource=resource,
                    meta=meta,
                    reason=f"Wait timeout exceeded ({wait_timeout}s)",
                )
                return None

            meta = read_metadata(meta_file)
            holder_key = (
                meta.get("conversation_id") if meta else None,
                meta.get("acquired_at") if meta else None,
            )

            # Emit once when waiting starts, and re-emit only if ownership changes
            if holder_key != last_reported_key:
                emit_json_event(
                    event="waiting",
                    resource=resource,
                    meta=meta,
                )
                last_reported_key = holder_key

            time.sleep(0.25)


# ---------------------------------------------------------------------------
# Subcommand: run
# ---------------------------------------------------------------------------
def cmd_run(args: argparse.Namespace, conversation_id: str, description: Optional[str]) -> int:
    resource = args.resource
    lock_file, meta_file = get_lock_paths(resource)
    wait_sec = args.wait

    fd = wait_and_acquire_lock_fd(lock_file, meta_file, resource, wait_sec)
    if fd is None:
        return 75  # EX_TEMPFAIL

    try:
        # Record execution metadata
        write_metadata(
            meta_file,
            {
                "pid": os.getpid(),
                "resource": resource,
                "conversation_id": conversation_id,
                "description": description,
                "mode": "run",
                "command": args.command,
                "acquired_at": time.time(),
                "lease_seconds": None,
            },
        )

        # Run wrapped command
        proc = subprocess.run(args.command)
        return proc.returncode
    finally:
        remove_metadata(meta_file)
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        except Exception:
            pass
        os.close(fd)


# ---------------------------------------------------------------------------
# Subcommand: acquire
# ---------------------------------------------------------------------------
def cmd_acquire(args: argparse.Namespace, conversation_id: str, description: Optional[str]) -> int:
    resource = args.resource
    lease = args.lease
    wait_sec = args.wait
    lock_file, meta_file = get_lock_paths(resource)

    # First acquire lock fd in foreground (waiting if needed)
    fd = wait_and_acquire_lock_fd(lock_file, meta_file, resource, wait_sec)
    if fd is None:
        return 1

    # Spawn daemon holder process, passing open locked file descriptor
    daemon_cmd = [
        sys.executable,
        os.path.abspath(__file__),
        "_daemon",
        "--resource",
        resource,
        "--lease",
        str(lease),
        "--fd",
        str(fd),
    ]

    proc = subprocess.Popen(
        daemon_cmd,
        pass_fds=(fd,),
        start_new_session=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    # Foreground can now close its copy; daemon process keeps fd open in kernel
    os.close(fd)

    # Write initial metadata with daemon's PID
    now = time.time()
    write_metadata(
        meta_file,
        {
            "pid": proc.pid,
            "resource": resource,
            "conversation_id": conversation_id,
            "description": description,
            "mode": "lease",
            "lease_seconds": lease,
            "acquired_at": now,
            "expires_at": now + lease,
        },
    )

    exp_str = time.strftime("%H:%M:%S", time.localtime(now + lease))
    print(f"Acquired exclusive lease on resource '{resource}'.")
    print(f"Conversation: {conversation_id}")
    if description:
        print(f"Description : {description}")
    print(f"Holder PID  : {proc.pid}")
    print(f"Duration    : {lease}s (auto-expires at {exp_str})")
    print(f"Release via : python3 {sys.argv[0]} release --conversation-id {conversation_id} --resource {resource}")
    return 0


# ---------------------------------------------------------------------------
# Subcommand: _daemon (Internal background holder)
# ---------------------------------------------------------------------------
def cmd_daemon(args: argparse.Namespace) -> int:
    fd = args.fd
    lease = args.lease
    resource = args.resource
    _, meta_file = get_lock_paths(resource)

    # Setup signal handlers for graceful termination
    def handle_exit(signum, frame):
        remove_metadata(meta_file)
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        except Exception:
            pass
        os.close(fd)
        sys.exit(0)

    signal.signal(signal.SIGTERM, handle_exit)
    signal.signal(signal.SIGINT, handle_exit)

    # Sleep until lease duration expires
    time.sleep(lease)

    # Auto-release lease after timeout
    remove_metadata(meta_file)
    try:
        fcntl.flock(fd, fcntl.LOCK_UN)
    except Exception:
        pass
    os.close(fd)
    return 0


# ---------------------------------------------------------------------------
# Subcommand: release
# ---------------------------------------------------------------------------
def cmd_release(args: argparse.Namespace, conversation_id: str) -> int:
    resource = args.resource
    lock_file, meta_file = get_lock_paths(resource)
    meta = read_metadata(meta_file)

    if not is_lock_held(lock_file):
        remove_metadata(meta_file)
        print(f"Resource '{resource}' is already unlocked (free).")
        return 0

    # Verify ownership unless --force is specified
    holder_conv = meta.get("conversation_id") if meta else None
    if holder_conv and holder_conv != conversation_id and not args.force:
        sys.stderr.write(
            f"Error: Resource '{resource}' is currently held by conversation '{holder_conv}', not '{conversation_id}'. Use --force to override.\n"
        )
        return 1

    if meta and "pid" in meta:
        holder_pid = meta["pid"]
        try:
            # Terminate holder process
            os.kill(holder_pid, signal.SIGTERM)
            # Wait up to 3 seconds for process to exit
            for _ in range(30):
                time.sleep(0.1)
                try:
                    os.kill(holder_pid, 0)
                except OSError:
                    break
            else:
                # Force kill if still lingering
                try:
                    os.kill(holder_pid, signal.SIGKILL)
                except OSError:
                    pass
        except ProcessLookupError:
            pass
        except PermissionError:
            sys.stderr.write(f"Error: Insufficient permission to terminate PID {holder_pid}.\n")
            return 1

    remove_metadata(meta_file)

    if args.force and holder_conv and holder_conv != conversation_id:
        print(f"Force-released lock on resource '{resource}' (previously held by conversation '{holder_conv}').")
    else:
        print(f"Successfully released lock on resource '{resource}'.")
    return 0


# ---------------------------------------------------------------------------
# Subcommand: status
# ---------------------------------------------------------------------------
def cmd_status(args: argparse.Namespace) -> int:
    if args.resource:
        resources = [args.resource]
    else:
        # Discover all existing lockfiles
        if not LOCK_DIR.exists():
            print("No active resource locks found.")
            return 0
        resources = [f.stem for f in LOCK_DIR.glob("*.lock")]
        if not resources:
            print("No active resource locks found.")
            return 0

    now = time.time()
    for res in sorted(resources):
        lock_file, meta_file = get_lock_paths(res)
        held = is_lock_held(lock_file)
        meta = read_metadata(meta_file)

        if not held:
            # Clean up stale metadata if needed
            if meta_file.exists():
                remove_metadata(meta_file)
            print(f"Resource: {res}")
            print("  Status      : FREE (unlocked)")
        else:
            print(f"Resource: {res}")
            print("  Status      : LOCKED")
            if meta:
                print(f"  Mode        : {meta.get('mode', 'unknown')}")
                if "conversation_id" in meta:
                    print(f"  Conversation: {meta['conversation_id']}")
                if "description" in meta and meta["description"]:
                    print(f"  Description : {meta['description']}")
                if "pid" in meta:
                    print(f"  Holder PID  : {meta['pid']}")
                if "acquired_at" in meta:
                    acq = meta["acquired_at"]
                    elapsed_ms = int((now - acq) * 1000)
                    print(f"  Held for    : {elapsed_ms} ms ({int(now - acq)}s)")
                if "expires_at" in meta:
                    remaining = int(meta["expires_at"] - now)
                    if remaining > 0:
                        print(f"  Expires in  : {remaining}s")
                    else:
                        print(f"  Expires in  : EXPIRED (pending cleanup)")
                if "command" in meta:
                    print(f"  Command     : {' '.join(meta['command'])}")
        print("-" * 40)

    return 0


# ---------------------------------------------------------------------------
# Main CLI Entrypoint
# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(
        prog="lock.py",
        description="POSIX flock-based mutual exclusion for shared resources.",
    )
    # Global arguments (can be placed before subcommand)
    parser.add_argument(
        "--conversation-id",
        "-c",
        dest="global_conversation_id",
        help="Opaque conversation identifier for the Antigravity session (required).",
    )
    parser.add_argument(
        "--description",
        "-d",
        dest="global_description",
        help="Optional human-friendly description of the lock purpose.",
    )

    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    # Subcommand: run
    p_run = subparsers.add_parser(
        "run",
        help="Execute a command with exclusive lock, automatically unlocking when finished.",
    )
    p_run.add_argument(
        "--conversation-id",
        "-c",
        dest="sub_conversation_id",
        help="Opaque conversation identifier.",
    )
    p_run.add_argument(
        "--description",
        "-d",
        dest="sub_description",
        help="Optional human-friendly description of the lock purpose.",
    )
    p_run.add_argument(
        "--resource",
        "-r",
        required=True,
        help="Name of shared resource to lock (e.g. ios-simulator, android-device, foobar).",
    )
    p_run.add_argument(
        "--wait",
        "-w",
        type=float,
        default=60.0,
        help="Max seconds to wait if resource is currently locked (default: 60s).",
    )
    p_run.add_argument(
        "command",
        nargs=argparse.REMAINDER,
        help="Command and arguments to execute under lock (prefix with --).",
    )

    # Subcommand: acquire
    p_acquire = subparsers.add_parser(
        "acquire",
        help="Acquire a background lock lease across multiple conversational steps.",
    )
    p_acquire.add_argument(
        "--conversation-id",
        "-c",
        dest="sub_conversation_id",
        help="Opaque conversation identifier.",
    )
    p_acquire.add_argument(
        "--description",
        "-d",
        dest="sub_description",
        help="Optional human-friendly description of the lock purpose.",
    )
    p_acquire.add_argument(
        "--resource",
        "-r",
        required=True,
        help="Name of shared resource to lock.",
    )
    p_acquire.add_argument(
        "--lease",
        "-l",
        type=float,
        default=300.0,
        help="Max duration of lease in seconds before automatic release (default: 300s).",
    )
    p_acquire.add_argument(
        "--wait",
        "-w",
        type=float,
        default=60.0,
        help="Max seconds to wait if resource is currently locked (default: 60s).",
    )

    # Subcommand: release
    p_release = subparsers.add_parser(
        "release",
        help="Release an active background lock lease on a resource.",
    )
    p_release.add_argument(
        "--conversation-id",
        "-c",
        dest="sub_conversation_id",
        help="Opaque conversation identifier.",
    )
    p_release.add_argument(
        "--resource",
        "-r",
        required=True,
        help="Name of shared resource to release.",
    )
    p_release.add_argument(
        "--force",
        "-f",
        action="store_true",
        help="Force release even if conversation ID does not match holder.",
    )

    # Subcommand: status
    p_status = subparsers.add_parser(
        "status",
        help="Check the lock status of one or all resources.",
    )
    p_status.add_argument(
        "--resource",
        "-r",
        help="Specific resource name to check (checks all if omitted).",
    )

    # Internal Subcommand: _daemon
    p_daemon = subparsers.add_parser("_daemon", help=argparse.SUPPRESS)
    p_daemon.add_argument("--resource", required=True)
    p_daemon.add_argument("--lease", type=float, required=True)
    p_daemon.add_argument("--fd", type=int, required=True)

    # Parse arguments
    args = parser.parse_args()

    # Internal daemon does not require conversation ID resolution
    if args.subcommand == "_daemon":
        return cmd_daemon(args)

    # Status does not strictly require conversation ID
    if args.subcommand == "status":
        return cmd_status(args)

    # Resolve conversation_id and description from subparser or global
    conversation_id = getattr(args, "sub_conversation_id", None) or getattr(args, "global_conversation_id", None)
    description = getattr(args, "sub_description", None) or getattr(args, "global_description", None)

    if not conversation_id:
        parser.error("argument --conversation-id/-c is required.")

    # Strip leading '--' from command if present in 'run'
    if args.subcommand == "run":
        if args.command and args.command[0] == "--":
            args.command = args.command[1:]
        if not args.command:
            parser.error("Subcommand 'run' requires a command to execute.")
        return cmd_run(args, conversation_id, description)
    elif args.subcommand == "acquire":
        return cmd_acquire(args, conversation_id, description)
    elif args.subcommand == "release":
        return cmd_release(args, conversation_id)

    return 0


if __name__ == "__main__":
    sys.exit(main())
