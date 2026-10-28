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
from typing import Any, Dict, List, Optional

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


def get_lock_paths(resource_name: str) -> tuple[Path, Path]:
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


def wait_and_acquire_lock_fd(lock_file: Path, meta_file: Path, wait_timeout: float) -> Optional[int]:
    """
    Attempt to acquire exclusive lock on lock_file.
    If held, polls until acquired or wait_timeout elapses.
    Returns open file descriptor on success, or None on timeout.
    """
    start_time = time.time()
    fd = os.open(lock_file, os.O_RDWR | os.O_CREAT, 0o666)
    last_notice = 0.0

    while True:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return fd
        except (BlockingIOError, OSError):
            elapsed = time.time() - start_time
            if elapsed >= wait_timeout:
                os.close(fd)
                return None

            # Print status periodically if waiting
            now = time.time()
            if now - last_notice >= 5.0:
                last_notice = now
                meta = read_metadata(meta_file)
                holder_info = ""
                if meta and "pid" in meta:
                    holder_info = f" (held by PID {meta['pid']} since {time.strftime('%H:%M:%S', time.localtime(meta.get('acquired_at', now)))})"
                sys.stderr.write(f"Waiting for lock on '{lock_file.stem}'{holder_info}... ({int(elapsed)}s elapsed, timeout {wait_timeout}s)\n")
                sys.stderr.flush()

            time.sleep(0.25)


# ---------------------------------------------------------------------------
# Subcommand: run
# ---------------------------------------------------------------------------
def cmd_run(args: argparse.Namespace) -> int:
    resource = args.resource
    lock_file, meta_file = get_lock_paths(resource)
    wait_sec = args.wait

    fd = wait_and_acquire_lock_fd(lock_file, meta_file, wait_sec)
    if fd is None:
        meta = read_metadata(meta_file)
        msg = f"Error: Could not acquire lock on resource '{resource}' within {wait_sec}s timeout.\n"
        if meta and "pid" in meta:
            msg += f"Currently locked by PID {meta['pid']} (mode: {meta.get('mode', 'unknown')}).\n"
        sys.stderr.write(msg)
        return 75  # EX_TEMPFAIL

    try:
        # Record execution metadata
        write_metadata(
            meta_file,
            {
                "pid": os.getpid(),
                "resource": resource,
                "mode": "run",
                "command": args.command,
                "acquired_at": time.time(),
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
def cmd_acquire(args: argparse.Namespace) -> int:
    resource = args.resource
    lease = args.lease
    wait_sec = args.wait
    lock_file, meta_file = get_lock_paths(resource)

    # First acquire lock fd in foreground (waiting if needed)
    fd = wait_and_acquire_lock_fd(lock_file, meta_file, wait_sec)
    if fd is None:
        meta = read_metadata(meta_file)
        msg = f"Error: Could not acquire lease on resource '{resource}' within {wait_sec}s timeout.\n"
        if meta and "pid" in meta:
            msg += f"Currently locked by PID {meta['pid']}.\n"
        sys.stderr.write(msg)
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

    # Foreground can now close its copy; the daemon process keeps fd open in kernel
    os.close(fd)

    # Write initial metadata with daemon's PID
    now = time.time()
    write_metadata(
        meta_file,
        {
            "pid": proc.pid,
            "resource": resource,
            "mode": "lease",
            "lease_seconds": lease,
            "acquired_at": now,
            "expires_at": now + lease,
        },
    )

    exp_str = time.strftime("%H:%M:%S", time.localtime(now + lease))
    print(f"Acquired exclusive lease on resource '{resource}'.")
    print(f"Holder PID : {proc.pid}")
    print(f"Duration   : {lease}s (auto-expires at {exp_str})")
    print(f"Release via: python3 {sys.argv[0]} release --resource {resource}")
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
def cmd_release(args: argparse.Namespace) -> int:
    resource = args.resource
    lock_file, meta_file = get_lock_paths(resource)
    meta = read_metadata(meta_file)

    if not is_lock_held(lock_file):
        remove_metadata(meta_file)
        print(f"Resource '{resource}' is already unlocked (free).")
        return 0

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

    # Double check lock status
    if not is_lock_held(lock_file):
        print(f"Successfully released lock on resource '{resource}'.")
        return 0
    else:
        print(f"Sent termination signal, but lock on '{resource}' may still be clearing.")
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
            print("  Status   : FREE (unlocked)")
        else:
            print(f"Resource: {res}")
            print("  Status   : LOCKED")
            if meta:
                print(f"  Mode     : {meta.get('mode', 'unknown')}")
                if "pid" in meta:
                    print(f"  Holder PID: {meta['pid']}")
                if "acquired_at" in meta:
                    acq = meta["acquired_at"]
                    elapsed = int(now - acq)
                    print(f"  Held for : {elapsed}s (since {time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(acq))})")
                if "expires_at" in meta:
                    remaining = int(meta["expires_at"] - now)
                    if remaining > 0:
                        print(f"  Expires  : in {remaining}s")
                    else:
                        print(f"  Expires  : EXPIRED (pending cleanup)")
                if "command" in meta:
                    print(f"  Command  : {' '.join(meta['command'])}")
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
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    # Subcommand: run
    p_run = subparsers.add_parser(
        "run",
        help="Execute a command with exclusive lock, automatically unlocking when finished.",
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
        "--resource",
        "-r",
        required=True,
        help="Name of shared resource to release.",
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

    # Strip leading '--' from command if present in 'run'
    if args.subcommand == "run":
        if args.command and args.command[0] == "--":
            args.command = args.command[1:]
        if not args.command:
            parser.error("Subcommand 'run' requires a command to execute.")

    if args.subcommand == "run":
        return cmd_run(args)
    elif args.subcommand == "acquire":
        return cmd_acquire(args)
    elif args.subcommand == "release":
        return cmd_release(args)
    elif args.subcommand == "status":
        return cmd_status(args)
    elif args.subcommand == "_daemon":
        return cmd_daemon(args)

    return 0


if __name__ == "__main__":
    sys.exit(main())
