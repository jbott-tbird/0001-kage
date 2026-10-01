#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/

"""Validate traceability integrity, not implementation completeness or semantic parity."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from swift_api_inventory import declarations

ROOT = Path(__file__).resolve().parents[1]
STATES = {'matched', 'pending_port', 'intentionally_different', 'not_applicable'}


def verify(ledger, root, ios=None):
    errors = []
    tasks = set(re.findall(r'id="(T\d+)"', (root / 'plans/0001 - android_mail_engine_port.html').read_text()))
    tests = '\n'.join(p.read_text() for p in (root / 'Android').rglob('*.kt')
                      if ('/src/test/' in str(p) or '/src/androidTest/' in str(p)))
    if not re.fullmatch('[0-9a-f]{40}', ledger['lastReviewedRevision']):
        errors.append('Invalid baseline revision')
    source_paths = set()
    symbols = 0
    for module in ledger['modules']:
        for source in module['sources']:
            path = source['path']
            if path in source_paths:
                errors.append(f'Duplicate source: {path}')
            source_paths.add(path)
            entries = source.get('symbolMappings', [])
            expected = list(range(len(source['publicDeclarations'])))
            if sorted(x['declarationIndex'] for x in entries) != expected:
                errors.append(f'Incomplete/duplicate symbol mapping: {path}')
            symbols += len(entries)
            for entry in entries:
                if entry['status'] not in STATES or not entry.get('reason'):
                    errors.append(f'Unexplained adoption status: {path}')
                if entry.get('task') and entry['task'] not in tasks:
                    errors.append(f'Unknown task {entry["task"]}: {path}')
                if entry.get('androidPath') and not (root / entry['androidPath']).is_file():
                    errors.append(f'Missing Android target: {entry["androidPath"]}')
                for test in entry.get('tests', []):
                    name = test.split('.')[-1]
                    pattern = r'\b(?:class|fun)\s+' + re.escape(name) + r'\b'
                    if not re.search(pattern, tests):
                        errors.append(f'Missing test reference: {test}')
            if ios:
                file = ios / path
                if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != source['sha256']:
                    errors.append(f'Source differs from baseline: {path}')
                elif set(declarations(file.read_text())) - set(source['publicDeclarations']):
                    errors.append(f'Uninventoried API declaration: {path}')
    for fixture in ledger['fixtures']:
        if fixture.get('adopted'):
            file = root / fixture['androidFixture']
            if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != fixture['sha256']:
                errors.append(f'Fixture provenance mismatch: {fixture["path"]}')
    if ios:
        lock = ledger['resolvedDependencies']
        file = ios / lock['path']
        if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != lock['sha256']:
            errors.append('Local workspace dependency pins changed; request/review required before adoption')
    return errors, len(source_paths), symbols


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ios-repository', type=Path)
    args = parser.parse_args()
    ledger = json.loads((ROOT / 'Android/core/ios-parity.json').read_text())
    errors, sources, symbols = verify(ledger, ROOT, args.ios_repository)
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'Validated {sources} source records and {symbols} symbol mappings; no completeness/adoption claim')


if __name__ == '__main__':
    main()
