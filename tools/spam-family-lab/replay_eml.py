#!/usr/bin/env python3
"""Local-only replay lab for FairEmail Spam Families.

Accepts a directory of .eml files or a ZIP containing .eml files. The messages
are never uploaded by this tool. It prints alias distribution, duplicates,
pairwise family similarity and greedy clusters for threshold tuning.

The feature/scoring logic intentionally mirrors the pure-Java prototype closely
enough to catch threshold regressions before wiring automatic mail actions.
"""

from __future__ import annotations

import argparse
import email
from email import policy
from email.utils import parseaddr
import io
import math
from pathlib import Path
import re
import zipfile
from urllib.parse import urlparse

URL = re.compile(r'''(?i)https?://[^\s\"'<>]+|www\.[^\s\"'<>]+''')
EMAIL = re.compile(r"(?i)[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}")
NUMBER = re.compile(r"(?<![\w])[+\-]?\d[\d.,:/\-]*(?![\w])")
LONG_ID = re.compile(r"(?i)\b[a-z0-9_-]{12,}\b")
TAG = re.compile(r"(?is)<\s*(/?)\s*([a-z0-9]+)(?:\s[^>]*)?>")
HREF = re.compile(r'''(?is)href\s*=\s*([\"'])(.*?)\1''')
TOKEN = re.compile(r"[\w<>]+", re.UNICODE)


def normalize(text: str | None) -> str:
    value = (text or "").lower()
    value = URL.sub(" <url> ", value)
    value = EMAIL.sub(" <email> ", value)
    value = NUMBER.sub(" <num> ", value)
    value = LONG_ID.sub(" <id> ", value)
    return re.sub(r"\s+", " ", value).strip()


def tokens(text: str | None) -> list[str]:
    return [t for t in TOKEN.findall(normalize(text)) if len(t) > 1 or t.startswith("<")]


def text_features(subject: str, plain: str, html: str) -> set[tuple]:
    result: set[tuple] = set()
    body = plain if plain.strip() else re.sub(r"(?is)<[^>]+>", " ", html)
    for channel, raw in (("s", subject), ("b", body)):
        words = tokens(raw)
        for word in words:
            result.add((channel, "u", word))
        for width in range(2, 5):
            for i in range(len(words) - width + 1):
                result.add((channel, width, tuple(words[i : i + width])))
    return result


def structure_features(html: str) -> set[tuple]:
    result: set[tuple] = set()
    tags: list[str] = []
    for match in TAG.finditer(html or ""):
        tag = match.group(2).lower()
        if tag in {"script", "style"}:
            continue
        marker = ("+" if not match.group(1) else "-") + tag
        tags.append(marker)
        result.add(("tag", marker))
    for width in range(2, 6):
        for i in range(len(tags) - width + 1):
            result.add(("dom", tuple(tags[i : i + width])))

    lower = (html or "").lower()
    for name in ("table", "img", "a", "button"):
        count = lower.count("<" + name)
        bucket = 0 if count == 0 else 1 if count == 1 else 2 if count <= 3 else 3 if count <= 7 else 4
        result.add(("count", name, bucket))
    return result


def _last_labels(host: str, count: int = 2) -> str:
    parts = host.split(".")
    return host if len(parts) <= count else ".".join(parts[-count:])


def link_features(raw: str) -> set[tuple]:
    result: set[tuple] = set()
    values = [m.group(2) for m in HREF.finditer(raw or "")]
    values += [m.group() for m in URL.finditer(raw or "")]
    for value in values:
        if value.startswith("www."):
            value = "https://" + value
        try:
            uri = urlparse(value)
            host = (uri.hostname or "").lower()
            if host:
                if host.startswith("www."):
                    host = host[4:]
                result.add(("host", host))
                result.add(("domain", _last_labels(host)))
            emitted = 0
            for segment in (uri.path or "").split("/"):
                segment = LONG_ID.sub("<id>", NUMBER.sub("<n>", segment.lower()))
                if not segment:
                    continue
                result.add(("path", emitted, segment))
                emitted += 1
                if emitted >= 3:
                    break
        except ValueError:
            pass
    return result


def _shape(value: str) -> str:
    out: list[str] = []
    previous = None
    run = 0
    for char in value:
        current = "a" if char.isalpha() else "9" if char.isdigit() else "x"
        if current == previous:
            run += 1
        else:
            if previous is not None:
                out.append(previous + str(min(run, 8)))
            previous = current
            run = 1
    if previous is not None:
        out.append(previous + str(min(run, 8)))
    return "".join(out)


def sender_features(address: str, name: str) -> set[tuple]:
    result: set[tuple] = set()
    value = (address or "").lower().strip()
    if "@" in value:
        local, domain = value.rsplit("@", 1)
        result.add(("sdomain", domain))
        result.add(("sbase", _last_labels(domain)))
        result.add(("slocal-shape", _shape(local)))
    for token in tokens(name):
        result.add(("sname", token))
    return result


def jaccard(a: set, b: set) -> float:
    return len(a & b) / len(a | b) if a and b else 0.0


def weighted(parts: list[tuple[float, set, set, float]]) -> float:
    value = 0.0
    weights = 0.0
    for score, a, b, weight in parts:
        if a and b:
            value += score * weight
            weights += weight
    return value / weights if weights else 0.0


def similarity(a: dict[str, set], b: dict[str, set]) -> tuple[float, tuple[float, float, float, float]]:
    text = jaccard(a["text"], b["text"])
    structure = jaccard(a["structure"], b["structure"])
    links = jaccard(a["links"], b["links"])
    sender = jaccard(a["sender"], b["sender"])

    balanced = weighted([
        (text, a["text"], b["text"], 0.48),
        (structure, a["structure"], b["structure"], 0.30),
        (links, a["links"], b["links"], 0.16),
        (sender, a["sender"], b["sender"], 0.06),
    ])
    template = weighted([
        (structure, a["structure"], b["structure"], 0.60),
        (links, a["links"], b["links"], 0.25),
        (text, a["text"], b["text"], 0.15),
    ])
    content = weighted([
        (text, a["text"], b["text"], 0.72),
        (links, a["links"], b["links"], 0.18),
        (structure, a["structure"], b["structure"], 0.10),
    ])
    infrastructure = weighted([
        (links, a["links"], b["links"], 0.58),
        (structure, a["structure"], b["structure"], 0.27),
        (sender, a["sender"], b["sender"], 0.15),
    ])

    raw = max(balanced, template, content, infrastructure)
    evidence_count = min(sum(map(len, a.values())), sum(map(len, b.values())))
    evidence = min(1.0, math.sqrt(evidence_count / 30.0))
    return raw * (0.72 + 0.28 * evidence), (text, structure, links, sender)


def message_bytes(source: Path):
    if source.is_dir():
        for path in sorted(source.rglob("*.eml")):
            yield path.name, path.read_bytes()
        return
    if zipfile.is_zipfile(source):
        with zipfile.ZipFile(source) as archive:
            for name in sorted(archive.namelist()):
                if name.lower().endswith(".eml") and not name.endswith("/"):
                    yield Path(name).name, archive.read(name)
        return
    raise SystemExit(f"Expected a directory or ZIP: {source}")


def decode_part(part) -> str:
    try:
        value = part.get_content()
        return value if isinstance(value, str) else str(value)
    except Exception:
        payload = part.get_payload(decode=True) or b""
        return payload.decode(part.get_content_charset() or "utf-8", errors="replace")


def parse_messages(source: Path) -> list[dict]:
    result = []
    for filename, raw in message_bytes(source):
        msg = email.message_from_binary_file(io.BytesIO(raw), policy=policy.default)
        plain: list[str] = []
        html: list[str] = []
        parts = msg.walk() if msg.is_multipart() else [msg]
        for part in parts:
            if part.get_content_disposition() == "attachment":
                continue
            ctype = part.get_content_type()
            if ctype == "text/plain":
                plain.append(decode_part(part))
            elif ctype == "text/html":
                html.append(decode_part(part))

        sender_name, sender_address = parseaddr(str(msg.get("From", "")))
        subject = str(msg.get("Subject", ""))
        plain_text = "\n".join(plain)
        html_text = "\n".join(html)
        delivered_to = (
            msg.get("Envelope-To")
            or msg.get("X-Envelope-To")
            or msg.get("X-Original-To")
            or msg.get("Delivered-To")
            or msg.get("X-Delivered-To")
        )
        features = {
            "text": text_features(subject, plain_text, html_text),
            "structure": structure_features(html_text),
            "links": link_features(html_text + "\n" + plain_text),
            "sender": sender_features(sender_address, sender_name),
        }
        result.append({
            "filename": filename,
            "message_id": str(msg.get("Message-ID", "")),
            "subject": subject,
            "alias": str(delivered_to or ""),
            "features": features,
        })
    return result


def unique_messages(messages: list[dict]) -> tuple[list[dict], int]:
    seen: set[str] = set()
    result = []
    duplicates = 0
    for message in messages:
        key = message["message_id"] or "file:" + message["filename"]
        if key in seen:
            duplicates += 1
            continue
        seen.add(key)
        result.append(message)
    return result, duplicates


def greedy_clusters(messages: list[dict], threshold: float) -> list[list[int]]:
    groups: list[list[int]] = []
    for index, message in enumerate(messages):
        winner = None
        for group_index, group in enumerate(groups):
            score = max(similarity(message["features"], messages[other]["features"])[0] for other in group)
            if winner is None or score > winner[0]:
                winner = (score, group_index)
        if winner is not None and winner[0] >= threshold:
            groups[winner[1]].append(index)
        else:
            groups.append([index])
    return groups


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Directory of .eml files or ZIP archive")
    parser.add_argument("--threshold", type=float, default=0.56)
    parser.add_argument("--pairs", type=int, default=20, help="Number of strongest pairs to print")
    args = parser.parse_args()

    messages, duplicates = unique_messages(parse_messages(args.source))
    print(f"messages={len(messages)} duplicates={duplicates}")

    aliases: dict[str, int] = {}
    for message in messages:
        alias = message["alias"].strip().lower() or "<unknown>"
        aliases[alias] = aliases.get(alias, 0) + 1
    print("\naliases:")
    for alias, count in sorted(aliases.items(), key=lambda item: (-item[1], item[0])):
        print(f"{count:4d}  {alias}")

    pairs = []
    for i in range(len(messages)):
        for j in range(i + 1, len(messages)):
            score, channels = similarity(messages[i]["features"], messages[j]["features"])
            pairs.append((score, i, j, channels))

    print("\nstrongest pairs:")
    for score, i, j, channels in sorted(pairs, reverse=True)[: args.pairs]:
        left, right = messages[i], messages[j]
        t, h, l, s = channels
        print(f"{score:.3f}  {left['filename']} <> {right['filename']}  "
              f"text={t:.2f} html={h:.2f} links={l:.2f} sender={s:.2f}")

    groups = greedy_clusters(messages, args.threshold)
    print(f"\nclusters threshold={args.threshold:.3f}: {len(groups)}")
    for number, group in enumerate(sorted(groups, key=len, reverse=True), start=1):
        names = ", ".join(messages[index]["filename"] for index in group)
        print(f"{number:2d}. n={len(group):2d}  {names}")


if __name__ == "__main__":
    main()
