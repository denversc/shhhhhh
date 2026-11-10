#!/usr/bin/env python3
"""
release_tool.py - Helpers for the unified release GitHub Actions workflow.

Subcommands:
  apk-info       Read the package name, versionCode, and versionName of an APK using `aapt2`,
                 validate them, and emit them as GitHub Actions step outputs.
  cert-digest    Verify an APK's signature using `apksigner` and emit the SHA-256 digest of its
                 (sole) signing certificate as a GitHub Actions step output.
  ios-info       Read and validate the Info.plist inside an iOS simulator `.app.zip` and unsigned
                 `.ipa`, verify they match, and emit them as GitHub Actions step outputs.
  check-version  Verify Android and iOS versions match, do not collide with an existing tag or
                 release, and strictly advance over the highest existing GitHub release (including
                 drafts).
  release-notes  Render the unified Markdown body for the GitHub release.

This script intentionally depends only on the Python standard library so that the release workflow
does not need to install any third-party packages.
"""

from __future__ import annotations

import argparse
import json
import os
import plistlib
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any, ClassVar, cast, override

if TYPE_CHECKING:
  from collections.abc import Callable, Mapping, Sequence

GITHUB_API_URL = "https://api.github.com"
USER_AGENT = "shhhhhh-release-tool"
IOS_SIMULATOR_PLATFORM = "iphonesimulator"
IOS_DEVICE_PLATFORM = "iphoneos"


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


def ios_simulator_zip_asset_name(asset_prefix: str, version: SemVer) -> str:
  return f"{asset_prefix}-{version.tag}-ios-simulator.app.zip"


def ios_ipa_asset_name(asset_prefix: str, version: SemVer) -> str:
  return f"{asset_prefix}-{version.tag}-ios-unsigned.ipa"


def ios_dsym_zip_asset_name(asset_prefix: str, version: SemVer) -> str:
  return f"{asset_prefix}-{version.tag}-ios-dSYMs.zip"


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
    msg = f"aapt2 badging `package:` line is missing attribute(s): {', '.join(missing)}"
    raise ReleaseToolError(msg)

  version_code_str = attrs["versionCode"]
  if not re.fullmatch(r"[1-9][0-9]*", version_code_str):
    msg = f"invalid versionCode in APK: {version_code_str!r}"
    raise ReleaseToolError(msg)

  return ApkInfo(
    package_name=attrs["name"],
    version_code=int(version_code_str),
    version_name=attrs["versionName"],
  )


def read_apk_info(aapt2: Path, apk: Path) -> ApkInfo:
  result = subprocess.run(  # noqa: S603 - arguments are tool paths and file paths from the workflow
    [str(aapt2), "dump", "badging", str(apk)],
    check=True,
    capture_output=True,
    text=True,
  )
  return parse_badging(result.stdout)


# apksigner labels the certificate lines differently depending on which signature schemes are
# present: "Signer #1 certificate SHA-256 digest: ..." when a v1/v2 signature is present, but
# "V3.0 Signer: certificate SHA-256 digest: ..." (or "V3.1 Signer: ...") when the APK only has
# v3+ signatures, which is the case when minSdk >= 28 because apksigner then skips v1 and v2.
_CERT_DIGEST_LINE = re.compile(
  r"^(?:Signer #[0-9]+ |V[0-9]+(?:\.[0-9]+)* Signer(?: #[0-9]+)?: )"
  r"certificate SHA-256 digest: (?P<digest>[0-9a-fA-F]{64})$",
  re.MULTILINE,
)
_SIGNER_COUNT_LINE = re.compile(r"^Number of signers: (?P<count>[0-9]+)$", re.MULTILINE)


def parse_cert_digest(apksigner_output: str) -> str:
  """
  Parse the output of `apksigner verify --verbose --print-certs`.

  Returns the lowercase hex SHA-256 digest of the signing certificate, requiring that there is
  exactly one signer. The digest may be reported more than once (e.g. once per signature scheme);
  all reported digests must be identical.
  """
  count_match = _SIGNER_COUNT_LINE.search(apksigner_output)
  if count_match is None:
    raise ReleaseToolError("`Number of signers:` line not found in apksigner output")
  if int(count_match.group("count")) != 1:
    msg = f"expected exactly 1 signer, but apksigner reported {count_match.group('count')}"
    raise ReleaseToolError(msg)

  digests = {m.group("digest").lower() for m in _CERT_DIGEST_LINE.finditer(apksigner_output)}
  if len(digests) != 1:
    msg = (
      "expected exactly 1 distinct certificate SHA-256 digest in apksigner output, "
      f"found {len(digests)}"
    )
    raise ReleaseToolError(msg)
  return digests.pop()


def verify_and_read_cert_digest(apksigner: Path, apk: Path) -> str:
  result = subprocess.run(  # noqa: S603 - arguments are tool paths and file paths from the workflow
    [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
    check=False,
    capture_output=True,
    text=True,
  )
  print(result.stdout, end="")
  print(result.stderr, end="", file=sys.stderr)
  if result.returncode != 0:
    msg = f"apksigner verify failed with exit code {result.returncode}"
    raise ReleaseToolError(msg)
  return parse_cert_digest(result.stdout)


def format_fingerprint(hex_digest: str) -> str:
  """Format a hex digest as colon-separated uppercase byte pairs (`AB:CD:...`)."""
  upper = hex_digest.upper()
  return ":".join(upper[i : i + 2] for i in range(0, len(upper), 2))


# ---------------------------------------------------------------------------------------------------
# iOS bundle inspection (zipfile / plistlib)
# ---------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class IosBundleInfo:
  bundle_id: str
  version_code: int
  version_name: str
  platform_name: str


_SIMULATOR_INFO_PLIST_RE = re.compile(r"^[^/]+\.app/Info\.plist$")
_IPA_INFO_PLIST_RE = re.compile(r"^Payload/[^/]+\.app/Info\.plist$")


def parse_ios_info_plist(data: bytes) -> IosBundleInfo:
  """Parse an iOS `Info.plist` (XML or binary) into `IosBundleInfo`."""
  try:
    raw = plistlib.loads(data)
  except Exception as err:
    msg = f"failed to parse Info.plist: {err}"
    raise ReleaseToolError(msg) from err

  if not isinstance(raw, dict):
    raise ReleaseToolError("Info.plist root object must be a dictionary")

  plist = cast("dict[str, Any]", raw)
  required_keys = (
    "CFBundleIdentifier",
    "CFBundleShortVersionString",
    "CFBundleVersion",
    "DTPlatformName",
  )
  missing = [key for key in required_keys if key not in plist]
  if missing:
    msg = f"Info.plist is missing key(s): {', '.join(missing)}"
    raise ReleaseToolError(msg)

  bundle_id = plist["CFBundleIdentifier"]
  version_name = plist["CFBundleShortVersionString"]
  version_code_raw = plist["CFBundleVersion"]
  platform_name = plist["DTPlatformName"]

  for key, value in (
    ("CFBundleIdentifier", bundle_id),
    ("CFBundleShortVersionString", version_name),
    ("CFBundleVersion", version_code_raw),
    ("DTPlatformName", platform_name),
  ):
    if not isinstance(value, str) or not value:
      msg = f"Info.plist key {key} must be a non-empty string, got: {value!r}"
      raise ReleaseToolError(msg)

  if not re.fullmatch(r"[1-9][0-9]*", version_code_raw):
    msg = f"invalid CFBundleVersion in Info.plist: {version_code_raw!r}"
    raise ReleaseToolError(msg)

  return IosBundleInfo(
    bundle_id=bundle_id,
    version_code=int(version_code_raw),
    version_name=version_name,
    platform_name=platform_name,
  )


def read_ios_archive_info(archive_path: Path, *, expected_platform: str) -> IosBundleInfo:
  """Read and validate the app `Info.plist` inside a simulator `.app.zip` or `.ipa`."""
  if expected_platform == IOS_SIMULATOR_PLATFORM:
    pattern = _SIMULATOR_INFO_PLIST_RE
    archive_desc = "simulator .app.zip"
  elif expected_platform == IOS_DEVICE_PLATFORM:
    pattern = _IPA_INFO_PLIST_RE
    archive_desc = ".ipa"
  else:
    msg = f"unsupported expected iOS platform: {expected_platform!r}"
    raise ReleaseToolError(msg)

  try:
    with zipfile.ZipFile(archive_path) as zf:
      matches = [name for name in zf.namelist() if pattern.fullmatch(name)]
      if len(matches) != 1:
        msg = (
          f"expected exactly 1 app Info.plist in {archive_desc} {archive_path.name}, "
          f"found {len(matches)}: {matches!r}"
        )
        raise ReleaseToolError(msg)
      plist_bytes = zf.read(matches[0])
  except zipfile.BadZipFile as err:
    msg = f"{archive_path.name} is not a valid zip archive: {err}"
    raise ReleaseToolError(msg) from err

  info = parse_ios_info_plist(plist_bytes)
  if info.platform_name != expected_platform:
    msg = (
      f"{archive_path.name} DTPlatformName {info.platform_name!r} does not match "
      f"expected {expected_platform!r}"
    )
    raise ReleaseToolError(msg)
  return info


def validate_ios_pair(
  simulator_info: IosBundleInfo,
  ipa_info: IosBundleInfo,
  expected_bundle_id: str,
) -> SemVer:
  """Validate that simulator and device builds share identity/version and strict SemVer."""
  for label, info in (("simulator .app.zip", simulator_info), ("unsigned .ipa", ipa_info)):
    if info.bundle_id != expected_bundle_id:
      msg = (
        f"iOS {label} bundle ID {info.bundle_id!r} does not match expected {expected_bundle_id!r}"
      )
      raise ReleaseToolError(msg)

  if simulator_info.version_name != ipa_info.version_name:
    msg = (
      f"simulator MARKETING_VERSION {simulator_info.version_name!r} does not match "
      f"IPA MARKETING_VERSION {ipa_info.version_name!r}"
    )
    raise ReleaseToolError(msg)
  if simulator_info.version_code != ipa_info.version_code:
    msg = (
      f"simulator CFBundleVersion {simulator_info.version_code} does not match "
      f"IPA CFBundleVersion {ipa_info.version_code}"
    )
    raise ReleaseToolError(msg)

  version = SemVer.parse(ipa_info.version_name)
  if version is None:
    msg = (
      f"MARKETING_VERSION {ipa_info.version_name!r} is not a plain MAJOR.MINOR.PATCH "
      "semantic version (three non-negative integers without leading zeros, and no suffix "
      "such as '-dev')"
    )
    raise ReleaseToolError(msg)
  return version


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

  def _get_json(self, path: str) -> object:
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
        msg = f"redirect without Location header for asset {asset.name}"
        raise ReleaseToolError(msg) from err

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
  releases: Sequence[Release], warn: Callable[[str], None] | None = None
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
      msg = (
        f"a {kind} with tag {new_version.tag} already exists; "
        "bump versionName (or delete the existing draft) and try again"
      )
      raise ReleaseToolError(msg)

  highest = find_highest_release(releases, warn=_warn)
  if highest is not None and new_version <= highest.version:
    msg = (
      f"versionName {new_version} must be strictly greater than the highest existing release "
      f"version {highest.version} (tag {highest.release.tag_name})"
    )
    raise ReleaseToolError(msg)
  return highest


def check_version_code(
  new_version_code: int,
  previous_version_code: int,
  previous_tag: str,
  *,
  label: str = "versionCode",
) -> None:
  if new_version_code <= previous_version_code:
    msg = (
      f"{label} {new_version_code} must be strictly greater than {label} "
      f"{previous_version_code} of the previous release {previous_tag}"
    )
    raise ReleaseToolError(msg)


def check_platform_parity(
  *,
  android_version: SemVer,
  android_version_code: int,
  ios_version: SemVer,
  ios_version_code: int,
) -> None:
  if ios_version != android_version:
    msg = f"iOS version ({ios_version}) does not match Android versionName ({android_version})"
    raise ReleaseToolError(msg)
  if ios_version_code != android_version_code:
    msg = (
      f"iOS CFBundleVersion ({ios_version_code}) does not match Android versionCode "
      f"({android_version_code})"
    )
    raise ReleaseToolError(msg)


def find_apk_asset(release: Release, asset_prefix: str, version: SemVer) -> ReleaseAsset:
  expected_name = apk_asset_name(asset_prefix, version)
  for asset in release.assets:
    if asset.name == expected_name:
      return asset
  msg = (
    f"release {release.tag_name} has no asset named {expected_name}; "
    f"found: {', '.join(a.name for a in release.assets) or '(none)'}"
  )
  raise ReleaseToolError(msg)


def find_optional_ios_asset(
  release: Release, asset_prefix: str, version: SemVer
) -> tuple[ReleaseAsset, str] | None:
  """Return `(asset, expected_platform)` if `release` contains a known iOS build asset."""
  by_name = {asset.name: asset for asset in release.assets}
  ipa_name = ios_ipa_asset_name(asset_prefix, version)
  if ipa_name in by_name:
    return by_name[ipa_name], IOS_DEVICE_PLATFORM
  sim_name = ios_simulator_zip_asset_name(asset_prefix, version)
  if sim_name in by_name:
    return by_name[sim_name], IOS_SIMULATOR_PLATFORM
  return None


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
  ios_simulator_zip_name: str
  ios_simulator_zip_sha256: str
  ios_ipa_name: str
  ios_ipa_sha256: str


def format_release_notes(notes: ReleaseNotesInput) -> str:
  short_commit = notes.commit[:12]
  return (
    f"Built from commit [`{short_commit}`](https://github.com/{notes.repo}/commit/{notes.commit})"
    f" (versionCode / CFBundleVersion {notes.version_code}).\n"
    "\n"
    "### Verification\n"
    "\n"
    "| | |\n"
    "| --- | --- |\n"
    f"| `{notes.apk_name}` SHA-256 | `{notes.apk_sha256.lower()}` |\n"
    f"| Android signing certificate SHA-256 | `{format_fingerprint(notes.cert_sha256)}` |\n"
    f"| `{notes.ios_simulator_zip_name}` SHA-256 | `{notes.ios_simulator_zip_sha256.lower()}` |\n"
    f"| `{notes.ios_ipa_name}` SHA-256 | `{notes.ios_ipa_sha256.lower()}` |\n"
    "\n"
    "Verify the build provenance attestations with:\n"
    "\n"
    "```sh\n"
    f"gh attestation verify {notes.apk_name} --repo {notes.repo}\n"
    f"gh attestation verify {notes.ios_simulator_zip_name} --repo {notes.repo}\n"
    f"gh attestation verify {notes.ios_ipa_name} --repo {notes.repo}\n"
    "```\n"
    "\n"
    "### iOS Testing Notes\n"
    "\n"
    f"- **iOS Simulator (macOS + Xcode):** Unzip `{notes.ios_simulator_zip_name}` and drag "
    "`shhhhhh.app` onto an open iOS Simulator window (or run "
    "`xcrun simctl install booted shhhhhh.app`).\n"
    f"- **Physical iPhone (unsigned):** `{notes.ios_ipa_name}` is unsigned and must be re-signed "
    "with your own Apple ID using a sideloading tool (such as Sideloadly, AltStore, or SideStore) "
    "or Xcode before installing.\n"
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
      msg = f"output {key!r} must not contain newlines"
      raise ReleaseToolError(msg)
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
    msg = (
      f"APK package name {info.package_name!r} does not match expected {args.expected_package!r}"
    )
    raise ReleaseToolError(msg)

  version = SemVer.parse(info.version_name)
  if version is None:
    msg = (
      f"versionName {info.version_name!r} is not a plain MAJOR.MINOR.PATCH semantic version "
      "(three non-negative integers without leading zeros, and no suffix such as '-dev')"
    )
    raise ReleaseToolError(msg)

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


def cmd_ios_info(args: argparse.Namespace) -> None:
  sim_info = read_ios_archive_info(args.simulator_zip, expected_platform=IOS_SIMULATOR_PLATFORM)
  ipa_info = read_ios_archive_info(args.ipa, expected_platform=IOS_DEVICE_PLATFORM)
  print(
    f"iOS simulator zip {args.simulator_zip}: bundle={sim_info.bundle_id} "
    f"CFBundleVersion={sim_info.version_code} CFBundleShortVersionString={sim_info.version_name}",
    file=sys.stderr,
  )
  print(
    f"iOS unsigned IPA {args.ipa}: bundle={ipa_info.bundle_id} "
    f"CFBundleVersion={ipa_info.version_code} CFBundleShortVersionString={ipa_info.version_name}",
    file=sys.stderr,
  )

  version = validate_ios_pair(sim_info, ipa_info, args.expected_bundle_id)
  write_github_outputs(
    {
      "version-name": str(version),
      "version-code": str(ipa_info.version_code),
      "tag": version.tag,
      "simulator-zip-name": ios_simulator_zip_asset_name(args.asset_prefix, version),
      "ipa-name": ios_ipa_asset_name(args.asset_prefix, version),
      "dsym-zip-name": ios_dsym_zip_asset_name(args.asset_prefix, version),
    }
  )


def cmd_check_version(args: argparse.Namespace) -> None:
  new_version = SemVer.parse(args.version_name)
  if new_version is None:
    msg = f"versionName {args.version_name!r} is not a plain MAJOR.MINOR.PATCH semantic version"
    raise ReleaseToolError(msg)

  ios_version = SemVer.parse(args.ios_version_name)
  if ios_version is None:
    msg = (
      f"iOS MARKETING_VERSION {args.ios_version_name!r} is not a plain MAJOR.MINOR.PATCH "
      "semantic version"
    )
    raise ReleaseToolError(msg)

  check_platform_parity(
    android_version=new_version,
    android_version_code=args.version_code,
    ios_version=ios_version,
    ios_version_code=args.ios_version_code,
  )

  client = GitHubClient(args.repo, args.token)

  if client.tag_exists(new_version.tag):
    msg = f"tag {new_version.tag} already exists in {args.repo}"
    raise ReleaseToolError(msg)

  releases = client.list_releases()
  print(f"Found {len(releases)} existing release(s) in {args.repo}.", file=sys.stderr)

  highest = check_new_version_against_releases(new_version, releases)
  if highest is None:
    print("No previous releases; skipping versionCode comparison.", file=sys.stderr)
    return

  asset = find_apk_asset(highest.release, args.asset_prefix, highest.version)
  with tempfile.TemporaryDirectory() as tmp_dir:
    tmp_root = Path(tmp_dir)
    previous_apk = tmp_root / asset.name
    print(f"Downloading {asset.name} from release {highest.release.tag_name}...", file=sys.stderr)
    client.download_asset(asset, previous_apk)
    previous_info = read_apk_info(args.aapt2, previous_apk)
    check_version_code(args.version_code, previous_info.version_code, highest.release.tag_name)

    ios_asset = find_optional_ios_asset(highest.release, args.asset_prefix, highest.version)
    if ios_asset is None:
      print(
        f"Previous release {highest.release.tag_name} has no iOS asset; "
        "skipping previous iOS build number comparison.",
        file=sys.stderr,
      )
    else:
      prev_ios_asset, prev_ios_platform = ios_asset
      previous_ios_path = tmp_root / prev_ios_asset.name
      print(
        f"Downloading {prev_ios_asset.name} from release {highest.release.tag_name}...",
        file=sys.stderr,
      )
      client.download_asset(prev_ios_asset, previous_ios_path)
      previous_ios_info = read_ios_archive_info(
        previous_ios_path, expected_platform=prev_ios_platform
      )
      check_version_code(
        args.ios_version_code,
        previous_ios_info.version_code,
        highest.release.tag_name,
        label="CFBundleVersion",
      )

  print(
    f"OK: {new_version} (versionCode / CFBundleVersion {args.version_code}) > "
    f"{highest.version} (Android versionCode {previous_info.version_code})",
    file=sys.stderr,
  )


def cmd_release_notes(args: argparse.Namespace) -> None:
  version = SemVer.parse(args.version_name)
  if version is None:
    msg = f"invalid versionName: {args.version_name!r}"
    raise ReleaseToolError(msg)
  if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
    msg = f"invalid commit SHA: {args.commit!r}"
    raise ReleaseToolError(msg)
  for name, value in (
    ("apk-sha256", args.apk_sha256),
    ("cert-sha256", args.cert_sha256),
    ("ios-simulator-zip-sha256", args.ios_simulator_zip_sha256),
    ("ios-ipa-sha256", args.ios_ipa_sha256),
  ):
    if not re.fullmatch(r"[0-9a-fA-F]{64}", value):
      msg = f"invalid --{name}: {value!r}"
      raise ReleaseToolError(msg)

  body = format_release_notes(
    ReleaseNotesInput(
      repo=args.repo,
      commit=args.commit,
      version=version,
      version_code=args.version_code,
      apk_name=args.apk_name,
      apk_sha256=args.apk_sha256,
      cert_sha256=args.cert_sha256,
      ios_simulator_zip_name=args.ios_simulator_zip_name,
      ios_simulator_zip_sha256=args.ios_simulator_zip_sha256,
      ios_ipa_name=args.ios_ipa_name,
      ios_ipa_sha256=args.ios_ipa_sha256,
    )
  )
  args.output.write_text(body, encoding="utf-8")
  print(body)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
  parser = argparse.ArgumentParser(description="Helpers for the unified release workflow.")
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

  ios_info = subparsers.add_parser(
    "ios-info", help="Read and validate iOS simulator .app.zip and unsigned .ipa version info."
  )
  ios_info.add_argument(
    "--simulator-zip", type=Path, required=True, help="Path to the iOS simulator .app.zip."
  )
  ios_info.add_argument("--ipa", type=Path, required=True, help="Path to the unsigned .ipa.")
  ios_info.add_argument("--expected-bundle-id", required=True, help="Expected CFBundleIdentifier.")
  ios_info.add_argument("--asset-prefix", required=True, help="Release asset name prefix.")
  ios_info.set_defaults(func=cmd_ios_info)

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
  check_version.add_argument("--version-name", required=True, help="New Android versionName.")
  check_version.add_argument(
    "--version-code", type=int, required=True, help="New Android versionCode."
  )
  check_version.add_argument("--ios-version-name", required=True, help="New iOS MARKETING_VERSION.")
  check_version.add_argument(
    "--ios-version-code", type=int, required=True, help="New iOS CURRENT_PROJECT_VERSION."
  )
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
    "--ios-simulator-zip-name", required=True, help="iOS simulator .app.zip asset file name."
  )
  release_notes.add_argument(
    "--ios-simulator-zip-sha256", required=True, help="SHA-256 of the iOS simulator .app.zip."
  )
  release_notes.add_argument(
    "--ios-ipa-name", required=True, help="iOS unsigned .ipa asset file name."
  )
  release_notes.add_argument(
    "--ios-ipa-sha256", required=True, help="SHA-256 of the iOS unsigned .ipa."
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
