#!/usr/bin/env python3
"""Offline sanity check for GitHub workflow files.

No PyYAML in the sandbox, so this does two targeted jobs:
  1. structural scan: TAB characters and unbalanced quotes, plus a list of every
     `${{ }}` expression used, so typos surface before pushing;
  2. extracts every `run: |` block, neutralises `${{ }}` expressions and pipes it through
     `bash -n` so shell syntax errors are caught locally instead of in CI.
"""
import re
import subprocess
import sys
from pathlib import Path

RUN_RE = re.compile(r"^(?P<indent>\s*)run:\s*\|\s*$")
EXPR_RE = re.compile(r"\$\{\{[^}]*\}\}")


def run_blocks(text):
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        m = RUN_RE.match(lines[i])
        if not m:
            i += 1
            continue
        base = len(m.group("indent"))
        body = []
        j = i + 1
        while j < len(lines):
            line = lines[j]
            if line.strip() == "":
                body.append("")
                j += 1
                continue
            indent = len(line) - len(line.lstrip(" "))
            if indent <= base:
                break
            body.append(line[base + 2:] if indent > base + 2 else line.lstrip(" "))
            j += 1
        yield i + 1, "\n".join(body)
        i = j


def dedent_block(body):
    widths = [len(l) - len(l.lstrip(" ")) for l in body.splitlines() if l.strip()]
    if not widths:
        return body
    cut = min(widths)
    return "\n".join(l[cut:] if l.strip() else "" for l in body.splitlines())


def check(path):
    text = Path(path).read_text()
    problems = []

    if "\t" in text:
        problems.append(f"{path}: contains a TAB character (YAML forbids tabs for indentation)")

    for n, line in enumerate(text.splitlines(), 1):
        # Comments may quote prose ("GitHub Actions") on one line and close it on the next.
        if line.lstrip().startswith("#"):
            continue
        if line.count('"') % 2 == 1 and "'" not in line:
            problems.append(f"{path}:{n}: odd number of double quotes -> {line.strip()[:90]}")

    exprs = sorted(set(EXPR_RE.findall(text)))

    ok = True
    for lineno, body in run_blocks(text):
        script = dedent_block(body)
        script = EXPR_RE.sub("DUMMY", script)
        # env/inputs referenced through $GITHUB_* stay as-is; they are just unset here
        proc = subprocess.run(["bash", "-n"], input=script, text=True,
                              capture_output=True)
        if proc.returncode != 0:
            ok = False
            problems.append(f"{path}:{lineno}: bash syntax error\n{proc.stderr.strip()}")
        else:
            print(f"  run block at line {lineno:>4}: bash -n OK ({len(script.splitlines())} lines)")

    print("  ${{ }} expressions used (%d):" % len(exprs))
    for e in exprs:
        print(f"    {e}")
    if problems:
        print("PROBLEMS:")
        for p in problems:
            print("  -", p)
    return ok and not problems


if __name__ == "__main__":
    good = True
    for f in sys.argv[1:]:
        print(f"== {f}")
        good &= check(f)
    print("ALL OK" if good else "ISSUES FOUND")
    sys.exit(0 if good else 1)
