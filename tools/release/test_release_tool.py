#!/usr/bin/env python3
"""Tests for release_tool.py."""

import os
import plistlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import release_tool
from release_tool import (
  IOS_DEVICE_PLATFORM,
  IOS_SIMULATOR_PLATFORM,
  ApkInfo,
  IosBundleInfo,
  Release,
  ReleaseAsset,
  ReleaseNotesInput,
  ReleaseToolError,
  SemVer,
  apk_asset_name,
  check_new_version_against_releases,
  check_platform_parity,
  check_version_code,
  find_apk_asset,
  find_highest_release,
  find_optional_ios_asset,
  format_fingerprint,
  format_release_notes,
  ios_dsym_zip_asset_name,
  ios_ipa_asset_name,
  ios_simulator_zip_asset_name,
  parse_args,
  parse_badging,
  parse_cert_digest,
  parse_ios_info_plist,
  parse_tag,
  read_ios_archive_info,
  validate_ios_pair,
  write_github_outputs,
)

SAMPLE_BADGING = (
  "package: name='prefix924.shhhhhh' versionCode='1' versionName='0.0.1' "
  "platformBuildVersionName='17' platformBuildVersionCode='37' compileSdkVersion='37' "
  "compileSdkVersionCodename='17'\n"
  "minSdkVersion:'37'\n"
  "targetSdkVersion:'37'\n"
  "application-label:'shhhhhh'\n"
)

DIGEST = "ab" * 32

SAMPLE_APKSIGNER = (
  "Verifies\n"
  "Verified using v1 scheme (JAR signing): false\n"
  "Verified using v2 scheme (APK Signature Scheme v2): true\n"
  "Verified using v3 scheme (APK Signature Scheme v3): true\n"
  "Number of signers: 1\n"
  "Signer #1 certificate DN: CN=shhhhhh\n"
  f"Signer #1 certificate SHA-256 digest: {DIGEST}\n"
  f"Signer #1 certificate SHA-1 digest: {'cd' * 20}\n"
  f"Signer #1 certificate MD5 digest: {'ef' * 16}\n"
)

# Output for an APK with minSdk >= 28, which apksigner signs only with v3 (captured from a real
# `apksigner verify --verbose --print-certs` run with build-tools 37.0.0).
SAMPLE_APKSIGNER_V3_ONLY = (
  "Verifies\n"
  "Verified using v1 scheme (JAR signing): false\n"
  "Verified using v2 scheme (APK Signature Scheme v2): false\n"
  "Verified using v3 scheme (APK Signature Scheme v3): true\n"
  "Verified using v3.1 scheme (APK Signature Scheme v3.1): false\n"
  "Verified using v3.2 scheme (APK Signature Scheme v3.2): false\n"
  "Verified using v4 scheme (APK Signature Scheme v4): false\n"
  "Verified for SourceStamp: false\n"
  "Number of signers: 1\n"
  "V3.0 Signer: certificate DN: CN=shhhhhh\n"
  f"V3.0 Signer: certificate SHA-256 digest: {DIGEST}\n"
  f"V3.0 Signer: certificate SHA-1 digest: {'cd' * 20}\n"
  f"V3.0 Signer: certificate MD5 digest: {'ef' * 16}\n"
  "V3.0 Signer: key algorithm: RSA\n"
  "V3.0 Signer: key size (bits): 4096\n"
  f"V3.0 Signer: public key SHA-256 digest: {'99' * 32}\n"
  f"V3.0 Signer: public key SHA-1 digest: {'88' * 20}\n"
  f"V3.0 Signer: public key MD5 digest: {'77' * 16}\n"
)


def release(tag: str, *, draft: bool = False, assets: tuple[str, ...] = ()) -> Release:
  return Release(
    tag_name=tag,
    draft=draft,
    assets=tuple(ReleaseAsset(name=a, url=f"https://api.example/{a}") for a in assets),
  )


class SemVerTest(unittest.TestCase):
  def test_parse_valid(self) -> None:
    self.assertEqual(SemVer.parse("0.0.1"), SemVer(0, 0, 1))
    self.assertEqual(SemVer.parse("1.12.0"), SemVer(1, 12, 0))
    self.assertEqual(SemVer.parse("2.0.103"), SemVer(2, 0, 103))
    self.assertEqual(SemVer.parse("10.20.30"), SemVer(10, 20, 30))

  def test_parse_invalid(self) -> None:
    for text in (
      "",
      "1",
      "1.2",
      "1.2.3.4",
      "01.2.3",
      "1.02.3",
      "1.2.03",
      "1.2.3-dev",
      "1.2.3+build",
      "v1.2.3",
      " 1.2.3",
      "1.2.3 ",
      "1.2.3\n",
      "1.-2.3",
      "a.b.c",
      "1.2.\u0663",  # ARABIC-INDIC DIGIT THREE
    ):
      with self.subTest(text=text):
        self.assertIsNone(SemVer.parse(text))

  def test_ordering_is_numeric(self) -> None:
    self.assertLess(SemVer(0, 9, 0), SemVer(0, 10, 0))
    self.assertLess(SemVer(1, 99, 99), SemVer(2, 0, 0))
    self.assertLess(SemVer(1, 2, 3), SemVer(1, 2, 4))
    self.assertEqual(SemVer(1, 2, 3), SemVer(1, 2, 3))

  def test_str_and_tag(self) -> None:
    self.assertEqual(str(SemVer(1, 2, 3)), "1.2.3")
    self.assertEqual(SemVer(1, 2, 3).tag, "v1.2.3")

  def test_parse_tag(self) -> None:
    self.assertEqual(parse_tag("v1.2.3"), SemVer(1, 2, 3))
    self.assertIsNone(parse_tag("1.2.3"))
    self.assertIsNone(parse_tag("v1.2.3-beta"))
    self.assertIsNone(parse_tag("release-1"))

  def test_apk_asset_name(self) -> None:
    self.assertEqual(apk_asset_name("shhhhhh", SemVer(0, 0, 1)), "shhhhhh-v0.0.1.apk")

  def test_ios_asset_names(self) -> None:
    version = SemVer(1, 0, 2)
    self.assertEqual(
      ios_simulator_zip_asset_name("shhhhhh", version),
      "shhhhhh-v1.0.2-ios-simulator.app.zip",
    )
    self.assertEqual(
      ios_ipa_asset_name("shhhhhh", version),
      "shhhhhh-v1.0.2-ios-unsigned.ipa",
    )
    self.assertEqual(
      ios_dsym_zip_asset_name("shhhhhh", version),
      "shhhhhh-v1.0.2-ios-dSYMs.zip",
    )


class ParseBadgingTest(unittest.TestCase):
  def test_parse_badging(self) -> None:
    self.assertEqual(
      parse_badging(SAMPLE_BADGING),
      ApkInfo(package_name="prefix924.shhhhhh", version_code=1, version_name="0.0.1"),
    )

  def test_missing_package_line(self) -> None:
    with self.assertRaises(ReleaseToolError):
      parse_badging("minSdkVersion:'37'\n")

  def test_missing_attribute(self) -> None:
    with self.assertRaisesRegex(ReleaseToolError, "versionName"):
      parse_badging("package: name='a.b' versionCode='1'\n")

  def test_invalid_version_code(self) -> None:
    with self.assertRaisesRegex(ReleaseToolError, "versionCode"):
      parse_badging("package: name='a.b' versionCode='0' versionName='1.0.0'\n")

  def test_version_name_with_suffix_is_returned_verbatim(self) -> None:
    info = parse_badging("package: name='a.b' versionCode='3' versionName='1.0.0-dev'\n")
    self.assertEqual(info.version_name, "1.0.0-dev")


class ParseCertDigestTest(unittest.TestCase):
  def test_parse_cert_digest(self) -> None:
    self.assertEqual(parse_cert_digest(SAMPLE_APKSIGNER), DIGEST)

  def test_parse_cert_digest_v3_only(self) -> None:
    self.assertEqual(parse_cert_digest(SAMPLE_APKSIGNER_V3_ONLY), DIGEST)

  def test_public_key_digest_is_not_mistaken_for_certificate_digest(self) -> None:
    output = "\n".join(
      line for line in SAMPLE_APKSIGNER_V3_ONLY.splitlines() if "certificate SHA-256" not in line
    )
    with self.assertRaisesRegex(ReleaseToolError, "found 0"):
      parse_cert_digest(output)

  def test_same_digest_reported_by_multiple_schemes(self) -> None:
    output = SAMPLE_APKSIGNER_V3_ONLY + f"V3.1 Signer: certificate SHA-256 digest: {DIGEST}\n"
    self.assertEqual(parse_cert_digest(output), DIGEST)

  def test_different_digests_rejected(self) -> None:
    output = SAMPLE_APKSIGNER_V3_ONLY + f"V3.1 Signer: certificate SHA-256 digest: {'12' * 32}\n"
    with self.assertRaisesRegex(ReleaseToolError, "found 2"):
      parse_cert_digest(output)

  def test_uppercase_digest_is_lowercased(self) -> None:
    output = SAMPLE_APKSIGNER.replace(DIGEST, DIGEST.upper())
    self.assertEqual(parse_cert_digest(output), DIGEST)

  def test_multiple_signers_rejected(self) -> None:
    output = SAMPLE_APKSIGNER.replace("Number of signers: 1", "Number of signers: 2")
    with self.assertRaisesRegex(ReleaseToolError, "exactly 1 signer"):
      parse_cert_digest(output)

  def test_missing_signer_count_rejected(self) -> None:
    output = SAMPLE_APKSIGNER.replace("Number of signers: 1\n", "")
    with self.assertRaises(ReleaseToolError):
      parse_cert_digest(output)

  def test_missing_digest_rejected(self) -> None:
    output = "\n".join(line for line in SAMPLE_APKSIGNER.splitlines() if "SHA-256" not in line)
    with self.assertRaises(ReleaseToolError):
      parse_cert_digest(output)

  def test_format_fingerprint(self) -> None:
    self.assertEqual(format_fingerprint("ab01cd"), "AB:01:CD")


class FindHighestReleaseTest(unittest.TestCase):
  def test_empty(self) -> None:
    self.assertIsNone(find_highest_release([]))

  def test_numeric_ordering_and_drafts_included(self) -> None:
    releases = [
      release("v0.9.0"),
      release("v0.10.0", draft=True),
      release("v0.2.0"),
    ]
    highest = find_highest_release(releases)
    assert highest is not None
    self.assertEqual(highest.version, SemVer(0, 10, 0))
    self.assertTrue(highest.release.draft)

  def test_non_semver_tags_ignored_with_warning(self) -> None:
    warnings: list[str] = []
    highest = find_highest_release(
      [release("nightly"), release("v1.0.0-beta"), release("v0.1.0")], warn=warnings.append
    )
    assert highest is not None
    self.assertEqual(highest.version, SemVer(0, 1, 0))
    self.assertEqual(len(warnings), 2)


class CheckNewVersionAgainstReleasesTest(unittest.TestCase):
  def test_first_release(self) -> None:
    self.assertIsNone(check_new_version_against_releases(SemVer(0, 0, 1), []))

  def test_greater_version_ok(self) -> None:
    highest = check_new_version_against_releases(
      SemVer(0, 2, 0), [release("v0.1.0"), release("v0.1.5")]
    )
    assert highest is not None
    self.assertEqual(highest.version, SemVer(0, 1, 5))

  def test_existing_release_with_same_tag_rejected(self) -> None:
    with self.assertRaisesRegex(ReleaseToolError, "already exists"):
      check_new_version_against_releases(SemVer(0, 1, 0), [release("v0.1.0")])

  def test_existing_draft_with_same_tag_rejected(self) -> None:
    with self.assertRaisesRegex(ReleaseToolError, "draft release with tag v0.1.0"):
      check_new_version_against_releases(SemVer(0, 1, 0), [release("v0.1.0", draft=True)])

  def test_lower_version_rejected(self) -> None:
    with self.assertRaisesRegex(ReleaseToolError, "strictly greater"):
      check_new_version_against_releases(SemVer(0, 9, 9), [release("v0.10.0")])

  def test_lower_than_draft_rejected(self) -> None:
    with self.assertRaisesRegex(ReleaseToolError, "strictly greater"):
      check_new_version_against_releases(
        SemVer(0, 2, 0), [release("v0.1.0"), release("v0.3.0", draft=True)]
      )


class CheckVersionCodeTest(unittest.TestCase):
  def test_greater_ok(self) -> None:
    check_version_code(2, 1, "v0.0.1")

  def test_equal_rejected(self) -> None:
    with self.assertRaisesRegex(ReleaseToolError, "versionCode 1 must be strictly greater"):
      check_version_code(1, 1, "v0.0.1")

  def test_lower_rejected(self) -> None:
    with self.assertRaises(ReleaseToolError):
      check_version_code(1, 5, "v0.0.5")


class FindApkAssetTest(unittest.TestCase):
  def test_found(self) -> None:
    rel = release("v0.0.1", assets=("shhhhhh-v0.0.1-mapping.txt", "shhhhhh-v0.0.1.apk"))
    self.assertEqual(find_apk_asset(rel, "shhhhhh", SemVer(0, 0, 1)).name, "shhhhhh-v0.0.1.apk")

  def test_missing(self) -> None:
    rel = release("v0.0.1", assets=("other.apk",))
    with self.assertRaisesRegex(ReleaseToolError, "no asset named shhhhhh-v0.0.1.apk"):
      find_apk_asset(rel, "shhhhhh", SemVer(0, 0, 1))


class FormatReleaseNotesTest(unittest.TestCase):
  def test_format_release_notes(self) -> None:
    commit = "0123456789abcdef0123456789abcdef01234567"
    body = format_release_notes(
      ReleaseNotesInput(
        repo="denversc/shhhhhh",
        commit=commit,
        version=SemVer(0, 0, 1),
        version_code=1,
        apk_name="shhhhhh-v0.0.1.apk",
        apk_sha256="12" * 32,
        cert_sha256="ab" * 32,
        ios_simulator_zip_name="shhhhhh-v0.0.1-ios-simulator.app.zip",
        ios_simulator_zip_sha256="34" * 32,
        ios_ipa_name="shhhhhh-v0.0.1-ios-unsigned.ipa",
        ios_ipa_sha256="56" * 32,
      )
    )
    self.assertIn(f"https://github.com/denversc/shhhhhh/commit/{commit}", body)
    self.assertIn("`0123456789ab`", body)
    self.assertIn("versionCode / CFBundleVersion 1", body)
    self.assertIn("12" * 32, body)
    self.assertIn(":".join(["AB"] * 32), body)
    self.assertIn("34" * 32, body)
    self.assertIn("56" * 32, body)
    self.assertIn("gh attestation verify shhhhhh-v0.0.1.apk --repo denversc/shhhhhh", body)
    self.assertIn(
      "gh attestation verify shhhhhh-v0.0.1-ios-simulator.app.zip --repo denversc/shhhhhh",
      body,
    )
    self.assertIn(
      "gh attestation verify shhhhhh-v0.0.1-ios-unsigned.ipa --repo denversc/shhhhhh",
      body,
    )


def make_plist_bytes(
  *,
  bundle_id: str = "prefix924.shhhhhh",
  version_name: str = "1.0.2",
  version_code: str = "1000002",
  platform_name: str = IOS_SIMULATOR_PLATFORM,
  fmt: plistlib.PlistFormat = plistlib.FMT_BINARY,
) -> bytes:
  return plistlib.dumps(
    {
      "CFBundleIdentifier": bundle_id,
      "CFBundleShortVersionString": version_name,
      "CFBundleVersion": version_code,
      "DTPlatformName": platform_name,
    },
    fmt=fmt,
  )


def write_ios_archive(
  path: Path,
  *,
  entry_path: str,
  plist_bytes: bytes,
  extra_entries: dict[str, bytes] | None = None,
) -> Path:
  with zipfile.ZipFile(path, "w") as zf:
    zf.writestr(entry_path, plist_bytes)
    if extra_entries:
      for name, data in extra_entries.items():
        zf.writestr(name, data)
  return path


class IosBundleInspectionTest(unittest.TestCase):
  def test_parse_binary_and_xml_plist(self) -> None:
    for fmt in (plistlib.FMT_BINARY, plistlib.FMT_XML):
      with self.subTest(fmt=fmt):
        info = parse_ios_info_plist(make_plist_bytes(fmt=fmt))
        self.assertEqual(
          info,
          IosBundleInfo(
            bundle_id="prefix924.shhhhhh",
            version_code=1000002,
            version_name="1.0.2",
            platform_name=IOS_SIMULATOR_PLATFORM,
          ),
        )

  def test_parse_rejects_missing_key(self) -> None:
    data = plistlib.dumps({"CFBundleIdentifier": "prefix924.shhhhhh"})
    with self.assertRaisesRegex(ReleaseToolError, "missing key"):
      parse_ios_info_plist(data)

  def test_parse_rejects_invalid_cf_bundle_version(self) -> None:
    with self.assertRaisesRegex(ReleaseToolError, "CFBundleVersion"):
      parse_ios_info_plist(make_plist_bytes(version_code="0"))

  def test_read_simulator_and_ipa_ignores_embedded_frameworks(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
      root = Path(tmp_dir)
      sim_zip = write_ios_archive(
        root / "sim.app.zip",
        entry_path="shhhhhh.app/Info.plist",
        plist_bytes=make_plist_bytes(platform_name=IOS_SIMULATOR_PLATFORM),
        extra_entries={
          "shhhhhh.app/Frameworks/ShhhhhhCore.framework/Info.plist": make_plist_bytes(
            bundle_id="org.example.ShhhhhhCore",
            version_name="9.9.9",
            version_code="999",
            platform_name=IOS_SIMULATOR_PLATFORM,
          )
        },
      )
      ipa = write_ios_archive(
        root / "app.ipa",
        entry_path="Payload/shhhhhh.app/Info.plist",
        plist_bytes=make_plist_bytes(platform_name=IOS_DEVICE_PLATFORM),
        extra_entries={
          "Payload/shhhhhh.app/Frameworks/ShhhhhhCore.framework/Info.plist": make_plist_bytes(
            bundle_id="org.example.ShhhhhhCore",
            version_name="9.9.9",
            version_code="999",
            platform_name=IOS_DEVICE_PLATFORM,
          )
        },
      )
      sim_info = read_ios_archive_info(sim_zip, expected_platform=IOS_SIMULATOR_PLATFORM)
      ipa_info = read_ios_archive_info(ipa, expected_platform=IOS_DEVICE_PLATFORM)
      self.assertEqual(sim_info.platform_name, IOS_SIMULATOR_PLATFORM)
      self.assertEqual(ipa_info.platform_name, IOS_DEVICE_PLATFORM)
      self.assertEqual(validate_ios_pair(sim_info, ipa_info, "prefix924.shhhhhh"), SemVer(1, 0, 2))

  def test_read_rejects_wrong_platform(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
      ipa = write_ios_archive(
        Path(tmp_dir) / "wrong.ipa",
        entry_path="Payload/shhhhhh.app/Info.plist",
        plist_bytes=make_plist_bytes(platform_name=IOS_SIMULATOR_PLATFORM),
      )
      with self.assertRaisesRegex(ReleaseToolError, "DTPlatformName"):
        read_ios_archive_info(ipa, expected_platform=IOS_DEVICE_PLATFORM)

  def test_validate_ios_pair_rejects_dev_suffix(self) -> None:
    sim = IosBundleInfo("prefix924.shhhhhh", 1000002, "1.0.2-dev", IOS_SIMULATOR_PLATFORM)
    ipa = IosBundleInfo("prefix924.shhhhhh", 1000002, "1.0.2-dev", IOS_DEVICE_PLATFORM)
    with self.assertRaisesRegex(ReleaseToolError, "plain MAJOR.MINOR.PATCH"):
      validate_ios_pair(sim, ipa, "prefix924.shhhhhh")

  def test_validate_ios_pair_rejects_mismatched_builds(self) -> None:
    sim = IosBundleInfo("prefix924.shhhhhh", 1000002, "1.0.2", IOS_SIMULATOR_PLATFORM)
    ipa = IosBundleInfo("prefix924.shhhhhh", 1000003, "1.0.2", IOS_DEVICE_PLATFORM)
    with self.assertRaisesRegex(ReleaseToolError, "CFBundleVersion"):
      validate_ios_pair(sim, ipa, "prefix924.shhhhhh")


class PlatformParityAndAssetsTest(unittest.TestCase):
  def test_parity_ok(self) -> None:
    check_platform_parity(
      android_version=SemVer(1, 0, 2),
      android_version_code=1000002,
      ios_version=SemVer(1, 0, 2),
      ios_version_code=1000002,
    )

  def test_parity_version_mismatch(self) -> None:
    with self.assertRaisesRegex(ReleaseToolError, "does not match Android versionName"):
      check_platform_parity(
        android_version=SemVer(1, 0, 2),
        android_version_code=1000002,
        ios_version=SemVer(1, 0, 1),
        ios_version_code=1000002,
      )

  def test_parity_code_mismatch(self) -> None:
    with self.assertRaisesRegex(ReleaseToolError, "does not match Android versionCode"):
      check_platform_parity(
        android_version=SemVer(1, 0, 2),
        android_version_code=1000002,
        ios_version=SemVer(1, 0, 2),
        ios_version_code=1,
      )

  def test_find_optional_ios_asset(self) -> None:
    android_only = release("v1.0.1", assets=("shhhhhh-v1.0.1.apk",))
    self.assertIsNone(find_optional_ios_asset(android_only, "shhhhhh", SemVer(1, 0, 1)))

    unified = release(
      "v1.0.2",
      assets=("shhhhhh-v1.0.2.apk", "shhhhhh-v1.0.2-ios-unsigned.ipa"),
    )
    found = find_optional_ios_asset(unified, "shhhhhh", SemVer(1, 0, 2))
    assert found is not None
    self.assertEqual(found[0].name, "shhhhhh-v1.0.2-ios-unsigned.ipa")
    self.assertEqual(found[1], IOS_DEVICE_PLATFORM)


class CmdCheckVersionAndIosInfoTest(unittest.TestCase):
  def test_cmd_ios_info_writes_outputs(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
      root = Path(tmp_dir)
      output_file = root / "outputs"
      sim_zip = write_ios_archive(
        root / "sim.app.zip",
        entry_path="shhhhhh.app/Info.plist",
        plist_bytes=make_plist_bytes(platform_name=IOS_SIMULATOR_PLATFORM),
      )
      ipa = write_ios_archive(
        root / "app.ipa",
        entry_path="Payload/shhhhhh.app/Info.plist",
        plist_bytes=make_plist_bytes(platform_name=IOS_DEVICE_PLATFORM),
      )
      args = parse_args(
        [
          "ios-info",
          "--simulator-zip",
          str(sim_zip),
          "--ipa",
          str(ipa),
          "--expected-bundle-id",
          "prefix924.shhhhhh",
          "--asset-prefix",
          "shhhhhh",
        ]
      )
      with (
        mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(output_file)}),
        mock.patch("builtins.print"),
        mock.patch("sys.stderr"),
      ):
        args.func(args)

      outputs = output_file.read_text(encoding="utf-8")
      self.assertIn("version-name=1.0.2\n", outputs)
      self.assertIn("version-code=1000002\n", outputs)
      self.assertIn("tag=v1.0.2\n", outputs)
      self.assertIn("simulator-zip-name=shhhhhh-v1.0.2-ios-simulator.app.zip\n", outputs)
      self.assertIn("ipa-name=shhhhhh-v1.0.2-ios-unsigned.ipa\n", outputs)
      self.assertIn("dsym-zip-name=shhhhhh-v1.0.2-ios-dSYMs.zip\n", outputs)

  def test_cmd_check_version_allows_android_only_previous_release(self) -> None:
    prev = release("v1.0.1", assets=("shhhhhh-v1.0.1.apk",))
    fake_client = mock.MagicMock()
    fake_client.tag_exists.return_value = False
    fake_client.list_releases.return_value = [prev]

    def fake_download(_asset: ReleaseAsset, dest: Path) -> None:
      dest.write_bytes(b"apk")

    fake_client.download_asset.side_effect = fake_download

    with (
      mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "o/r", "GH_TOKEN": "tok"}, clear=True),
      mock.patch.object(release_tool, "GitHubClient", return_value=fake_client),
      mock.patch.object(
        release_tool,
        "read_apk_info",
        return_value=ApkInfo("prefix924.shhhhhh", 1000001, "1.0.1"),
      ),
      mock.patch("sys.stderr"),
    ):
      args = parse_args(
        [
          "check-version",
          "--aapt2",
          "/x/aapt2",
          "--version-name",
          "1.0.2",
          "--version-code",
          "1000002",
          "--ios-version-name",
          "1.0.2",
          "--ios-version-code",
          "1000002",
          "--asset-prefix",
          "shhhhhh",
        ]
      )
      args.func(args)

    fake_client.download_asset.assert_called_once()

  def test_cmd_check_version_checks_previous_ios_build_when_present(self) -> None:
    prev = release(
      "v1.0.2",
      assets=("shhhhhh-v1.0.2.apk", "shhhhhh-v1.0.2-ios-unsigned.ipa"),
    )
    fake_client = mock.MagicMock()
    fake_client.tag_exists.return_value = False
    fake_client.list_releases.return_value = [prev]

    def fake_download(asset: ReleaseAsset, dest: Path) -> None:
      if asset.name.endswith(".ipa"):
        write_ios_archive(
          dest,
          entry_path="Payload/shhhhhh.app/Info.plist",
          plist_bytes=make_plist_bytes(
            version_name="1.0.2",
            version_code="1000003",
            platform_name=IOS_DEVICE_PLATFORM,
          ),
        )
      else:
        dest.write_bytes(b"apk")

    fake_client.download_asset.side_effect = fake_download

    with (
      mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "o/r", "GH_TOKEN": "tok"}, clear=True),
      mock.patch.object(release_tool, "GitHubClient", return_value=fake_client),
      mock.patch.object(
        release_tool,
        "read_apk_info",
        return_value=ApkInfo("prefix924.shhhhhh", 1000002, "1.0.2"),
      ),
      mock.patch("sys.stderr"),
      self.assertRaisesRegex(ReleaseToolError, "CFBundleVersion 1000003"),
    ):
      args = parse_args(
        [
          "check-version",
          "--aapt2",
          "/x/aapt2",
          "--version-name",
          "1.0.3",
          "--version-code",
          "1000003",
          "--ios-version-name",
          "1.0.3",
          "--ios-version-code",
          "1000003",
          "--asset-prefix",
          "shhhhhh",
        ]
      )
      args.func(args)


class WriteGithubOutputsTest(unittest.TestCase):
  def test_writes_to_github_output(self) -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
      output_file = Path(tmp_dir) / "output"
      with (
        mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(output_file)}),
        mock.patch("builtins.print"),
      ):
        write_github_outputs({"a": "1", "b": "two"})
      self.assertEqual(output_file.read_text(encoding="utf-8"), "a=1\nb=two\n")

  def test_rejects_newlines(self) -> None:
    with mock.patch.dict(os.environ, {}, clear=True), self.assertRaises(ReleaseToolError):
      write_github_outputs({"a": "1\n2"})


class ParseArgsTest(unittest.TestCase):
  def test_check_version_args(self) -> None:
    with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "o/r", "GH_TOKEN": "t"}, clear=True):
      args = parse_args(
        [
          "check-version",
          "--aapt2",
          "/x/aapt2",
          "--version-name",
          "1.2.3",
          "--version-code",
          "7",
          "--ios-version-name",
          "1.2.3",
          "--ios-version-code",
          "7",
          "--asset-prefix",
          "shhhhhh",
        ]
      )
    self.assertEqual(args.repo, "o/r")
    self.assertEqual(args.token, "t")
    self.assertEqual(args.version_code, 7)
    self.assertEqual(args.ios_version_code, 7)
    self.assertEqual(args.aapt2, Path("/x/aapt2"))

  def test_check_version_requires_token(self) -> None:
    with (
      mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "o/r"}, clear=True),
      mock.patch("sys.stderr"),
      self.assertRaises(SystemExit),
    ):
      parse_args(
        [
          "check-version",
          "--aapt2",
          "a",
          "--version-name",
          "1.2.3",
          "--version-code",
          "1",
          "--ios-version-name",
          "1.2.3",
          "--ios-version-code",
          "1",
          "--asset-prefix",
          "p",
        ]
      )

  def test_ios_info_args(self) -> None:
    args = parse_args(
      [
        "ios-info",
        "--simulator-zip",
        "/x/sim.app.zip",
        "--ipa",
        "/x/app.ipa",
        "--expected-bundle-id",
        "prefix924.shhhhhh",
        "--asset-prefix",
        "shhhhhh",
      ]
    )
    self.assertEqual(args.simulator_zip, Path("/x/sim.app.zip"))
    self.assertEqual(args.ipa, Path("/x/app.ipa"))
    self.assertEqual(args.expected_bundle_id, "prefix924.shhhhhh")


if __name__ == "__main__":
  unittest.main()
