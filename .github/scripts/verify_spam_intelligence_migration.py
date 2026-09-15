#!/usr/bin/env python3
"""Verify migration-created SpamIntelligenceDB tables against Room schemas.

The script reads both sources of truth at runtime:
  * SQL statements are extracted from SpamIntelligenceDB.java.
  * Expected table/index metadata is read from Room's generated schema JSON.

This catches migration/entity drift without needing an emulator.
"""

from __future__ import annotations

import ast
import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/eu/faircode/email/SpamIntelligenceDB.java"
SCHEMA_ROOT = ROOT / "app/schemas"
CASES = [
    ("MIGRATION_8_9", 9, "spam_family_exclusion"),
    ("MIGRATION_9_10", 10, "spam_action_history"),
]


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def extract_sql(migration: str) -> list[str]:
    source = JAVA.read_text(encoding="utf-8")
    start = source.find(migration)
    if start < 0:
        fail(f"{migration} not found in {JAVA}")
    end = source.find("\n    };", start)
    if end < 0:
        fail(f"Could not locate end of {migration}")
    block = source[start:end]

    calls = re.findall(r"db\.execSQL\((.*?)\);", block, flags=re.DOTALL)
    if not calls:
        fail(f"No execSQL statements found in {migration}")

    statements: list[str] = []
    for expression in calls:
        literals = re.findall(r'"((?:\\.|[^"\\])*)"', expression)
        if not literals:
            fail(f"Could not decode execSQL expression: {expression!r}")
        try:
            statement = "".join(ast.literal_eval(f'"{literal}"') for literal in literals)
        except Exception as exc:
            fail(f"Could not decode Java string literal: {exc}")
        statements.append(statement)
    return statements


def find_room_entity(version: int, table: str) -> tuple[Path, dict]:
    if not SCHEMA_ROOT.exists():
        fail(f"Room schema directory not generated: {SCHEMA_ROOT}")

    candidates = sorted(SCHEMA_ROOT.rglob(f"{version}.json"))
    if not candidates:
        fail(f"No Room v{version} schema JSON found after compilation")

    for path in candidates:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
        database = data.get("database", {})
        for entity in database.get("entities", []):
            if entity.get("tableName") == table:
                return path, entity
    fail(f"Room v{version} schema found, but table {table!r} is missing")


def normalize_affinity(value: str | None) -> str:
    value = (value or "").upper()
    if "INT" in value:
        return "INTEGER"
    if any(token in value for token in ("CHAR", "CLOB", "TEXT")):
        return "TEXT"
    if "BLOB" in value or value == "":
        return "BLOB"
    if any(token in value for token in ("REAL", "FLOA", "DOUB")):
        return "REAL"
    return "NUMERIC"


def verify_case(migration: str, version: int, table: str) -> None:
    statements = extract_sql(migration)
    schema_path, entity = find_room_entity(version, table)

    db = sqlite3.connect(":memory:")
    try:
        for statement in statements:
            db.execute(statement)

        actual_rows = db.execute(f"PRAGMA table_info('{table}')").fetchall()
        if not actual_rows:
            fail(f"{migration} did not create table {table}")

        actual_columns = {
            row[1]: {
                "affinity": normalize_affinity(row[2]),
                "notNull": bool(row[3]),
                "pk": int(row[5]),
            }
            for row in actual_rows
        }
        expected_columns = {
            field["columnName"]: {
                "affinity": normalize_affinity(field.get("affinity")),
                "notNull": bool(field.get("notNull")),
            }
            for field in entity.get("fields", [])
        }

        if set(actual_columns) != set(expected_columns):
            fail(
                f"{migration} column set mismatch: "
                f"actual={sorted(actual_columns)} expected={sorted(expected_columns)}"
            )

        for name, expected in expected_columns.items():
            actual = actual_columns[name]
            if actual["affinity"] != expected["affinity"]:
                fail(
                    f"{migration} affinity mismatch for {name}: "
                    f"actual={actual['affinity']} expected={expected['affinity']}"
                )
            if actual["notNull"] != expected["notNull"]:
                fail(
                    f"{migration} nullability mismatch for {name}: "
                    f"actual={actual['notNull']} expected={expected['notNull']}"
                )

        expected_pk = list(entity.get("primaryKey", {}).get("columnNames", []))
        actual_pk = [
            name
            for name, metadata in sorted(
                actual_columns.items(), key=lambda item: item[1]["pk"] or 10_000
            )
            if metadata["pk"] > 0
        ]
        if actual_pk != expected_pk:
            fail(f"{migration} primary key mismatch: actual={actual_pk} expected={expected_pk}")

        expected_indices = {
            index["name"]: {
                "unique": bool(index.get("unique")),
                "columns": list(index.get("columnNames", [])),
            }
            for index in entity.get("indices", [])
        }
        index_rows = db.execute(f"PRAGMA index_list('{table}')").fetchall()
        actual_named = {
            row[1]: {
                "unique": bool(row[2]),
                "columns": [
                    info[2]
                    for info in db.execute(f"PRAGMA index_info('{row[1]}')").fetchall()
                ],
            }
            for row in index_rows
            if not row[1].startswith("sqlite_autoindex_")
        }

        if set(actual_named) != set(expected_indices):
            fail(
                f"{migration} index set mismatch: "
                f"actual={sorted(actual_named)} expected={sorted(expected_indices)}"
            )

        for name, expected in expected_indices.items():
            actual = actual_named[name]
            if actual != expected:
                fail(f"{migration} index mismatch for {name}: actual={actual} expected={expected}")

        print(
            f"PASS {migration}: {table} matches Room schema from "
            f"{schema_path.relative_to(ROOT)}"
        )
        print(f"Executed {len(statements)} migration statements")
    finally:
        db.close()


def main() -> None:
    for migration, version, table in CASES:
        verify_case(migration, version, table)


if __name__ == "__main__":
    main()
