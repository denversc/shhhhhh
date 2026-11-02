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
from pathlib import Path
import sys
import urllib.error
import urllib.request
import zoneinfo

import yaml


@dataclass(frozen=True)
class ScheduledWorkflow:
  name: str
  file_path: str


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
  workflow_path: str = ""
  failed_jobs: tuple[FailedJob, ...] = ()

  @property
  def display_status(self) -> str:
    if self.status == "completed":
      if self.conclusion == "success":
        return "✅ success"
      return "❌ failure"
    return "⏳ in progress"


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
        workflow_path=r.get("path", ""),
        failed_jobs=failed_jobs,
      )
    )
  return runs


def discover_scheduled_workflows(workflows_dir: Path) -> list[ScheduledWorkflow]:
  """Read workflow YAML files in workflows_dir and return workflows with a scheduled trigger."""
  scheduled: list[ScheduledWorkflow] = []
  patterns = ("*.yml", "*.yaml")
  workflow_files: list[Path] = []
  for pattern in patterns:
    workflow_files.extend(workflows_dir.glob(pattern))

  for file_path in sorted(workflow_files):
    try:
      with open(file_path, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)
    except Exception as err:
      print(f"Warning: Failed to parse YAML file {file_path}: {err}", file=sys.stderr)
      continue

    if not isinstance(data, dict):
      continue

    on_clause = data.get("on") if "on" in data else data.get(True)
    has_schedule = False
    if isinstance(on_clause, dict):
      has_schedule = "schedule" in on_clause
    elif isinstance(on_clause, list):
      has_schedule = "schedule" in on_clause
    elif isinstance(on_clause, str):
      has_schedule = on_clause == "schedule"

    if has_schedule:
      name = data.get("name") or file_path.stem
      scheduled.append(ScheduledWorkflow(name=str(name), file_path=str(file_path)))

  return sorted(scheduled, key=lambda w: w.name)


def find_missing_scheduled_workflows(
  expected_workflows: list[ScheduledWorkflow],
  runs: list[WorkflowRun],
) -> list[ScheduledWorkflow]:
  """Identify scheduled workflows that have no runs in the provided list."""
  executed_names = {r.name for r in runs}
  executed_paths = {Path(r.workflow_path).name for r in runs if r.workflow_path}

  missing: list[ScheduledWorkflow] = []
  for w in expected_workflows:
    wf_filename = Path(w.file_path).name
    if w.name not in executed_names and wf_filename not in executed_paths:
      missing.append(w)
  return sorted(missing, key=lambda w: w.name)


def format_summary_markdown(
  runs: list[WorkflowRun],
  missing_workflows: list[ScheduledWorkflow],
  tz: zoneinfo.ZoneInfo,
  now_utc: datetime,
) -> str:
  """Format the runs into a Markdown scorecard with Toronto timestamps."""
  now_local = now_utc.astimezone(tz)
  tz_name = now_local.strftime("%Z")
  today_str = now_local.strftime("%Y-%m-%d")

  total_count = len(runs) + len(missing_workflows)
  success_count = sum(1 for r in runs if r.status == "completed" and r.conclusion == "success")
  pct = int((success_count / total_count * 100) if total_count > 0 else 0)
  status_icon = "✅" if total_count > 0 and success_count == total_count else "❌"

  rate_str = f"{status_icon} {success_count} / {total_count} Passed ({pct}% Success Rate)"
  lines: list[str] = [
    f"### 📊 Daily Scheduled Actions Summary: {today_str}",
    "",
    f"**Overall Status**: {rate_str}",
    "**Window**: Preceding 24 hours",
    "",
    "| Workflow | Status | Run Created | Run Link |",
    "| :--- | :--- | :--- | :--- |",
  ]

  table_rows: list[tuple[str, str, str, str]] = []
  for r in runs:
    local_time_str = r.created_at_utc.astimezone(tz).strftime("%Y-%m-%d %H:%M:%S")
    table_rows.append((r.name, r.display_status, local_time_str, f"[Run #{r.id}]({r.url})"))

  for mw in missing_workflows:
    table_rows.append((mw.name, "❌ missing", "—", "—"))

  table_rows.sort(key=lambda row: row[0].lower())

  for name, display_status, created_str, link_str in table_rows:
    lines.append(f"| `{name}` | {display_status} | {created_str} | {link_str} |")

  lines.append("")
  lines.append(f"*Note: All dates and times are expressed in time zone {tz_name}.*")

  # If any failures occurred, list job and step details as well as missing workflows
  failing_runs = [r for r in runs if r.status == "completed" and r.conclusion != "success"]
  if failing_runs or missing_workflows:
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
    for mw in missing_workflows:
      lines.append(f"- **`{mw.name}`**: No scheduled runs in the past 24 hours.")

  return "\n".join(lines)


def post_issue_comment(repo: str, issue_number: int, body: str, token: str) -> str:
  """Post comment to the tracking issue and return the comment html_url."""
  url = f"https://api.github.com/repos/{repo}/issues/{issue_number}/comments"
  payload = json.dumps({"body": body}).encode("utf-8")
  result = make_github_request(url, token, data=payload)
  return result.get("html_url", "")


def get_default_workflows_dir() -> Path:
  """Get default path to .github/workflows directory."""
  repo_root = Path(__file__).resolve().parent.parent.parent
  candidate = repo_root / ".github" / "workflows"
  if candidate.is_dir():
    return candidate
  return Path.cwd() / ".github" / "workflows"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
  parser = argparse.ArgumentParser(
    description="Summarize scheduled GitHub Actions and post a comment to an issue."
  )
  parser.add_argument(
    "--repo",
    default=os.environ.get("GITHUB_REPOSITORY", "denversc/shhhhhh"),
    help=(
      "Target GitHub repository (owner/repo). "
      "Defaults to GITHUB_REPOSITORY env var or denversc/shhhhhh."
    ),
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
    "--workflows-dir",
    type=Path,
    default=None,
    help=(
      "Path to .github/workflows directory. "
      "Defaults to auto-detecting .github/workflows in repo root."
    ),
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
    print(
      "Error: GitHub token must be provided via --token or GH_TOKEN / GITHUB_TOKEN env var.",
      file=sys.stderr,
    )
    sys.exit(1)

  try:
    tz = zoneinfo.ZoneInfo(args.timezone)
  except Exception as err:
    print(f"Error: Invalid timezone '{args.timezone}': {err}", file=sys.stderr)
    sys.exit(1)

  workflows_dir = args.workflows_dir or get_default_workflows_dir()
  expected_workflows: list[ScheduledWorkflow] = []
  if workflows_dir.is_dir():
    expected_workflows = discover_scheduled_workflows(workflows_dir)
    print(
      f"Discovered {len(expected_workflows)} scheduled workflows in {workflows_dir}.",
      file=sys.stderr,
    )
  else:
    print(f"Warning: Workflows directory '{workflows_dir}' not found.", file=sys.stderr)

  now_utc = datetime.now(timezone.utc)
  cutoff_utc = now_utc - timedelta(hours=args.hours)

  print(
    f"Fetching scheduled runs for {args.repo} since {cutoff_utc.isoformat()}...",
    file=sys.stderr,
  )
  runs = fetch_scheduled_runs(repo=args.repo, token=args.token, cutoff_utc=cutoff_utc)
  print(
    f"Found {len(runs)} scheduled runs in the preceding {args.hours} hours.",
    file=sys.stderr,
  )

  missing_workflows = find_missing_scheduled_workflows(expected_workflows, runs)
  if missing_workflows:
    missing_names = ", ".join(f"'{w.name}'" for w in missing_workflows)
    print(
      f"Error: {len(missing_workflows)} scheduled workflow(s) missing runs in the past {args.hours} hours: {missing_names}",
      file=sys.stderr,
    )

  comment_markdown = format_summary_markdown(
    runs=runs,
    missing_workflows=missing_workflows,
    tz=tz,
    now_utc=now_utc,
  )

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
