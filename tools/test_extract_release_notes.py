#!/usr/bin/env python3
"""Tests for selecting the release notes shown on GitHub."""

import unittest

from extract_release_notes import extract_release_notes


class ExtractReleaseNotesTest(unittest.TestCase):
    def test_matches_apk_version_to_short_changelog_version(self):
        changelog = """\
## [1.5] - 2026-10-03

The useful current update.

## [1.4] - 2026-10-02

Older notes must not leak into this release.
"""
        notes = extract_release_notes(changelog, "1.5.0-main.22-googletv")
        self.assertIn("The useful current update.", notes)
        self.assertNotIn("Older notes", notes)

    def test_nonempty_unreleased_notes_take_priority(self):
        changelog = """\
## [Unreleased]

### Fixed

A just-merged fix.

## [1.5]

Notes from the previous release.
"""
        notes = extract_release_notes(changelog, "1.5.0-main.23-googletv")
        self.assertIn("A just-merged fix.", notes)
        self.assertNotIn("previous release", notes)

    def test_empty_unreleased_placeholder_does_not_hide_version_notes(self):
        changelog = """\
## [Unreleased]

Nothing yet — changes collect here until the next version is cut.

## [1.5]

Current version notes.
"""
        notes = extract_release_notes(changelog, "1.5.0-main.24-googletv")
        self.assertIn("Current version notes.", notes)
        self.assertNotIn("Nothing yet", notes)

    def test_does_not_reuse_notes_from_a_different_version(self):
        changelog = """\
## [1.5]

Old release notes.
"""
        self.assertEqual("", extract_release_notes(changelog, "1.6.0-main.1-googletv"))


if __name__ == "__main__":
    unittest.main()
