#!/usr/bin/env python3
"""Executable regression contract for Spam Control bootstrap/message authority.

This protects the semantics discovered from the known-good system history and
from the real phone failure: Junk must be reviewable before any family exists,
and missing Envelope-To must never make the message disappear.
"""
from __future__ import annotations

import ast
import re
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DAO = ROOT / "app/src/main/java/eu/faircode/email/DaoSpamMessage.java"
BULK = ROOT / "app/src/main/java/eu/faircode/email/SpamExactBulkPropagator.java"
SNAPSHOT = ROOT / "app/src/main/java/eu/faircode/email/SpamLearningSnapshot.java"
STORE = ROOT / "app/src/main/java/eu/faircode/email/SpamMessageStore.java"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL: {message}")


def extract_review_sql() -> str:
    text = DAO.read_text(encoding="utf-8")
    marker = "List<EntitySpamMessage> getReviewQueue"
    method = text.index(marker)
    start = text.rfind("@Query(", 0, method)
    require(start >= 0, "getReviewQueue @Query not found")
    expression = text[start + len("@Query("):method]

    token_re = re.compile(
        r'"(?:\\.|[^"\\])*"|'
        r'EntitySpamMessage\.LABEL_UNKNOWN|'
        r'EntityFolder\.JUNK'
    )
    parts: list[str] = []
    for token in token_re.findall(expression):
        if token == "EntitySpamMessage.LABEL_UNKNOWN":
            parts.append("0")
        elif token == "EntityFolder.JUNK":
            parts.append("Junk")
        else:
            parts.append(ast.literal_eval(token))
    sql = "".join(parts)
    require(sql.startswith("SELECT * FROM spam_message"),
            "review authority must be spam_message")
    require("folder_type = 'Junk'" in sql,
            "Junk must be an independent review admission reason")
    require("predicted_family_id IS NOT NULL" in sql,
            "exact prediction admission path missing")
    require("traffic_verdict = 'SUSPICIOUS'" in sql,
            "suspicious alias enrichment admission path missing")
    require("delivered_to IS NOT NULL" not in sql,
            "Envelope-To must not be an admission requirement")
    require("JOIN spam_family" not in sql and "FROM spam_family" not in sql,
            "review must not require an existing family")
    return sql


def verify_review_semantics(sql: str) -> None:
    db = sqlite3.connect(":memory:")
    db.execute("""
        CREATE TABLE spam_message (
          account_uuid TEXT NOT NULL,
          message_id INTEGER NOT NULL,
          received INTEGER NOT NULL,
          folder_type TEXT,
          delivered_to TEXT,
          label INTEGER NOT NULL DEFAULT 0,
          family_id INTEGER,
          predicted_family_id INTEGER,
          family_score REAL,
          family_assessed_at INTEGER,
          PRIMARY KEY(account_uuid, message_id)
        )
    """)
    db.execute("""
        CREATE TABLE alias_delivery (
          account_uuid TEXT NOT NULL,
          message_id INTEGER NOT NULL,
          traffic_verdict TEXT,
          PRIMARY KEY(account_uuid, message_id)
        )
    """)

    # Crucially there is NO spam_family table in this test. A bootstrap query
    # that secretly requires families therefore fails instead of passing.
    rows = [
        # id, folder, delivered_to, label, predicted, score
        (1, "Junk", None, 0, None, None),       # aliasless Junk: MUST review
        (2, "Inbox", None, 0, None, None),      # plain Inbox: not review by itself
        (3, "Inbox", None, 0, None, None),      # suspicious alias enrichment
        (4, "Inbox", None, 0, 77, 1.0),         # exact prediction
        (5, "Junk", None, 1, None, None),       # explicit HAM Junk
        (6, "Junk", None, 2, None, None),       # explicit SPAM Junk
    ]
    for mid, folder, delivered, label, predicted, score in rows:
        db.execute(
            "INSERT INTO spam_message(account_uuid,message_id,received,folder_type,delivered_to,label,predicted_family_id,family_score) VALUES(?,?,?,?,?,?,?,?)",
            ("acct", mid, 1000 + mid, folder, delivered, label, predicted, score),
        )
    db.execute(
        "INSERT INTO alias_delivery(account_uuid,message_id,traffic_verdict) VALUES(?,?,?)",
        ("acct", 3, "SUSPICIOUS"),
    )

    hidden = [r[1] for r in db.execute(sql, {
        "accountUuid": "acct",
        "includeReviewed": 0,
        "limit": 100,
    }).fetchall()]
    require(1 in hidden, "UNKNOWN Junk without Envelope-To disappeared")
    require(2 not in hidden, "plain UNKNOWN Inbox became review work without evidence")
    require(3 in hidden, "suspicious alias enrichment no longer admits Inbox message")
    require(4 in hidden, "exact predicted family no longer admits message")
    require(5 not in hidden and 6 not in hidden,
            "hide-reviewed contract no longer hides human HAM/SPAM")

    shown = [r[1] for r in db.execute(sql, {
        "accountUuid": "acct",
        "includeReviewed": 1,
        "limit": 100,
    }).fetchall()]
    require(5 in shown and 6 in shown,
            "include-reviewed contract no longer exposes reviewed Junk")


def verify_bulk_ham_invariant() -> None:
    dao = DAO.read_text(encoding="utf-8")
    bulk = BULK.read_text(encoding="utf-8")
    require("AND label = \" + EntitySpamMessage.LABEL_UNKNOWN" in dao,
            "getUnknownAfter must remain UNKNOWN-only")
    require("messageDao.getUnknownAfter" in bulk,
            "bulk propagation must consume the UNKNOWN-only canonical query")
    require("SpamIntelligence.learnSpam" in bulk,
            "bulk must use normal Spam learning path so alias side-effects survive when present")


def verify_reset_keeps_index() -> None:
    dao = DAO.read_text(encoding="utf-8")
    snap = SNAPSHOT.read_text(encoding="utf-8")
    require("int resetLearning" in dao and "UPDATE spam_message SET" in dao,
            "message reset must clear learning in place")
    require("resetAllSpamMessageLearning" in snap,
            "reset snapshot path must reset canonical message learning")
    require("deleteAllSpamMessages" not in snap,
            "reset must never delete canonical message index")


def verify_store_is_alias_optional() -> None:
    store = STORE.read_text(encoding="utf-8")
    require("AliasRegistry.normalizeAddress(deliveredTo)" in store,
            "message index should retain normalized Envelope-To when available")
    require("deliveredTo == null" not in store,
            "message store must not reject missing Envelope-To")


def main() -> None:
    sql = extract_review_sql()
    verify_review_semantics(sql)
    verify_bulk_ham_invariant()
    verify_reset_keeps_index()
    verify_store_is_alias_optional()
    print("PASS: Spam Control bootstrap/message-authority contract")
    print("PASS: aliasless Junk is reviewable with zero families")
    print("PASS: Inbox remains contextual, exact/suspicious paths preserved")
    print("PASS: bulk preserves HAM and reset preserves message index")


if __name__ == "__main__":
    main()
