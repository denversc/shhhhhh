#!/usr/bin/env python3
"""Tests for daily_actions_summary.py."""

from __future__ import annotations

import tempfile
import unittest
import zoneinfo
from datetime import datetime, timezone
from pathlib import Path

from daily_actions_summary import (
  FailedJob,
  FailedStep,
  ScheduledWorkflow,
  WorkflowRun,
  discover_scheduled_workflows,
  find_missing_scheduled_workflows,
  format_summary_markdown,
  is_workflow_excluded,
  parse_args,
)


class DiscoverScheduledWorkflowsTest(unittest.TestCase):
  def test_discover_scheduled_workflows(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
      dir_path = Path(tmp_dir)

      # Workflow with schedule and explicit name
      (dir_path / "scheduled_one.yml").write_text(
        """
name: First Scheduled
on:
  schedule:
    - cron: "0 11 * * *"
  pull_request:
""",
        encoding="utf-8",
      )

      # Workflow without schedule
      (dir_path / "pr_only.yml").write_text(
        """
name: PR Only
on:
  pull_request:
    branches: [main]
""",
        encoding="utf-8",
      )

      # Workflow with schedule without explicit name (fallback to file stem)
      (dir_path / "no_name.yaml").write_text(
        """
on:
  schedule:
    - cron: "0 12 * * *"
""",
        encoding="utf-8",
      )

      # Workflow with on as list containing schedule
      (dir_path / "list_schedule.yml").write_text(
        """
name: List Schedule
on: [push, schedule]
""",
        encoding="utf-8",
      )

      # Malformed / empty YAML
      (dir_path / "broken.yml").write_text(":: invalid yaml ::", encoding="utf-8")
      (dir_path / "empty.yml").write_text("", encoding="utf-8")

      results = discover_scheduled_workflows(dir_path)

      names = [w.name for w in results]
      self.assertEqual(names, ["First Scheduled", "List Schedule", "no_name"])
      self.assertEqual(len(results), 3)

      # Test with exclusion
      filtered = discover_scheduled_workflows(
        dir_path, exclude_workflows={"first scheduled", "no_name.yaml"}
      )
      self.assertEqual([w.name for w in filtered], ["List Schedule"])

  def test_is_workflow_excluded(self) -> None:
    excluded = {"daily-actions-summary", "foo.yml"}
    self.assertTrue(
      is_workflow_excluded(
        name="daily-actions-summary",
        file_path_or_name=".github/workflows/daily-actions-summary.yml",
        excluded_set=excluded,
      )
    )
    self.assertTrue(
      is_workflow_excluded(
        name="different-name",
        file_path_or_name=".github/workflows/foo.yml",
        excluded_set=excluded,
      )
    )
    self.assertFalse(
      is_workflow_excluded(
        name="android",
        file_path_or_name=".github/workflows/android.yml",
        excluded_set=excluded,
      )
    )

  def test_parse_args_exclude_workflows_multiple(self) -> None:
    args = parse_args(
      [
        "--token",
        "dummy",
        "--exclude-workflow",
        "daily-actions-summary",
        "--exclude-workflow",
        "ios",
        "--exclude",
        "android",
      ]
    )
    self.assertEqual(
      args.exclude_workflows,
      ["daily-actions-summary", "ios", "android"],
    )


class FindMissingScheduledWorkflowsTest(unittest.TestCase):
  def setUp(self) -> None:
    self.wf1 = ScheduledWorkflow(name="wf1", file_path="/path/to/wf1.yml")
    self.wf2 = ScheduledWorkflow(name="wf2", file_path="/path/to/wf2.yml")
    self.wf3 = ScheduledWorkflow(name="wf3", file_path="/path/to/wf3.yml")
    self.expected = [self.wf1, self.wf2, self.wf3]

  def test_all_executed(self) -> None:
    runs = [
      WorkflowRun(
        id=1,
        name="wf1",
        status="completed",
        conclusion="success",
        created_at_utc=datetime.now(timezone.utc),
        url="http://example.com/1",
      ),
      WorkflowRun(
        id=2,
        name="wf2",
        status="completed",
        conclusion="success",
        created_at_utc=datetime.now(timezone.utc),
        url="http://example.com/2",
      ),
      WorkflowRun(
        id=3,
        name="wf3",
        status="completed",
        conclusion="success",
        created_at_utc=datetime.now(timezone.utc),
        url="http://example.com/3",
      ),
    ]
    missing = find_missing_scheduled_workflows(self.expected, runs)
    self.assertEqual(missing, [])

  def test_some_missing(self) -> None:
    runs = [
      WorkflowRun(
        id=1,
        name="wf1",
        status="completed",
        conclusion="success",
        created_at_utc=datetime.now(timezone.utc),
        url="http://example.com/1",
      ),
    ]
    missing = find_missing_scheduled_workflows(self.expected, runs)
    self.assertEqual([w.name for w in missing], ["wf2", "wf3"])

  def test_matched_by_path(self) -> None:
    # If run name is different but workflow path matches wf2.yml
    runs = [
      WorkflowRun(
        id=1,
        name="wf1",
        status="completed",
        conclusion="success",
        created_at_utc=datetime.now(timezone.utc),
        url="http://example.com/1",
      ),
      WorkflowRun(
        id=2,
        name="Different Display Name",
        status="completed",
        conclusion="success",
        created_at_utc=datetime.now(timezone.utc),
        url="http://example.com/2",
        workflow_path=".github/workflows/wf2.yml",
      ),
    ]
    missing = find_missing_scheduled_workflows(self.expected, runs)
    self.assertEqual([w.name for w in missing], ["wf3"])


class FormatSummaryMarkdownTest(unittest.TestCase):
  def setUp(self) -> None:
    self.tz = zoneinfo.ZoneInfo("America/Toronto")
    self.now_utc = datetime(2026, 11, 2, 18, 0, 0, tzinfo=timezone.utc)

  def test_all_passed_no_missing(self) -> None:
    runs = [
      WorkflowRun(
        id=101,
        name="lint",
        status="completed",
        conclusion="success",
        created_at_utc=datetime(2026, 11, 2, 15, 0, 0, tzinfo=timezone.utc),
        url="http://example.com/101",
      ),
    ]
    md = format_summary_markdown(runs=runs, missing_workflows=[], tz=self.tz, now_utc=self.now_utc)
    self.assertIn("✅ 1 / 1 Passed (100% Success Rate)", md)
    self.assertIn("| `lint` | ✅ success |", md)
    self.assertNotIn("### Failures", md)
    self.assertNotIn("❌ missing", md)

  def test_with_missing_workflows(self) -> None:
    runs = [
      WorkflowRun(
        id=101,
        name="lint",
        status="completed",
        conclusion="success",
        created_at_utc=datetime(2026, 11, 2, 15, 0, 0, tzinfo=timezone.utc),
        url="http://example.com/101",
      ),
    ]
    missing = [
      ScheduledWorkflow(name="android", file_path="/path/to/android.yml"),
      ScheduledWorkflow(name="ios", file_path="/path/to/ios.yml"),
    ]
    md = format_summary_markdown(
      runs=runs, missing_workflows=missing, tz=self.tz, now_utc=self.now_utc
    )
    self.assertIn("❌ 1 / 3 Passed (33% Success Rate)", md)
    self.assertIn("| `android` | ❌ missing | — | — |", md)
    self.assertIn("| `ios` | ❌ missing | — | — |", md)
    self.assertIn("### Failures", md)
    self.assertIn("- **`android`**: No scheduled runs in the past 24 hours.", md)
    self.assertIn("- **`ios`**: No scheduled runs in the past 24 hours.", md)

  def test_with_failing_runs_and_missing_workflows(self) -> None:
    runs = [
      WorkflowRun(
        id=102,
        name="test-suite",
        status="completed",
        conclusion="failure",
        created_at_utc=datetime(2026, 11, 2, 15, 0, 0, tzinfo=timezone.utc),
        url="http://example.com/102",
        failed_jobs=(
          FailedJob(
            name="unit-tests",
            url="http://example.com/job/1",
            failed_steps=(FailedStep(name="run tests", number=4, conclusion="failure"),),
          ),
        ),
      ),
    ]
    missing = [
      ScheduledWorkflow(name="android", file_path="/path/to/android.yml"),
    ]
    md = format_summary_markdown(
      runs=runs, missing_workflows=missing, tz=self.tz, now_utc=self.now_utc
    )
    self.assertIn("❌ 0 / 2 Passed (0% Success Rate)", md)
    self.assertIn("### Failures", md)
    self.assertIn("- **`test-suite`** ([Run #102](http://example.com/102)):", md)
    self.assertIn("Job [`unit-tests`](http://example.com/job/1): failed at `run tests`", md)
    self.assertIn("- **`android`**: No scheduled runs in the past 24 hours.", md)

  def test_table_sorted_alphabetically(self) -> None:
    runs = [
      WorkflowRun(
        id=1,
        name="zulu",
        status="completed",
        conclusion="success",
        created_at_utc=datetime(2026, 11, 2, 15, 0, 0, tzinfo=timezone.utc),
        url="http://example.com/1",
      ),
      WorkflowRun(
        id=2,
        name="bravo",
        status="completed",
        conclusion="success",
        created_at_utc=datetime(2026, 11, 2, 15, 0, 0, tzinfo=timezone.utc),
        url="http://example.com/2",
      ),
    ]
    missing = [
      ScheduledWorkflow(name="alpha", file_path="/path/to/alpha.yml"),
      ScheduledWorkflow(name="charlie", file_path="/path/to/charlie.yml"),
    ]
    md = format_summary_markdown(
      runs=runs, missing_workflows=missing, tz=self.tz, now_utc=self.now_utc
    )
    table_lines = [line for line in md.splitlines() if line.startswith("| `")]
    expected_order = [
      "| `alpha` | ❌ missing | — | — |",
      "| `bravo` | ✅ success | 2026-11-02 10:00:00 | [Run #2](http://example.com/2) |",
      "| `charlie` | ❌ missing | — | — |",
      "| `zulu` | ✅ success | 2026-11-02 10:00:00 | [Run #1](http://example.com/1) |",
    ]
    self.assertEqual(table_lines, expected_order)


if __name__ == "__main__":
  unittest.main()
