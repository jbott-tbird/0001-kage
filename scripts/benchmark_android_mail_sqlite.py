#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/

"""Host SQLite baseline for current Room mailbox queries; Android timing remains separate."""

import argparse
import json
import sqlite3
import statistics
import time
from pathlib import Path


SCHEMA = (Path(__file__).resolve().parents[1] / "Android/app/schemas/"
          "org.foxred.kage.data.local.MailDatabase/1.json")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, default=200_000)
    parser.add_argument("--repeats", type=int, default=5)
    args = parser.parse_args()
    if args.rows < 2 or args.repeats < 1:
        parser.error("rows must be at least 2 and repeats must be positive")

    schema = json.loads(SCHEMA.read_text())["database"]
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA synchronous=OFF")
    for entity in schema["entities"]:
        if entity["tableName"] not in {"messages", "attachments"}:
            continue
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

    message = next(e for e in schema["entities"] if e["tableName"] == "messages")
    columns = [field["columnName"] for field in message["fields"]]
    defaults = {field["columnName"]: 0 if field["affinity"] == "INTEGER" else ""
                for field in message["fields"]}
    insert = ("INSERT INTO messages (" + ",".join(f'"{c}"' for c in columns) +
              ") VALUES (" + ",".join("?" for _ in columns) + ")")

    def row(number: int) -> tuple:
        values = defaults | {
            "id": f"m{number:08d}", "accountId": "a",
            "folderId": "inbox" if number < args.rows // 2 else "archive",
            "sender": "Sender", "senderAddress": "sender@example.test",
            "to": "a@example.test", "subject": "Synthetic message",
            "body": "Short body", "preview": "Short body",
            "receivedAt": f"2026-01-{1 + number % 28:02d}T00:00:00Z",
            "uidValidity": 1, "uid": number + 1,
            "bodyDownloaded": 0 if number % 100 == 0 else 1,
            "isRead": 0 if number % 5 == 0 else 1,
            "envelopeJson": "{}",
        }
        for nullable in ("html", "relatedGroup", "remoteEmailId", "rawMessagePath",
                         "lastSeenPassId"):
            values[nullable] = None
        return tuple(values[column] for column in columns)

    db.executemany(insert, (row(number) for number in range(args.rows)))
    inbox_end = args.rows // 2 + 1
    deep_cursor = "2026-01-15T00:00:00Z"
    queries = {
        "history bodies": (
            "SELECT id, uid, receivedAt FROM messages WHERE folderId=? AND uidValidity=? "
            "AND uid>=? AND uid<? AND bodyDownloaded=0 ORDER BY uid DESC LIMIT ?",
            ("inbox", 1, 1, inbox_end, 100)),
        "recent bodies": (
            "SELECT id, uid, receivedAt FROM messages WHERE folderId=? AND uidValidity=? "
            "AND uid IS NOT NULL AND uid<? AND receivedAt>=? AND bodyDownloaded=0 "
            "ORDER BY uid DESC LIMIT ?",
            ("inbox", 1, inbox_end, "2026-01-01", 100)),
        "folder page": (
            "SELECT m.id, m.receivedAt, (SELECT COUNT(*) FROM attachments a "
            "WHERE a.messageId=m.id) AS attachmentCount FROM messages m "
            "WHERE m.folderId IN (?) AND (? IS NULL OR m.receivedAt<? OR "
            "(m.receivedAt=? AND m.id<?)) "
            "ORDER BY m.receivedAt DESC, m.id DESC LIMIT ?",
            ("inbox", None, None, None, None, 51)),
        "unified page": (
            "SELECT m.id, m.receivedAt, (SELECT COUNT(*) FROM attachments a "
            "WHERE a.messageId=m.id) AS attachmentCount FROM messages m "
            "WHERE m.folderId IN (?, ?) AND (? IS NULL OR m.receivedAt<? OR "
            "(m.receivedAt=? AND m.id<?)) "
            "ORDER BY m.receivedAt DESC, m.id DESC LIMIT ?",
            ("inbox", "archive", None, None, None, None, 51)),
        "folder deep page": (
            "SELECT m.id, m.receivedAt, (SELECT COUNT(*) FROM attachments a "
            "WHERE a.messageId=m.id) AS attachmentCount FROM messages m "
            "WHERE m.folderId IN (?) AND (? IS NULL OR m.receivedAt<? OR "
            "(m.receivedAt=? AND m.id<?)) "
            "ORDER BY m.receivedAt DESC, m.id DESC LIMIT ?",
            ("inbox", deep_cursor, deep_cursor, deep_cursor, "m99999999", 51)),
        "unified deep page": (
            "SELECT m.id, m.receivedAt, (SELECT COUNT(*) FROM attachments a "
            "WHERE a.messageId=m.id) AS attachmentCount FROM messages m "
            "WHERE m.folderId IN (?, ?) AND (? IS NULL OR m.receivedAt<? OR "
            "(m.receivedAt=? AND m.id<?)) "
            "ORDER BY m.receivedAt DESC, m.id DESC LIMIT ?",
            ("inbox", "archive", deep_cursor, deep_cursor, deep_cursor, "m99999999", 51)),
        "folder seek page": (
            "SELECT m.id, m.receivedAt, (SELECT COUNT(*) FROM attachments a "
            "WHERE a.messageId=m.id) AS attachmentCount FROM messages m "
            "WHERE m.folderId IN (?) AND (m.receivedAt, m.id) < (?, ?) "
            "ORDER BY m.receivedAt DESC, m.id DESC LIMIT ?",
            ("inbox", deep_cursor, "m99999999", 51)),
        "unified seek page": (
            "SELECT m.id, m.receivedAt, (SELECT COUNT(*) FROM attachments a "
            "WHERE a.messageId=m.id) AS attachmentCount FROM messages m "
            "WHERE m.folderId IN (?, ?) AND (m.receivedAt, m.id) < (?, ?) "
            "ORDER BY m.receivedAt DESC, m.id DESC LIMIT ?",
            ("inbox", "archive", deep_cursor, "m99999999", 51)),
        "oldest deep page": (
            "SELECT m.id, m.receivedAt FROM messages m WHERE m.folderId IN (?) "
            "AND (? IS NULL OR m.receivedAt>? OR (m.receivedAt=? AND m.id>?)) "
            "ORDER BY m.receivedAt ASC, m.id ASC LIMIT ?",
            ("inbox", deep_cursor, deep_cursor, deep_cursor, "m99999999", 51)),
        "oldest seek page": (
            "SELECT m.id, m.receivedAt FROM messages m WHERE m.folderId IN (?) "
            "AND (m.receivedAt, m.id) > (?, ?) "
            "ORDER BY m.receivedAt ASC, m.id ASC LIMIT ?",
            ("inbox", deep_cursor, "m99999999", 51)),
        "global search deep scan": (
            "SELECT * FROM messages WHERE (? IS NULL OR receivedAt<? OR "
            "(receivedAt=? AND id<?)) "
            "ORDER BY receivedAt DESC, id DESC LIMIT ?",
            (deep_cursor, deep_cursor, deep_cursor, "m99999999", 4)),
        "global search seek scan": (
            "SELECT * FROM messages WHERE (receivedAt, id) < (?, ?) "
            "ORDER BY receivedAt DESC, id DESC LIMIT ?",
            (deep_cursor, "m99999999", 4)),
        "account search deep scan": (
            "SELECT * FROM messages WHERE accountId=? AND (? IS NULL OR "
            "receivedAt<? OR (receivedAt=? AND id<?)) "
            "ORDER BY receivedAt DESC, id DESC LIMIT ?",
            ("a", deep_cursor, deep_cursor, deep_cursor, "m99999999", 4)),
        "account search seek scan": (
            "SELECT * FROM messages WHERE accountId=? AND (receivedAt, id) < (?, ?) "
            "ORDER BY receivedAt DESC, id DESC LIMIT ?",
            ("a", deep_cursor, "m99999999", 4)),
        "oldest search deep scan": (
            "SELECT * FROM messages WHERE (? IS NULL OR receivedAt>? OR "
            "(receivedAt=? AND id>?)) "
            "ORDER BY receivedAt ASC, id ASC LIMIT ?",
            (deep_cursor, deep_cursor, deep_cursor, "m99999999", 4)),
        "oldest search seek scan": (
            "SELECT * FROM messages WHERE (receivedAt, id) > (?, ?) "
            "ORDER BY receivedAt ASC, id ASC LIMIT ?",
            (deep_cursor, "m99999999", 4)),
        "unread counts": (
            "SELECT folderId, COUNT(*) AS unread FROM messages "
            "WHERE isRead=0 GROUP BY folderId", ()),
        "history remaining bodies": (
            "SELECT COUNT(*) FROM messages WHERE folderId=? AND uidValidity=? "
            "AND uid>=? AND bodyDownloaded=0", ("inbox", 1, 1)),
    }
    print(f"Room v{schema['version']} schema; host SQLite {sqlite3.sqlite_version}; "
          f"{args.rows:,} synthetic messages; {args.repeats} measured runs")
    deep = db.execute(*queries["folder deep page"]).fetchall()
    seek = db.execute(*queries["folder seek page"]).fetchall()
    assert deep == seek, "Folder keyset query changed the page"
    deep = db.execute(*queries["unified deep page"]).fetchall()
    seek = db.execute(*queries["unified seek page"]).fetchall()
    assert deep == seek, "Unified keyset query changed the page"
    deep = db.execute(*queries["oldest deep page"]).fetchall()
    seek = db.execute(*queries["oldest seek page"]).fetchall()
    assert deep == seek, "Ascending keyset query changed the page"
    for current, sought in (("global search deep scan", "global search seek scan"),
                            ("account search deep scan", "account search seek scan"),
                            ("oldest search deep scan", "oldest search seek scan")):
        assert db.execute(*queries[current]).fetchall() == db.execute(*queries[sought]).fetchall(), (
            f"Search keyset query changed {current}")
    for name, (sql, values) in queries.items():
        plan = [part[3] for part in db.execute("EXPLAIN QUERY PLAN " + sql, values)]
        samples = []
        result_count = 0
        for _ in range(args.repeats):
            started = time.perf_counter()
            result_count = len(db.execute(sql, values).fetchall())
            samples.append((time.perf_counter() - started) * 1000)
        print(f"{name}: {result_count} rows; median {statistics.median(samples):.2f} ms")
        for detail in plan:
            print(f"  {detail}")

    # Evaluate future Room migrations separately from the exported v1 baseline.
    sql, values = queries["unread counts"]
    baseline_unread = db.execute(sql, values).fetchall()
    db.execute("CREATE INDEX candidate_messages_isRead_folderId "
               "ON messages (isRead, folderId)")
    plan = [part[3] for part in db.execute("EXPLAIN QUERY PLAN " + sql, values)]
    samples = []
    for _ in range(args.repeats):
        started = time.perf_counter()
        indexed = db.execute(sql, values).fetchall()
        samples.append((time.perf_counter() - started) * 1000)
    assert indexed == baseline_unread, "Unread index changed counts"
    print(f"unread counts with candidate index: {len(indexed)} rows; "
          f"median {statistics.median(samples):.2f} ms")
    for detail in plan:
        print(f"  {detail}")

    # Compare the repeated full-history progress count with a covering pending-body index.
    sql, values = queries["history remaining bodies"]
    baseline_remaining = db.execute(sql, values).fetchone()[0]
    db.execute("CREATE INDEX candidate_messages_history_body "
               "ON messages (folderId, uidValidity, bodyDownloaded, uid)")
    plan = [part[3] for part in db.execute("EXPLAIN QUERY PLAN " + sql, values)]
    samples = []
    for _ in range(args.repeats):
        started = time.perf_counter()
        indexed_remaining = db.execute(sql, values).fetchone()[0]
        samples.append((time.perf_counter() - started) * 1000)
    assert indexed_remaining == baseline_remaining, "History pending-body index changed the count"
    print(f"history remaining bodies with candidate index: {indexed_remaining} pending; "
          f"median {statistics.median(samples):.2f} ms")
    for detail in plan:
        print(f"  {detail}")


if __name__ == "__main__":
    main()
