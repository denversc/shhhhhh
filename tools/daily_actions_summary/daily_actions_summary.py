#!/usr/bin/env python3
"""
daily_actions_summary.py - Summarize scheduled GitHub Actions workflow runs.

Queries scheduled workflow runs from the preceding 24 hours in a GitHub repository,
formats a scorecard in Toronto local time, and posts it as a comment to a designated
GitHub tracking issue.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import zoneinfo
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, cast

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
  run_started_at_utc: datetime | None = None
  updated_at_utc: datetime | None = None

  @property
  def display_status(self) -> str:
    if self.status == "completed":
      if self.conclusion == "success":
        return "✅ success"
      return "❌ failure"
    return "⏳ in progress"

  @property
  def elapsed_time_str(self) -> str:
    start = self.run_started_at_utc or self.created_at_utc
    end = self.updated_at_utc or start
    if start.tzinfo is not None and end.tzinfo is None:
      end = end.replace(tzinfo=start.tzinfo)
    elif start.tzinfo is None and end.tzinfo is not None:
      start = start.replace(tzinfo=end.tzinfo)
    duration_seconds = max(0.0, (end - start).total_seconds())
    minutes = max(1, round(duration_seconds / 60))
    return f"{minutes} min."


def make_github_request(
  url: str, token: str, data: bytes | None = None
) -> dict[str, Any] | list[Any]:
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
    parsed: Any = json.loads(resp.read().decode("utf-8"))
    if isinstance(parsed, dict):
      return cast("dict[str, Any]", parsed)
    if isinstance(parsed, list):
      return cast("list[Any]", parsed)
    return {}


def fetch_failed_jobs(repo: str, run_id: int, token: str) -> tuple[FailedJob, ...]:
  """Fetch job and step details for a failed workflow run."""
  url = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs"
  try:
    data = make_github_request(url, token)
  except urllib.error.URLError as err:
    print(f"Warning: Failed to fetch jobs for run {run_id}: {err}", file=sys.stderr)
    return ()

  if not isinstance(data, dict):
    return ()

  failed_jobs: list[FailedJob] = []
  jobs_list: list[Any] = data.get("jobs", [])
  for raw_job in jobs_list:
    if not isinstance(raw_job, dict):
      continue
    job = cast("dict[str, Any]", raw_job)
    if job.get("conclusion") != "success":
      failed_steps: list[FailedStep] = []
      steps_list: list[Any] = job.get("steps", [])
      for raw_step in steps_list:
        if not isinstance(raw_step, dict):
          continue
        step = cast("dict[str, Any]", raw_step)
        if step.get("conclusion") not in ("success", "skipped", None):
          failed_steps.append(
            FailedStep(
              name=str(step.get("name", "Unknown step")),
              number=int(step.get("number", 0)),
              conclusion=str(step.get("conclusion", "failure")),
            )
          )
      failed_jobs.append(
        FailedJob(
          name=str(job.get("name", "Unknown job")),
          url=str(job.get("html_url", "")),
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
  if not isinstance(data, dict):
    return []

  runs: list[WorkflowRun] = []
  raw_runs: list[Any] = data.get("workflow_runs", [])
  for raw_run in raw_runs:
    if not isinstance(raw_run, dict):
      continue
    r = cast("dict[str, Any]", raw_run)
    created_at_raw = str(r.get("created_at", ""))
    created_at_utc = datetime.fromisoformat(created_at_raw)
    if created_at_utc < cutoff_utc:
      continue

    run_id = int(r.get("id", 0))
    status = str(r.get("status", ""))
    conclusion = str(r["conclusion"]) if r.get("conclusion") is not None else None

    failed_jobs: tuple[FailedJob, ...] = ()
    if status == "completed" and conclusion != "success":
      failed_jobs = fetch_failed_jobs(repo, run_id, token)

    run_started_at_utc: datetime | None = None
    if r.get("run_started_at"):
      try:
        run_started_at_utc = datetime.fromisoformat(str(r["run_started_at"]))
      except Exception:
        run_started_at_utc = None

    updated_at_utc: datetime | None = None
    if r.get("updated_at"):
      try:
        updated_at_utc = datetime.fromisoformat(str(r["updated_at"]))
      except Exception:
        updated_at_utc = None

    runs.append(
      WorkflowRun(
        id=run_id,
        name=str(r.get("name", "unknown")),
        status=status,
        conclusion=conclusion,
        created_at_utc=created_at_utc,
        url=str(r.get("html_url", "")),
        workflow_path=str(r.get("path", "")),
        failed_jobs=failed_jobs,
        run_started_at_utc=run_started_at_utc,
        updated_at_utc=updated_at_utc,
      )
    )
  return runs


def is_workflow_excluded(name: str, file_path_or_name: str, excluded_set: set[str]) -> bool:
  """Check if a workflow matches any entry in excluded_set by name or filename."""
  if not excluded_set:
    return False
  path_obj = Path(file_path_or_name)
  candidates = {
    name.lower(),
    path_obj.name.lower(),
    path_obj.stem.lower(),
  }
  return not candidates.isdisjoint(excluded_set)


def discover_scheduled_workflows(
  workflows_dir: Path,
  exclude_workflows: set[str] | None = None,
) -> list[ScheduledWorkflow]:
  """Read workflow YAML files in workflows_dir and return workflows with a scheduled trigger."""
  scheduled: list[ScheduledWorkflow] = []
  patterns = ("*.yml", "*.yaml")
  workflow_files: list[Path] = []
  for pattern in patterns:
    workflow_files.extend(workflows_dir.glob(pattern))

  excluded_set: set[str] = {x.lower() for x in exclude_workflows} if exclude_workflows else set()

  for file_path in sorted(workflow_files):
    try:
      with open(file_path, encoding="utf-8") as f:
        data: Any = yaml.safe_load(f)
    except Exception as err:
      print(f"Warning: Failed to parse YAML file {file_path}: {err}", file=sys.stderr)
      continue

    if not isinstance(data, dict):
      continue

    data_dict = cast("dict[Any, Any]", data)
    on_clause: Any = data_dict.get("on") if "on" in data_dict else data_dict.get(True)
    has_schedule = False
    if isinstance(on_clause, (dict, list)):
      has_schedule = "schedule" in on_clause
    elif isinstance(on_clause, str):
      has_schedule = on_clause == "schedule"

    if has_schedule:
      name = str(data_dict.get("name") or file_path.stem)
      if not is_workflow_excluded(name, str(file_path), excluded_set):
        scheduled.append(ScheduledWorkflow(name=name, file_path=str(file_path)))

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
  header_time_str = now_local.strftime("%Y-%m-%d %H:%M")

  total_count = len(runs) + len(missing_workflows)
  success_count = sum(1 for r in runs if r.status == "completed" and r.conclusion == "success")
  failed_count = total_count - success_count
  if failed_count > 0:
    status_str = f"❌ {success_count} / {total_count} Passed, {failed_count} / {total_count} Failed"
  else:
    status_str = f"✅ {success_count} / {total_count} Passed"

  lines: list[str] = [
    f"### Scheduled Actions Summary: {header_time_str}",
    "",
    f"**Overall Status**: {status_str}",
    "**Window**: Preceding 24 hours",
    "",
    "| Workflow | Status | Elapsed Time | Run Created | Run Link |",
    "| :--- | :--- | :--- | :--- | :--- |",
  ]

  table_rows: list[tuple[str, str, str, str, str]] = []
  for r in runs:
    local_time_str = r.created_at_utc.astimezone(tz).strftime("%Y-%m-%d %H:%M:%S")
    table_rows.append(
      (r.name, r.display_status, r.elapsed_time_str, local_time_str, f"[Run #{r.id}]({r.url})")
    )

  for mw in missing_workflows:
    table_rows.append((mw.name, "❌ missing", "—", "—", "—"))

  table_rows.sort(key=lambda row: row[0].lower())

  for name, display_status, elapsed_str, created_str, link_str in table_rows:
    lines.append(f"| `{name}` | {display_status} | {elapsed_str} | {created_str} | {link_str} |")

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
  if isinstance(result, dict):
    return str(result.get("html_url", ""))
  return ""


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
    "--exclude-workflow",
    "--exclude",
    dest="exclude_workflows",
    action="append",
    default=[],
    help=(
      "Workflow name or filename to exclude from the report (can be specified multiple times)."
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

  excluded_set = {x.lower() for x in args.exclude_workflows}

  workflows_dir = args.workflows_dir or get_default_workflows_dir()
  expected_workflows: list[ScheduledWorkflow] = []
  if workflows_dir.is_dir():
    expected_workflows = discover_scheduled_workflows(workflows_dir, exclude_workflows=excluded_set)
    print(
      f"Discovered {len(expected_workflows)} scheduled workflows in {workflows_dir}.",
      file=sys.stderr,
    )
  else:
    print(f"Warning: Workflows directory '{workflows_dir}' not found.", file=sys.stderr)

  now_utc = datetime.now(UTC)
  cutoff_utc = now_utc - timedelta(hours=args.hours)

  print(
    f"Fetching scheduled runs for {args.repo} since {cutoff_utc.isoformat()}...",
    file=sys.stderr,
  )
  runs = fetch_scheduled_runs(repo=args.repo, token=args.token, cutoff_utc=cutoff_utc)

  if excluded_set:
    runs = [r for r in runs if not is_workflow_excluded(r.name, r.workflow_path, excluded_set)]

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
