#!/usr/bin/env python3
"""Prove that Spam Control semantic contracts are actually wired to their consumers.

A semantic test that does not run when the source it protects changes is not a
contract. Derive Java consumers from each contract script and verify workflow
branch/path coverage mechanically.
"""
from __future__ import annotations

import fnmatch
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OPERATIONAL_BRANCH = "feature/spam-control-p0"
CHANGESET_BRANCH = "changeset/spam-control-family-correction-semantics"

CONTRACTS = {
    "bootstrap": (
        ".github/scripts/verify_spam_control_bootstrap_contract.py",
        ".github/workflows/verify-spam-control-bootstrap-contract.yml",
    ),
    "alias-sideeffects": (
        ".github/scripts/verify_spam_control_alias_sideeffects.py",
        ".github/workflows/verify-spam-control-alias-sideeffects.yml",
    ),
    "reset-undo": (
        ".github/scripts/verify_spam_control_reset_undo_contract.py",
        ".github/workflows/verify-spam-control-reset-undo-contract.yml",
    ),
    "observability": (
        ".github/scripts/verify_spam_control_observability_contract.py",
        ".github/workflows/verify-spam-control-observability-contract.yml",
    ),
    "exact-family-retirement": (
        ".github/scripts/verify_spam_exact_family_retirement.py",
        ".github/workflows/verify-spam-exact-family-retirement.yml",
    ),
    "reply-alias-authority": (
        ".github/scripts/verify_reply_alias_authority.py",
        ".github/workflows/verify-reply-alias-authority.yml",
    ),
}


def fail(message: str) -> None:
    raise SystemExit("FAIL: " + message)


def yaml_list(text: str, key: str) -> list[str]:
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = re.match(rf"^(\s*){re.escape(key)}:\s*$", line)
        if not match:
            continue
        base_indent = len(match.group(1))
        values: list[str] = []
        for candidate in lines[index + 1:]:
            if not candidate.strip():
                continue
            indent = len(candidate) - len(candidate.lstrip())
            if indent <= base_indent:
                break
            item = re.match(r"^\s*-\s*['\"]?(.+?)['\"]?\s*$", candidate)
            if item:
                values.append(item.group(1))
        return values
    return []


def consumed_java_paths(script_text: str) -> set[str]:
    paths = set(re.findall(
        r"ROOT\s*/\s*['\"](app/src/[^'\"]+\.java)['\"]",
        script_text,
    ))
    paths.update(re.findall(
        r"read\(\s*['\"](app/src/[^'\"]+\.java)['\"]\s*\)",
        script_text,
    ))
    return paths


def covered(source: str, patterns: list[str]) -> bool:
    return any(source == pattern or fnmatch.fnmatchcase(source, pattern)
               for pattern in patterns)


def verify_contract(name: str, script_path: str, workflow_path: str) -> None:
    script_file = ROOT / script_path
    workflow_file = ROOT / workflow_path
    if not script_file.is_file():
        fail(f"{name}: missing contract script {script_path}")
    if not workflow_file.is_file():
        fail(f"{name}: missing workflow {workflow_path}")

    script = script_file.read_text(encoding="utf-8")
    workflow = workflow_file.read_text(encoding="utf-8")
    branches = yaml_list(workflow, "branches")
    paths = yaml_list(workflow, "paths")

    for branch in (OPERATIONAL_BRANCH, CHANGESET_BRANCH):
        if branch not in branches:
            fail(f"{name}: workflow does not run on required branch {branch}")

    for own_path in (script_path, workflow_path):
        if not covered(own_path, paths):
            fail(f"{name}: workflow does not trigger on its own file {own_path}")

    consumers = consumed_java_paths(script)
    if not consumers:
        fail(f"{name}: no Java consumers derived from contract script")

    missing = sorted(source for source in consumers if not covered(source, paths))
    if missing:
        fail(f"{name}: workflow path coverage missing consumers: {', '.join(missing)}")

    print(f"PASS {name}: branches={len(branches)} consumers={len(consumers)} paths={len(paths)}")
    for source in sorted(consumers):
        print(f"  COVERED {source}")


def main() -> None:
    for name, (script, workflow) in CONTRACTS.items():
        verify_contract(name, script, workflow)
    print("PASS: every selected Spam Control semantic contract is wired to every Java source it reads")
    print("PASS: operational and active ChangeSet branches are covered")


if __name__ == "__main__":
    main()
