#!/usr/bin/env python3
"""
release_tool.py - Helpers for the android-release GitHub Actions workflow.

Subcommands:
  apk-info       Read the package name, versionCode, and versionName of an APK using `aapt2`,
                 validate them, and emit them as GitHub Actions step outputs.
  cert-digest    Verify an APK's signature using `apksigner` and emit the SHA-256 digest of its
                 (sole) signing certificate as a GitHub Actions step output.
  check-version  Verify that a new version does not collide with an existing tag or release and
                 is strictly greater (both versionName and versionCode) than the highest existing
                 GitHub release, including drafts.
  release-notes  Render the Markdown body for the GitHub release.

This script intentionally depends only on the Python standard library so that the release workflow
does not need to install any third-party packages.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any, ClassVar, cast, override

if TYPE_CHECKING:
  from collections.abc import Mapping, Sequence

GITHUB_API_URL = "https://api.github.com"
USER_AGENT = "shhhhhh-release-tool"


class ReleaseToolError(Exception):
  """An error that should be reported to the user and cause a non-zero exit."""


# ---------------------------------------------------------------------------------------------------
# Semantic versions
# ---------------------------------------------------------------------------------------------------


@dataclass(frozen=True, order=True)
class SemVer:
  """A plain `MAJOR.MINOR.PATCH` semantic version with no pre-release or build suffix."""

  major: int
  minor: int
  patch: int

  # Deliberately uses [0-9] rather than \d so that non-ASCII digits are rejected.
  _PATTERN: ClassVar[re.Pattern[str]] = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
  )

  @classmethod
  def parse(cls, text: str) -> SemVer | None:
    """Parse `text`, returning `None` if it is not exactly `MAJOR.MINOR.PATCH`."""
    match = cls._PATTERN.fullmatch(text)
    if match is None:
      return None
    return cls(int(match.group(1)), int(match.group(2)), int(match.group(3)))

  @override
  def __str__(self) -> str:
    return f"{self.major}.{self.minor}.{self.patch}"

  @property
  def tag(self) -> str:
    return f"v{self}"


def parse_tag(tag: str) -> SemVer | None:
  """Parse a release tag of the form `v<MAJOR.MINOR.PATCH>`, returning `None` otherwise."""
  if not tag.startswith("v"):
    return None
  return SemVer.parse(tag[1:])


def apk_asset_name(asset_prefix: str, version: SemVer) -> str:
  return f"{asset_prefix}-{version.tag}.apk"


# ---------------------------------------------------------------------------------------------------
# APK inspection (aapt2 / apksigner)
# ---------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class ApkInfo:
  package_name: str
  version_code: int
  version_name: str


_BADGING_PACKAGE_LINE = re.compile(r"^package:(?P<attrs>.*)$", re.MULTILINE)
_BADGING_ATTR = re.compile(r"(?P<key>[A-Za-z]+)='(?P<value>[^']*)'")


def parse_badging(badging: str) -> ApkInfo:
  """Parse the output of `aapt2 dump badging`."""
  match = _BADGING_PACKAGE_LINE.search(badging)
  if match is None:
    raise ReleaseToolError("`package:` line not found in aapt2 badging output")

  attrs = {m.group("key"): m.group("value") for m in _BADGING_ATTR.finditer(match.group("attrs"))}

  missing = [key for key in ("name", "versionCode", "versionName") if key not in attrs]
  if missing:
    raise ReleaseToolError(
      f"aapt2 badging `package:` line is missing attribute(s): {', '.join(missing)}"
    )

  version_code_str = attrs["versionCode"]
  if not re.fullmatch(r"[1-9][0-9]*", version_code_str):
    raise ReleaseToolError(f"invalid versionCode in APK: {version_code_str!r}")

  return ApkInfo(
    package_name=attrs["name"],
    version_code=int(version_code_str),
    version_name=attrs["versionName"],
  )


def read_apk_info(aapt2: Path, apk: Path) -> ApkInfo:
  result = subprocess.run(
    [str(aapt2), "dump", "badging", str(apk)],
    check=True,
    capture_output=True,
    text=True,
  )
  return parse_badging(result.stdout)


_CERT_DIGEST_LINE = re.compile(
  r"^Signer #(?P<index>[0-9]+) certificate SHA-256 digest: (?P<digest>[0-9a-fA-F]{64})$",
  re.MULTILINE,
)
_SIGNER_COUNT_LINE = re.compile(r"^Number of signers: (?P<count>[0-9]+)$", re.MULTILINE)


def parse_cert_digest(apksigner_output: str) -> str:
  """
  Parse the output of `apksigner verify --verbose --print-certs`.

  Returns the lowercase hex SHA-256 digest of the signing certificate, requiring that there is
  exactly one signer.
  """
  count_match = _SIGNER_COUNT_LINE.search(apksigner_output)
  if count_match is None:
    raise ReleaseToolError("`Number of signers:` line not found in apksigner output")
  if int(count_match.group("count")) != 1:
    raise ReleaseToolError(
      f"expected exactly 1 signer, but apksigner reported {count_match.group('count')}"
    )

  digests = _CERT_DIGEST_LINE.findall(apksigner_output)
  if len(digests) != 1:
    raise ReleaseToolError(
      f"expected exactly 1 certificate SHA-256 digest in apksigner output, found {len(digests)}"
    )
  _index, digest = cast("tuple[str, str]", digests[0])
  return digest.lower()


def verify_and_read_cert_digest(apksigner: Path, apk: Path) -> str:
  result = subprocess.run(
    [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
    check=False,
    capture_output=True,
    text=True,
  )
  print(result.stdout, end="")
  print(result.stderr, end="", file=sys.stderr)
  if result.returncode != 0:
    raise ReleaseToolError(f"apksigner verify failed with exit code {result.returncode}")
  return parse_cert_digest(result.stdout)


def format_fingerprint(hex_digest: str) -> str:
  """Format a hex digest as colon-separated uppercase byte pairs (`AB:CD:...`)."""
  upper = hex_digest.upper()
  return ":".join(upper[i : i + 2] for i in range(0, len(upper), 2))


# ---------------------------------------------------------------------------------------------------
# GitHub REST API
# ---------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class ReleaseAsset:
  name: str
  url: str


@dataclass(frozen=True)
class Release:
  tag_name: str
  draft: bool
  assets: tuple[ReleaseAsset, ...]


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
  @override
  def redirect_request(
    self,
    req: urllib.request.Request,
    fp: Any,
    code: int,
    msg: str,
    headers: Any,
    newurl: str,
  ) -> urllib.request.Request | None:
    return None


class GitHubClient:
  def __init__(self, repo: str, token: str, api_url: str = GITHUB_API_URL) -> None:
    self.repo = repo
    self.token = token
    self.api_url = api_url.rstrip("/")

  def _headers(self, accept: str) -> dict[str, str]:
    return {
      "Authorization": f"Bearer {self.token}",
      "Accept": accept,
      "User-Agent": USER_AGENT,
      "X-GitHub-Api-Version": "2022-11-28",
    }

  def _get_json(self, path: str) -> Any:
    req = urllib.request.Request(
      f"{self.api_url}{path}", headers=self._headers("application/vnd.github+json")
    )
    with urllib.request.urlopen(req) as resp:
      return json.loads(resp.read().decode("utf-8"))

  def list_releases(self) -> list[Release]:
    """List all releases, including drafts (drafts require `contents: write`)."""
    releases: list[Release] = []
    page = 1
    while True:
      data = self._get_json(f"/repos/{self.repo}/releases?per_page=100&page={page}")
      if not isinstance(data, list) or not data:
        break
      for item in cast("list[dict[str, Any]]", data):
        assets = tuple(
          ReleaseAsset(name=str(a["name"]), url=str(a["url"]))
          for a in cast("list[dict[str, Any]]", item.get("assets", []))
        )
        releases.append(
          Release(tag_name=str(item["tag_name"]), draft=bool(item.get("draft")), assets=assets)
        )
      page += 1
    return releases

  def tag_exists(self, tag: str) -> bool:
    quoted = urllib.parse.quote(tag, safe="")
    try:
      self._get_json(f"/repos/{self.repo}/git/ref/tags/{quoted}")
    except urllib.error.HTTPError as err:
      if err.code == 404:  # noqa: PLR2004
        return False
      raise
    return True

  def download_asset(self, asset: ReleaseAsset, dest: Path) -> None:
    """
    Download a release asset.

    The asset API endpoint redirects to a pre-signed storage URL; the redirect is followed manually
    so that the `Authorization` header is not forwarded to the storage host.
    """
    opener = urllib.request.build_opener(_NoRedirectHandler)
    req = urllib.request.Request(asset.url, headers=self._headers("application/octet-stream"))
    try:
      with opener.open(req) as resp:
        dest.write_bytes(resp.read())
        return
    except urllib.error.HTTPError as err:
      if err.code not in {301, 302, 303, 307, 308}:
        raise
      location = err.headers.get("Location")
      if not location:
        raise ReleaseToolError(f"redirect without Location header for asset {asset.name}") from err

    redirect_req = urllib.request.Request(location, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(redirect_req) as resp:
      dest.write_bytes(resp.read())


# ---------------------------------------------------------------------------------------------------
# Version checks
# ---------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class PreviousRelease:
  version: SemVer
  release: Release


def find_highest_release(
  releases: Sequence[Release], warn: Any = None
) -> PreviousRelease | None:
  """
  Find the release whose tag is the highest `v<MAJOR.MINOR.PATCH>` version.

  Releases whose tags are not of that form are ignored (with a warning, if `warn` is given).
  """
  highest: PreviousRelease | None = None
  for release in releases:
    version = parse_tag(release.tag_name)
    if version is None:
      if warn is not None:
        warn(f"ignoring release with non-semver tag: {release.tag_name!r}")
      continue
    if highest is None or version > highest.version:
      highest = PreviousRelease(version=version, release=release)
  return highest


def check_new_version_against_releases(
  new_version: SemVer, releases: Sequence[Release]
) -> PreviousRelease | None:
  """
  Verify `new_version` is not already released and is strictly greater than all releases.

  Returns the highest existing release (or `None` if there are none).
  """
  for release in releases:
    if release.tag_name == new_version.tag:
      kind = "draft release" if release.draft else "release"
      raise ReleaseToolError(
        f"a {kind} with tag {new_version.tag} already exists; "
        "bump versionName (or delete the existing draft) and try again"
      )

  highest = find_highest_release(releases, warn=_warn)
  if highest is not None and new_version <= highest.version:
    raise ReleaseToolError(
      f"versionName {new_version} must be strictly greater than the highest existing release "
      f"version {highest.version} (tag {highest.release.tag_name})"
    )
  return highest


def check_version_code(new_version_code: int, previous_version_code: int, previous_tag: str) -> None:
  if new_version_code <= previous_version_code:
    raise ReleaseToolError(
      f"versionCode {new_version_code} must be strictly greater than versionCode "
      f"{previous_version_code} of the previous release {previous_tag}"
    )


def find_apk_asset(release: Release, asset_prefix: str, version: SemVer) -> ReleaseAsset:
  expected_name = apk_asset_name(asset_prefix, version)
  for asset in release.assets:
    if asset.name == expected_name:
      return asset
  raise ReleaseToolError(
    f"release {release.tag_name} has no asset named {expected_name}; "
    f"found: {', '.join(a.name for a in release.assets) or '(none)'}"
  )


# ---------------------------------------------------------------------------------------------------
# Release notes
# ---------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class ReleaseNotesInput:
  repo: str
  commit: str
  version: SemVer
  version_code: int
  apk_name: str
  apk_sha256: str
  cert_sha256: str


def format_release_notes(notes: ReleaseNotesInput) -> str:
  short_commit = notes.commit[:12]
  return (
    f"Built from commit [`{short_commit}`](https://github.com/{notes.repo}/commit/{notes.commit})"
    f" (versionCode {notes.version_code}).\n"
    "\n"
    "### Verification\n"
    "\n"
    "| | |\n"
    "| --- | --- |\n"
    f"| `{notes.apk_name}` SHA-256 | `{notes.apk_sha256.lower()}` |\n"
    f"| Signing certificate SHA-256 | `{format_fingerprint(notes.cert_sha256)}` |\n"
    "\n"
    "Verify the build provenance attestation with:\n"
    "\n"
    "```sh\n"
    f"gh attestation verify {notes.apk_name} --repo {notes.repo}\n"
    "```\n"
  )


# ---------------------------------------------------------------------------------------------------
# GitHub Actions integration
# ---------------------------------------------------------------------------------------------------


def _warn(message: str) -> None:
  print(f"::warning::{message}", file=sys.stderr)


def write_github_outputs(outputs: Mapping[str, str]) -> None:
  """Write step outputs to `$GITHUB_OUTPUT` (if set) and always echo them to stdout."""
  for key, value in outputs.items():
    if "\n" in value or "\r" in value:
      raise ReleaseToolError(f"output {key!r} must not contain newlines")
    print(f"{key}={value}")

  output_path = os.environ.get("GITHUB_OUTPUT")
  if output_path:
    with open(output_path, "a", encoding="utf-8") as f:
      for key, value in outputs.items():
        f.write(f"{key}={value}\n")


# ---------------------------------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------------------------------


def cmd_apk_info(args: argparse.Namespace) -> None:
  info = read_apk_info(args.aapt2, args.apk)
  print(
    f"APK {args.apk}: package={info.package_name} versionCode={info.version_code} "
    f"versionName={info.version_name}",
    file=sys.stderr,
  )

  if info.package_name != args.expected_package:
    raise ReleaseToolError(
      f"APK package name {info.package_name!r} does not match expected {args.expected_package!r}"
    )

  version = SemVer.parse(info.version_name)
  if version is None:
    raise ReleaseToolError(
      f"versionName {info.version_name!r} is not a plain MAJOR.MINOR.PATCH semantic version "
      "(three non-negative integers without leading zeros, and no suffix such as '-dev')"
    )

  write_github_outputs(
    {
      "version-name": str(version),
      "version-code": str(info.version_code),
      "tag": version.tag,
      "apk-name": apk_asset_name(args.asset_prefix, version),
    }
  )


def cmd_cert_digest(args: argparse.Namespace) -> None:
  digest = verify_and_read_cert_digest(args.apksigner, args.apk)
  write_github_outputs({"cert-sha256": digest})


def cmd_check_version(args: argparse.Namespace) -> None:
  new_version = SemVer.parse(args.version_name)
  if new_version is None:
    raise ReleaseToolError(
      f"versionName {args.version_name!r} is not a plain MAJOR.MINOR.PATCH semantic version"
    )

  client = GitHubClient(args.repo, args.token)

  if client.tag_exists(new_version.tag):
    raise ReleaseToolError(f"tag {new_version.tag} already exists in {args.repo}")

  releases = client.list_releases()
  print(f"Found {len(releases)} existing release(s) in {args.repo}.", file=sys.stderr)

  highest = check_new_version_against_releases(new_version, releases)
  if highest is None:
    print("No previous releases; skipping versionCode comparison.", file=sys.stderr)
    return

  asset = find_apk_asset(highest.release, args.asset_prefix, highest.version)
  with tempfile.TemporaryDirectory() as tmp_dir:
    previous_apk = Path(tmp_dir) / asset.name
    print(f"Downloading {asset.name} from release {highest.release.tag_name}...", file=sys.stderr)
    client.download_asset(asset, previous_apk)
    previous_info = read_apk_info(args.aapt2, previous_apk)

  check_version_code(args.version_code, previous_info.version_code, highest.release.tag_name)
  print(
    f"OK: {new_version} (versionCode {args.version_code}) > {highest.version} "
    f"(versionCode {previous_info.version_code})",
    file=sys.stderr,
  )


def cmd_release_notes(args: argparse.Namespace) -> None:
  version = SemVer.parse(args.version_name)
  if version is None:
    raise ReleaseToolError(f"invalid versionName: {args.version_name!r}")
  if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
    raise ReleaseToolError(f"invalid commit SHA: {args.commit!r}")
  for name, value in (("apk-sha256", args.apk_sha256), ("cert-sha256", args.cert_sha256)):
    if not re.fullmatch(r"[0-9a-fA-F]{64}", value):
      raise ReleaseToolError(f"invalid --{name}: {value!r}")

  body = format_release_notes(
    ReleaseNotesInput(
      repo=args.repo,
      commit=args.commit,
      version=version,
      version_code=args.version_code,
      apk_name=args.apk_name,
      apk_sha256=args.apk_sha256,
      cert_sha256=args.cert_sha256,
    )
  )
  args.output.write_text(body, encoding="utf-8")
  print(body)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
  parser = argparse.ArgumentParser(description="Helpers for the android-release workflow.")
  subparsers = parser.add_subparsers(dest="command", required=True)

  apk_info = subparsers.add_parser("apk-info", help="Read and validate APK version info.")
  apk_info.add_argument("--aapt2", type=Path, required=True, help="Path to the aapt2 binary.")
  apk_info.add_argument("--apk", type=Path, required=True, help="Path to the APK.")
  apk_info.add_argument("--expected-package", required=True, help="Expected package name.")
  apk_info.add_argument("--asset-prefix", required=True, help="Release asset name prefix.")
  apk_info.set_defaults(func=cmd_apk_info)

  cert_digest = subparsers.add_parser(
    "cert-digest", help="Verify an APK signature and print its certificate SHA-256 digest."
  )
  cert_digest.add_argument(
    "--apksigner", type=Path, required=True, help="Path to the apksigner binary."
  )
  cert_digest.add_argument("--apk", type=Path, required=True, help="Path to the signed APK.")
  cert_digest.set_defaults(func=cmd_cert_digest)

  check_version = subparsers.add_parser(
    "check-version", help="Verify the new version is greater than all existing releases."
  )
  check_version.add_argument(
    "--repo",
    default=os.environ.get("GITHUB_REPOSITORY"),
    help="GitHub repository (owner/repo). Defaults to the GITHUB_REPOSITORY env var.",
  )
  check_version.add_argument(
    "--token",
    default=os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN"),
    help="GitHub token. Defaults to the GH_TOKEN or GITHUB_TOKEN env var.",
  )
  check_version.add_argument("--aapt2", type=Path, required=True, help="Path to the aapt2 binary.")
  check_version.add_argument("--version-name", required=True, help="New versionName.")
  check_version.add_argument("--version-code", type=int, required=True, help="New versionCode.")
  check_version.add_argument("--asset-prefix", required=True, help="Release asset name prefix.")
  check_version.set_defaults(func=cmd_check_version)

  release_notes = subparsers.add_parser("release-notes", help="Render the release notes body.")
  release_notes.add_argument(
    "--repo",
    default=os.environ.get("GITHUB_REPOSITORY"),
    help="GitHub repository (owner/repo). Defaults to the GITHUB_REPOSITORY env var.",
  )
  release_notes.add_argument("--commit", required=True, help="Full SHA of the released commit.")
  release_notes.add_argument("--version-name", required=True, help="versionName.")
  release_notes.add_argument("--version-code", type=int, required=True, help="versionCode.")
  release_notes.add_argument("--apk-name", required=True, help="APK asset file name.")
  release_notes.add_argument("--apk-sha256", required=True, help="SHA-256 of the APK.")
  release_notes.add_argument(
    "--cert-sha256", required=True, help="SHA-256 of the signing certificate."
  )
  release_notes.add_argument(
    "--output", type=Path, required=True, help="File to write the Markdown body to."
  )
  release_notes.set_defaults(func=cmd_release_notes)

  args = parser.parse_args(argv)
  if args.command in {"check-version", "release-notes"} and not args.repo:
    parser.error("--repo must be specified (or set the GITHUB_REPOSITORY env var)")
  if args.command == "check-version" and not args.token:
    parser.error("--token must be specified (or set the GH_TOKEN / GITHUB_TOKEN env var)")
  return args


def main(argv: list[str] | None = None) -> None:
  args = parse_args(argv)
  try:
    args.func(args)
  except ReleaseToolError as err:
    print(f"::error::{err}", file=sys.stderr)
    sys.exit(1)
  except subprocess.CalledProcessError as err:
    print(f"::error::command failed with exit code {err.returncode}: {err.cmd}", file=sys.stderr)
    if err.stderr:
      print(err.stderr, file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
  main()
