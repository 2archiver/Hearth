#!/usr/bin/env python3
"""Print the relevant CHANGELOG.md section for a rolling APK release.

An unreleased section with real content takes priority. Otherwise, match the APK's
version to a Keep a Changelog heading (for example, APK 1.5.0-main.12-googletv
matches ``## [1.5]``). Print nothing when there is no honest match so the workflow
can fall back to the commits since the previous release instead of showing stale notes.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

HEADING = re.compile(r"^## \[([^]]+)\].*$", re.MULTILINE)
VERSION = re.compile(r"^(\d+(?:\.\d+){1,2})")
EMPTY_CHANGELOG_LINE = re.compile(
    r"^\s*(?:nothing yet\b|no changes?\b|tbd\b).*?$", re.IGNORECASE
)


def version_key(value: str) -> tuple[int, ...] | None:
    """Normalize ``1.5`` and ``1.5.0-main.12-googletv`` to the same key."""
    value = value.strip().lstrip("vV")
    match = VERSION.match(value)
    if not match:
        return None
    parts = [int(part) for part in match.group(1).split(".")]
    while len(parts) > 2 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def meaningful_body(body: str) -> str:
    """Remove an empty-section placeholder; return an empty string if nothing remains."""
    lines = [line for line in body.strip().splitlines() if not EMPTY_CHANGELOG_LINE.match(line)]
    return "\n".join(lines).strip()


def sections(text: str) -> list[tuple[str, str]]:
    """Return level-two changelog sections in file order."""
    headings = list(HEADING.finditer(text))
    result = []
    for index, heading in enumerate(headings):
        start = heading.end()
        end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
        result.append((heading.group(1).strip(), text[start:end].strip()))
    return result


def extract_release_notes(changelog: str, apk_version: str) -> str:
    parsed = sections(changelog)

    # An actively maintained Unreleased section describes changes that may not yet
    # have been assigned to a numbered changelog version.
    for title, body in parsed:
        if title.casefold() == "unreleased":
            notes = meaningful_body(body)
            if notes:
                return notes
            break

    expected = version_key(apk_version)
    if expected is None:
        return ""

    for title, body in parsed:
        if title.casefold() == "unreleased":
            continue
        if version_key(title) == expected:
            return meaningful_body(body)
    return ""


def main() -> int:
    if len(sys.argv) != 3:
        print(f"Usage: {Path(sys.argv[0]).name} CHANGELOG.md APK_VERSION", file=sys.stderr)
        return 2

    changelog_path = Path(sys.argv[1])
    apk_version = sys.argv[2]
    notes = extract_release_notes(changelog_path.read_text(encoding="utf-8"), apk_version)
    if notes:
        print(notes)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
