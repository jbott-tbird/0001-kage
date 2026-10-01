#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/

"""Check Room snapshots against Room 2.8.4's schema identity recipe."""

import hashlib
import json
from pathlib import Path


SCHEMAS = (Path(__file__).resolve().parents[1] / "Android/app/schemas/"
           "org.foxred.kage.data.local.MailDatabase")


def digest(parts: list[str]) -> str:
    joined = "?:?".join(parts) + ("?:?" if parts else "")
    return hashlib.md5(joined.encode()).hexdigest()


def sorted_keys(parts: list[str]) -> list[str]:
    return sorted(parts, key=str.lower)


def entity_hash(entity: dict) -> str:
    primary = entity["primaryKey"]
    columns = ", ".join(primary["columnNames"])
    primary_key = f"{str(primary['autoGenerate']).lower()}-[{columns}]"
    fields = []
    for field in entity["fields"]:
        key = (f"{field['columnName']}-{field['affinity']}-"
               f"{str(field.get('notNull', False)).lower()}")
        if "defaultValue" in field:
            key += f"-defaultValue={field['defaultValue']}"
        fields.append(key)
    indices = []
    for index in entity.get("indices", []):
        key = (f"{str(index['unique']).lower()}-{index['name']}-"
               f"{','.join(index['columnNames'])}")
        if index.get("orders"):
            key += "-" + ",".join(index["orders"])
        indices.append(key)
    foreign_keys = []
    for key in entity.get("foreignKeys", []):
        foreign_keys.append("-".join((
            key["table"], ",".join(key["referencedColumns"]),
            ",".join(key["columns"]), key["onDelete"], key["onUpdate"], "false",
        )))
    return digest([entity["tableName"], primary_key] + sorted_keys(fields) +
                  sorted_keys(indices) + sorted_keys(foreign_keys))


def main() -> None:
    database = json.loads((SCHEMAS / "1.json").read_text())["database"]
    assert database["version"] == 1
    actual = digest(sorted_keys([entity_hash(e) for e in database["entities"]]))
    assert actual == database["identityHash"], "baseline identity hash mismatch"
    assert database["setupQueries"][-1].endswith(f"'{actual}')")
    print(f"Room v1 baseline identity hash verified: {actual}")


if __name__ == "__main__":
    main()
