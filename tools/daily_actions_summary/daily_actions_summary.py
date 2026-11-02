#!/usr/bin/env python3
"""
daily_actions_summary.py - Summarize scheduled GitHub Actions workflow runs.

Queries scheduled workflow runs from the preceding 24 hours in a GitHub repository,
formats a scorecard in Toronto local time, and posts it as a comment to a designated
GitHub tracking issue.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import json
import os
import sys
import urllib.error
import urllib.request
import zoneinfo


@dataclass(frozen=True)
class FailedStep:
    name: str
    number: int
    conclusion: str


@dataclass(frozen=True)
class FailedJob:
    name: str
    url: str
    failed_steps: tuple[FailedStep, ...]


@dataclass(frozen=True)
class WorkflowRun:
    id: int
    name: str
    status: str
    conclusion: str | None
    created_at_utc: datetime
    url: str
    failed_jobs: tuple[FailedJob, ...] = ()

    @property
    def display_status(self) -> str:
        if self.status == "completed":
            if self.conclusion == "success":
                return "✅ success"
            return "❌ failure"
        return "🏃 running"


def make_github_request(url: str, token: str, data: bytes | None = None) -> dict | list:
    """Send an authenticated request to the GitHub REST API."""
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "daily-actions-summary-script",
            "X-GitHub-Api-Version": "2022-11-28",
            **({"Content-Type": "application/json"} if data is not None else {}),
        },
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_failed_jobs(repo: str, run_id: int, token: str) -> tuple[FailedJob, ...]:
    """Fetch job and step details for a failed workflow run."""
    url = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs"
    try:
        data = make_github_request(url, token)
    except urllib.error.URLError as err:
        print(f"Warning: Failed to fetch jobs for run {run_id}: {err}", file=sys.stderr)
        return ()

    failed_jobs: list[FailedJob] = []
    for job in data.get("jobs", []):
        if job.get("conclusion") != "success":
            failed_steps: list[FailedStep] = []
            for step in job.get("steps", []):
                if step.get("conclusion") not in ("success", "skipped", None):
                    failed_steps.append(
                        FailedStep(
                            name=step.get("name", "Unknown step"),
                            number=step.get("number", 0),
                            conclusion=step.get("conclusion", "failure"),
                        )
                    )
            failed_jobs.append(
                FailedJob(
                    name=job.get("name", "Unknown job"),
                    url=job.get("html_url", ""),
                    failed_steps=tuple(failed_steps),
                )
            )
    return tuple(failed_jobs)


def fetch_scheduled_runs(
    repo: str,
    token: str,
    cutoff_utc: datetime,
) -> list[WorkflowRun]:
    """Fetch all scheduled workflow runs created on or after cutoff_utc."""
    url = f"https://api.github.com/repos/{repo}/actions/runs?event=schedule&per_page=50"
    data = make_github_request(url, token)

    runs: list[WorkflowRun] = []
    for r in data.get("workflow_runs", []):
        created_at_utc = datetime.fromisoformat(r["created_at"].replace("Z", "+00:00"))
        if created_at_utc < cutoff_utc:
            continue

        run_id = r["id"]
        status = r.get("status", "")
        conclusion = r.get("conclusion")

        failed_jobs: tuple[FailedJob, ...] = ()
        if status == "completed" and conclusion != "success":
            failed_jobs = fetch_failed_jobs(repo, run_id, token)

        runs.append(
            WorkflowRun(
                id=run_id,
                name=r.get("name", "unknown"),
                status=status,
                conclusion=conclusion,
                created_at_utc=created_at_utc,
                url=r.get("html_url", ""),
                failed_jobs=failed_jobs,
            )
        )
    return runs


def format_summary_markdown(
    runs: list[WorkflowRun],
    tz: zoneinfo.ZoneInfo,
    now_utc: datetime,
) -> str:
    """Format the runs into a Markdown scorecard with Toronto timestamps."""
    now_local = now_utc.astimezone(tz)
    tz_name = now_local.strftime("%Z")
    today_str = now_local.strftime("%Y-%m-%d")

    total_count = len(runs)
    success_count = sum(1 for r in runs if r.status == "completed" and r.conclusion == "success")
    pct = int((success_count / total_count * 100) if total_count > 0 else 0)
    status_icon = "✅" if total_count > 0 and success_count == total_count else "❌"

    lines: list[str] = [
        f"### 📊 Daily Scheduled Actions Summary: {today_str}",
        "",
        f"**Overall Status**: {status_icon} {success_count} / {total_count} Passed ({pct}% Success Rate)",
        "**Window**: Preceding 24 hours",
        "",
        "| Workflow | Status | Run Created | Run Link |",
        "| :--- | :--- | :--- | :--- |",
    ]

    for r in runs:
        local_time_str = r.created_at_utc.astimezone(tz).strftime("%Y-%m-%d %H:%M:%S")
        lines.append(f"| `{r.name}` | {r.display_status} | {local_time_str} | [Run #{r.id}]({r.url}) |")

    lines.append("")
    lines.append(f"*Note: All dates and times are expressed in time zone {tz_name}.*")

    # If any failures occurred, list job and step details
    failing_runs = [r for r in runs if r.status == "completed" and r.conclusion != "success"]
    if failing_runs:
        lines.append("")
        lines.append("### Failures")
        for fr in failing_runs:
            lines.append(f"- **`{fr.name}`** ([Run #{fr.id}]({fr.url})):")
            if fr.failed_jobs:
                for job in fr.failed_jobs:
                    step_details = (
                        ", ".join(f"`{s.name}`" for s in job.failed_steps)
                        if job.failed_steps
                        else "unspecified step"
                    )
                    lines.append(f"  - Job [`{job.name}`]({job.url}): failed at {step_details}")
            else:
                lines.append("  - Job details unavailable.")

    return "\n".join(lines)


def post_issue_comment(repo: str, issue_number: int, body: str, token: str) -> str:
    """Post comment to the tracking issue and return the comment html_url."""
    url = f"https://api.github.com/repos/{repo}/issues/{issue_number}/comments"
    payload = json.dumps({"body": body}).encode("utf-8")
    result = make_github_request(url, token, data=payload)
    return result.get("html_url", "")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Summarize scheduled GitHub Actions and post a comment to an issue."
    )
    parser.add_argument(
        "--repo",
        default=os.environ.get("GITHUB_REPOSITORY", "denversc/shhhhhh"),
        help="Target GitHub repository (owner/repo). Defaults to GITHUB_REPOSITORY env var or denversc/shhhhhh.",
    )
    parser.add_argument(
        "--issue-number",
        type=int,
        default=int(os.environ.get("TRACKING_ISSUE_NUMBER", "21")),
        help="GitHub issue number to comment on (default: 21).",
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN"),
        help="GitHub token. Defaults to GH_TOKEN or GITHUB_TOKEN environment variable.",
    )
    parser.add_argument(
        "--timezone",
        default="America/Toronto",
        help="Target timezone name (default: America/Toronto).",
    )
    parser.add_argument(
        "--hours",
        type=int,
        default=24,
        help="Number of preceding hours to include (default: 24).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the generated markdown to stdout without posting to GitHub.",
    )
    return parser.parse_args(argv)


def main() -> None:
    args = parse_args()

    if not args.token:
        print("Error: GitHub token must be provided via --token or GH_TOKEN / GITHUB_TOKEN env var.", file=sys.stderr)
        sys.exit(1)

    try:
        tz = zoneinfo.ZoneInfo(args.timezone)
    except Exception as err:
        print(f"Error: Invalid timezone '{args.timezone}': {err}", file=sys.stderr)
        sys.exit(1)

    now_utc = datetime.now(timezone.utc)
    cutoff_utc = now_utc - timedelta(hours=args.hours)

    print(f"Fetching scheduled runs for {args.repo} since {cutoff_utc.isoformat()}...", file=sys.stderr)
    runs = fetch_scheduled_runs(repo=args.repo, token=args.token, cutoff_utc=cutoff_utc)
    print(f"Found {len(runs)} scheduled runs in the preceding {args.hours} hours.", file=sys.stderr)

    comment_markdown = format_summary_markdown(runs=runs, tz=tz, now_utc=now_utc)

    if args.dry_run:
        print("\n--- [DRY RUN] Generated Comment ---\n")
        print(comment_markdown)
        return

    print(f"Posting comment to issue #{args.issue_number} in {args.repo}...", file=sys.stderr)
    comment_url = post_issue_comment(
        repo=args.repo,
        issue_number=args.issue_number,
        body=comment_markdown,
        token=args.token,
    )
    print(f"Successfully posted comment: {comment_url}")


if __name__ == "__main__":
    main()
