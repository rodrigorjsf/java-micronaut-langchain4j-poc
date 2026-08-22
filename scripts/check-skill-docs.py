#!/usr/bin/env python3
"""
WHAT   Cross-checks the skill directories under `.claude/skills/` against the skill
       rows in `docs/06-skills.md`, in both directions.

WHY    A skill is two artefacts that nothing pairs: a directory a model loads, and a
       row a human reads. Both failure modes are silent.

       A skill added with no row exists for the model and not for the reader — the
       chapter says it documents every skill, and quietly stops being true. That is
       worse than an absent chapter, because a reader who checked once trusts it
       afterwards.

       A row that outlives its directory is the same defect from the other end: a
       link to a path that is not there, in a table whose whole job is routing. The
       build has no opinion, because one side is a directory name and the other is a
       Markdown table.

       The count is deliberately not asserted anywhere — not here, not in the
       chapter, not in `README.md` or `docs/INDEX.md`. A number restated in three
       places is a number that is wrong in at least one of them; set equality is the
       assertion that does not rot.

WHEN   After adding, renaming or removing a skill, after editing the tables in
       `docs/06-skills.md`, and after merging skill work written in parallel — which
       is exactly when the two halves drift.

HOW    ./scripts/check-skill-docs.py
       Exit 0 when both sides agree, 1 otherwise. Prints each mismatch with the
       path that declares it.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SKILLS_DIR = REPO / ".claude/skills"
CHAPTER = REPO / "docs/06-skills.md"

# Only the first cell of a table row counts as a row's subject. The tables also
# carry a "hands off to" column naming neighbouring skills, and those are written
# as bare backticked names on purpose: if a hand-off cell could satisfy this check,
# a skill mentioned by its neighbour but never given a row of its own would pass —
# which is precisely the case the check exists to catch.
ROW_LINK = re.compile(r"^\|\s*\[[^\]]+\]\(\.\./\.claude/skills/([a-z0-9][a-z0-9-]*)/SKILL\.md\)")


def skills_on_disk() -> dict[str, bool]:
    """Immediate subdirectories of .claude/skills, mapped to whether SKILL.md exists.

    A directory with no SKILL.md is reported rather than skipped. Nothing loads it,
    so it is either a rename half-done or a skill whose body never landed.
    """
    return {
        entry.name: (entry / "SKILL.md").is_file()
        for entry in sorted(SKILLS_DIR.iterdir())
        if entry.is_dir()
    }


def skills_documented() -> dict[str, list[int]]:
    """Skill names linked from the first cell of a table row, with line numbers."""
    documented: dict[str, list[int]] = {}
    for number, line in enumerate(CHAPTER.read_text(encoding="utf-8").splitlines(), 1):
        match = ROW_LINK.match(line)
        if match:
            documented.setdefault(match.group(1), []).append(number)
    return documented


def main() -> int:
    on_disk = skills_on_disk()
    documented = skills_documented()

    undocumented = sorted(set(on_disk) - set(documented))
    orphan_rows = sorted(set(documented) - set(on_disk))
    bodyless = sorted(name for name, has_body in on_disk.items() if not has_body)
    duplicated = sorted(name for name, lines in documented.items() if len(lines) > 1)

    print(f".claude/skills holds {len(on_disk)} skill directories")
    print(f"docs/06-skills.md documents {len(documented)}")

    if undocumented:
        print("\nON DISK, NOT DOCUMENTED — a skill the model can load and no reader "
              "can find; docs/06-skills.md needs a row for each:")
        for name in undocumented:
            print(f"  {name:36s} .claude/skills/{name}/")

    if orphan_rows:
        print("\nDOCUMENTED, NOT ON DISK — a row linking a path that is not there:")
        for name in orphan_rows:
            where = ", ".join(f"line {line}" for line in documented[name])
            print(f"  {name:36s} docs/06-skills.md {where}")

    if bodyless:
        print("\nDIRECTORY WITH NO SKILL.md — nothing loads it:")
        for name in bodyless:
            print(f"  {name:36s} .claude/skills/{name}/")

    if duplicated:
        print("\nDOCUMENTED TWICE — one skill, two rows, and they will disagree:")
        for name in duplicated:
            where = ", ".join(f"line {line}" for line in documented[name])
            print(f"  {name:36s} docs/06-skills.md {where}")

    if not (undocumented or orphan_rows or bodyless or duplicated):
        print("\nevery skill has exactly one row, and every row has a skill")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
