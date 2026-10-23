#!/usr/bin/env python3
"""Contract tests for the immutable Release workflow.

Runs in CI's "Validate release automation" step without PyYAML, the Android SDK or
network access. The release hotfix switched publishing from one mutable ``latest``
tag (force-pushed, assets deleted and re-uploaded on every build) to one immutable,
versioned GitHub Release per successful build. These tests pin that contract so the
destructive behaviour cannot silently return:

* tags/releases are named ``v<versionName>-main.<run number>`` and are unique;
* no force-push, tag move, release/asset deletion, clobber or cleanup action exists;
* GitHub's ``make_latest`` designation is the only rolling pointer, read through
  ``/releases/latest`` by the in-app updater;
* one APK is published per release, plus a separately retained workflow artifact;
* reruns are idempotent and concurrent publishing is guarded by a concurrency group.

The updater-side parsing of these releases is covered by the JVM tests in
``app/src/test/kotlin/com/phairplay/update/ImmutableReleaseTest.kt``.
"""

from __future__ import annotations

import re
import os
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASE_WORKFLOW = ROOT / ".github/workflows/release.yml"
WORKFLOW_DIR = ROOT / ".github/workflows"

# Same version policy as the workflow's meta step and gradle.properties.
BASE_VERSION_RE = re.compile(r"^\d+\.\d+(\.\d+)?(-[0-9A-Za-z.-]+)?$")
RUN_NUMBER = "23"
EXAMPLE_BASE = "2.1.0"
EXAMPLE_VERSION = f"{EXAMPLE_BASE}-main.{RUN_NUMBER}"
EXAMPLE_TAG = f"v{EXAMPLE_VERSION}"
EXAMPLE_ASSET = f"Hearth-{EXAMPLE_VERSION}-googletv.apk"
IMMUTABLE_TAG_RE = re.compile(r"^v\d+\.\d+(\.\d+)?(-[0-9A-Za-z.-]+)?-main\.\d+$")


class ReleaseNamingTest(unittest.TestCase):
    def test_example_names_follow_the_immutable_scheme(self):
        self.assertTrue(BASE_VERSION_RE.match(EXAMPLE_BASE))
        self.assertTrue(IMMUTABLE_TAG_RE.match(EXAMPLE_TAG))
        self.assertEqual(f"v{EXAMPLE_VERSION}", EXAMPLE_TAG)
        self.assertEqual(f"Hearth-{EXAMPLE_VERSION}-googletv.apk", EXAMPLE_ASSET)

    def test_a_moving_latest_tag_does_not_match_the_immutable_scheme(self):
        self.assertIsNone(IMMUTABLE_TAG_RE.match("latest"))
        self.assertIsNone(IMMUTABLE_TAG_RE.match("v2.0.0"))

    def test_workflow_mints_the_tag_from_the_embedded_version_and_run_number(self):
        text = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn('version="${base}-main.${GITHUB_RUN_NUMBER}"', text)
        self.assertIn('tag="v${version}"', text)
        # The download URL must be built from the immutable tag, never a literal latest.
        self.assertIn("releases/download/${TAG}/${APK_NAME}", text)
        self.assertNotIn("releases/download/latest/", text)

    def test_gradle_properties_carries_a_supported_base_version(self):
        props = (ROOT / "gradle.properties").read_text(encoding="utf-8")
        match = re.search(r"^phairplay\.versionName=(.+)$", props, re.MULTILINE)
        self.assertIsNotNone(match)
        self.assertTrue(BASE_VERSION_RE.match(match.group(1).strip()))


class NoDestructivePublishingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflows = {
            path: path.read_text(encoding="utf-8")
            for path in sorted(WORKFLOW_DIR.glob("*.yml"))
        }
        cls.release = cls.workflows[RELEASE_WORKFLOW]

    def test_no_force_push_or_tag_rewrite_anywhere(self):
        for name, text in self.workflows.items():
            for forbidden in (
                "git push --force",
                "git push -f",
                "push --force origin",
                "git tag -f",
                "git tag --force",
            ):
                self.assertNotIn(forbidden, text, f"{name} must not contain {forbidden!r}")

    def test_release_workflow_never_deletes_releases_or_assets(self):
        for forbidden in (
            "--method DELETE",
            "-X DELETE",
            "releases/assets/",
            "gh release delete",
            "gh api --method delete",
            "delete-release",
            "delete-old-releases",
        ):
            self.assertNotIn(forbidden, self.release, f"release.yml must not contain {forbidden!r}")

    def test_no_asset_clobber_or_prune_or_cleanup_action(self):
        # The literal CLI flag is banned anywhere, including comments.
        self.assertNotIn("--clobber", self.release)
        # Prose may explain what is forbidden, so scan executable lines only for the
        # generic action words and third-party cleanup actions.
        code_lines = [
            line
            for line in self.release.splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
        code = "\n".join(code_lines)
        for forbidden in (
            "clobber",
            "prune",
            "cleanup",
            "actions/delete-package-versions",
        ):
            self.assertNotIn(forbidden, code, f"release.yml code must not contain {forbidden!r}")

    def test_no_workflow_uses_a_release_cleanup_action(self):
        for name, text in self.workflows.items():
            self.assertNotIn("delete-release-assets", text, name)
            self.assertNotIn("remove-old-assets", text, name)

    def test_latest_is_not_used_as_a_publish_target(self):
        # The historical 1.9.3 release tagged `latest` is retained, but no workflow may
        # publish to, upload against, or view a tag named latest anymore.
        for forbidden in (
            'gh release create latest',
            'gh release edit latest',
            'gh release upload latest',
            "releases/tags/latest",
            "refs/tags/latest",
        ):
            self.assertNotIn(forbidden, self.release, f"release.yml must not target {forbidden!r}")


class LatestDesignationTest(unittest.TestCase):
    def setUp(self):
        self.text = RELEASE_WORKFLOW.read_text(encoding="utf-8")

    def test_latest_pointer_uses_the_github_designation_api(self):
        self.assertIn("make_latest=true", self.text)
        self.assertIn("make_latest=false", self.text)
        # The updater's endpoint, which resolves to the designated newest release.
        self.assertIn("repos/$REPO/releases/latest", self.text)

    def test_stale_runs_do_not_take_the_designation(self):
        self.assertIn("id: freshness", self.text)
        self.assertIn('IS_CURRENT', self.text)

    def test_concurrent_publishing_is_serialized(self):
        self.assertIn("concurrency:", self.text)
        self.assertIn("group: release-publish", self.text)
        self.assertIn("cancel-in-progress: false", self.text)


class OneApkAndArtifactTest(unittest.TestCase):
    def setUp(self):
        self.text = RELEASE_WORKFLOW.read_text(encoding="utf-8")

    def test_build_must_produce_exactly_one_apk_before_publishing(self):
        self.assertIn('Expected one Google TV release APK', self.text)
        self.assertIn('${#apks[@]} != 1', self.text)

    def test_signed_apk_is_also_kept_as_a_retained_workflow_artifact(self):
        self.assertIn("uses: actions/upload-artifact@v4", self.text)
        self.assertIn("retention-days: 30", self.text)
        # Artifacts and release assets are separate concepts.
        self.assertIn("dist/*.apk", self.text)

    def test_signature_and_metadata_verification_remain_gates(self):
        for required in (
            "apksigner",
            "EXPECTED_FINGERPRINT",
            "Verify APK metadata and collect release asset",
            "Verify the APK signature",
            "Verify generated updater repository",
        ):
            self.assertIn(required, self.text)


class RerunIdempotencyTest(unittest.TestCase):
    def setUp(self):
        self.text = RELEASE_WORKFLOW.read_text(encoding="utf-8")

    def test_existing_release_is_detected_before_creation(self):
        self.assertIn("releases/tags/$TAG", self.text)
        self.assertIn("already exists (rerun)", self.text)

    def test_existing_asset_is_skipped_not_replaced(self):
        self.assertIn("skipping upload", self.text)
        self.assertIn("Refusing to overwrite a published artifact", self.text)

    def test_tag_creation_checks_remote_first_and_pushes_without_force(self):
        self.assertIn('git ls-remote --exit-code --tags origin "refs/tags/$TAG"', self.text)
        self.assertIn('git push origin "refs/tags/$TAG"', self.text)
        self.assertNotIn('git push --force origin "refs/tags/$TAG"', self.text)

    def test_run_number_keeps_release_names_unique_across_runs(self):
        self.assertIn("${GITHUB_RUN_NUMBER}", self.text)


class ReleaseLookupResponseTest(unittest.TestCase):
    def lookup(self, response, exit_code):
        text = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        end = text.index('          if [[ -n "$existing_id" ]]; then')
        start = list(re.finditer(r"(?m)^          existing_id=", text[:end]))[-1].start()
        lookup = text[start:end]
        script = (
            "set -euo pipefail\n"
            "REPO=test/hearth\nTAG=v2.0.0-main.23\n"
            f"gh() {{ printf '%s' '{response}'; return {exit_code}; }}\n"
            + lookup + '\nprintf "%s" "$existing_id"\n'
        )
        result = subprocess.run(
            [os.environ.get("HEARTH_TEST_BASH", "bash"), "-c", script],
            capture_output=True, text=True, check=True,
        )
        return result.stdout

    def test_missing_release_error_body_is_not_a_release_id(self):
        self.assertEqual(self.lookup('{"message":"Not Found","status":"404"}', 1), "")

    def test_existing_release_id_is_preserved(self):
        self.assertEqual(self.lookup("401548003", 0), "401548003")


class StableMilestoneTest(unittest.TestCase):
    """The 2.1.0 stable release path, without weakening the immutable-release contract."""

    @classmethod
    def setUpClass(cls):
        cls.text = RELEASE_WORKFLOW.read_text(encoding="utf-8")

    def test_milestone_is_chosen_from_a_changelog_heading_and_a_missing_tag(self):
        # A milestone needs BOTH: notes for this exact version, and no v<version> tag yet.
        self.assertIn('grep -qF "## [${base}]" CHANGELOG.md', self.text)
        self.assertIn('refs/tags/v${base}', self.text)
        self.assertIn("milestone=true", self.text)
        self.assertIn('echo "milestone=$milestone"', self.text)

    def test_stable_release_uses_the_predictable_asset_name(self):
        self.assertIn('apk_name="Hearth${apk_version%-googletv}.apk"', self.text)
        # Development builds keep their embedded-version name.
        self.assertIn('apk_name="Hearth${apk_version%-googletv}.apk"', self.text)
        # No duplicated extension and no "universal" label anywhere.
        self.assertNotIn(".apk.apk", self.text)
        self.assertNotIn("universal", self.text.lower())

    def test_the_current_version_train_has_release_notes(self):
        # The milestone branch can only fire when the changelog has a section for the base
        # version; this catches a version bump that forgets the notes.
        props = (ROOT / "gradle.properties").read_text(encoding="utf-8")
        base = re.search(r"^phairplay\.versionName=(.+)$", props, re.MULTILINE).group(1).strip()
        changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
        self.assertIn(f"## [{base}]", changelog)

    def test_validation_gates_run_before_the_tag_and_the_release(self):
        validate = self.text.index("Validate release automation, protocol logic and TV interface")
        build = self.text.index("Build release APK")
        verify_metadata = self.text.index("Verify APK metadata and collect release asset")
        verify_signature = self.text.index("Verify the APK signature")
        tag = self.text.index("Create the immutable version tag")
        publish = self.text.index("Publish the immutable release (idempotent)")
        self.assertLess(validate, build)
        self.assertLess(build, verify_metadata)
        self.assertLess(verify_metadata, verify_signature)
        self.assertLess(verify_signature, tag)
        self.assertLess(tag, publish)

    def test_both_supported_abis_are_verified_inside_the_published_apk(self):
        # A build that silently loses armeabi-v7a still installs on 64-bit TVs, so only an
        # explicit gate catches it. Both ABIs must be asserted, and the failure must be fatal.
        self.assertIn("unzip -Z1", self.text)
        self.assertIn("for abi in armeabi-v7a arm64-v8a; do", self.text)
        self.assertIn("The release APK is missing supported ABI(s)", self.text)
        gate = self.text.index("The release APK is missing supported ABI(s)")
        tag = self.text.index("Create the immutable version tag")
        self.assertLess(gate, tag)

    def test_version_code_must_exceed_the_currently_published_release(self):
        # The updater only installs a newer versionCode, so publishing a lower one would look
        # like a successful release that no 2.0 install can ever take.
        self.assertIn("code <= previous_code", self.text)
        self.assertIn("code=$((previous_code + 1))", self.text)

    def test_the_milestone_path_adds_no_destructive_commands(self):
        code = "\n".join(
            line for line in self.text.splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        )
        for forbidden in ("git tag -d", "git push --delete", "gh release delete", "-X DELETE"):
            self.assertNotIn(forbidden, code)


if __name__ == "__main__":
    unittest.main(verbosity=2)
