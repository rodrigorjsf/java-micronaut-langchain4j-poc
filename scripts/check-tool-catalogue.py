#!/usr/bin/env python3
"""
WHAT   Cross-checks the API catalogue keys used in Java tool code against the keys
       configured under `agentic.tools.apis` in application.yml, in both directions.

WHY    A tool names a catalogue key and never a URL — that is the SSRF control
       (docs/adr/0009). The cost of that design is one failure mode: a key present
       in Java and absent from the configuration compiles, passes every unit test,
       and fails only when a user asks the question that reaches it, as a polite
       "this data source is not configured". Nothing else in the build catches it,
       because the pairing is a string on one side and YAML on the other.

       The reverse direction matters too: a configured host nothing calls is an
       allow-listed destination with no reason to be reachable.

WHEN   After adding or renaming a tool, after editing `agentic.tools.apis`, and
       after merging tool code written in parallel — which is exactly when the two
       halves drift.

HOW    ./scripts/check-tool-catalogue.py
       Exit 0 when both sides agree, 1 otherwise. Prints each mismatch with the
       file that declares it.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TOOLS_DIR = REPO / "src/main/java/io/github/rodrigorjsf/agenticchat"
CONFIG = REPO / "src/main/resources/application.yml"

# A catalogue key is a String constant passed to ToolHttpClient.get(...). Matching
# the constant declaration rather than the call site keeps this robust against
# formatting, and every tool class in this repository follows that shape.
CONSTANT = re.compile(r'static final String\s+\w+\s*=\s*"([a-z0-9][a-z0-9-]{2,60})"\s*;')


def keys_used() -> dict[str, set[Path]]:
    """Catalogue-shaped constants declared in classes that call ToolHttpClient."""
    used: dict[str, set[Path]] = {}
    for java in TOOLS_DIR.rglob("*.java"):
        text = java.read_text(encoding="utf-8")
        if "ToolHttpClient" not in text:
            continue
        for key in CONSTANT.findall(text):
            used.setdefault(key, set()).add(java.relative_to(REPO))
    return used


def keys_configured() -> set[str]:
    """Immediate children of `agentic.tools.apis` — parsed by indentation on purpose.

    A YAML library would be the obvious choice and would also pull a dependency
    into a check that has to run before anything is installed.
    """
    lines = CONFIG.read_text(encoding="utf-8").splitlines()
    configured: set[str] = set()
    apis_indent = None
    for line in lines:
        if apis_indent is None:
            if re.match(r"^(\s*)apis:\s*$", line):
                apis_indent = len(line) - len(line.lstrip())
            continue
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if indent <= apis_indent:
            break
        entry = re.match(r"^\s*([a-z0-9][a-z0-9-]*):\s*$", line)
        if entry and indent == apis_indent + 2:
            configured.add(entry.group(1))
    return configured


def main() -> int:
    used = keys_used()
    configured = keys_configured()

    missing = sorted(set(used) - configured)
    unused = sorted(configured - set(used))

    print(f"tool classes declare {len(used)} catalogue keys")
    print(f"application.yml configures {len(configured)}")

    if missing:
        print("\nUSED IN JAVA, NOT CONFIGURED — these tools would answer "
              "'this data source is not configured' at runtime:")
        for key in missing:
            where = ", ".join(str(path) for path in sorted(used[key]))
            print(f"  {key:32s} {where}")

    if unused:
        print("\nCONFIGURED, NEVER CALLED — an allow-listed host with no caller:")
        for key in unused:
            print(f"  {key}")

    if not missing and not unused:
        print("\nboth sides agree")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
