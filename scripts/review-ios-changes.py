#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/

"""Read-only upstream report for explicitly supplied local commits; never fetches or adopts."""
import argparse
import json
import sys
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from swift_api_inventory import declarations


def git(repository, *command):
    return subprocess.check_output(['git', '-C', str(repository), *command], text=True)


def source(repository, revision, path):
    return git(repository, 'show', f'{revision}:{path}')



def report(repository, start, end, ledger):
    start = git(repository, 'rev-parse', '--verify', '--end-of-options', start + '^{commit}').strip()
    end = git(repository, 'rev-parse', '--verify', '--end-of-options', end + '^{commit}').strip()
    tokens = git(repository, 'diff', '--name-status', '-z', '-M', start, end, '--',
                 'Core', 'Feature', 'Thunderbird').rstrip('\0').split('\0')
    rows = []
    i = 0
    while i < len(tokens) and tokens[i]:
        state, path = tokens[i:i + 2]
        i += 2
        old_path = path
        if state.startswith('R'):
            path = tokens[i]
            i += 1
        paths = (path, old_path)
        mappings = [m for m in ledger['modules'] if any(
            p.startswith('Core/Sources/' + m['iosModule'] + '/') or
            p.startswith('Core/Tests/' + m['iosModule'] + 'Tests/') for p in paths)]
        behaviors = [m for m in ledger.get('behaviorMappings', []) if any(
            p == m['ios'] or p.startswith(m['ios'].rstrip('/') + '/') for p in paths)]
        inspect_text = path.endswith(('.swift', 'Package.resolved'))
        old = '' if state == 'A' or not inspect_text else source(repository, start, old_path)
        new = '' if state == 'D' or not inspect_text else source(repository, end, path)
        old_symbols, new_symbols = set(declarations(old)), set(declarations(new))
        dependency = any(p.endswith(('Package.swift', 'Package.resolved')) for p in paths)
        test = any('/Tests/' in p for p in paths)
        categories = []
        if dependency:
            categories.append('dependencies')
        if test:
            categories.append('tests_or_fixtures')
        if old_symbols != new_symbols:
            categories.append('api_or_model_declarations')
        if state.startswith('R'):
            categories.append('rename')
        if state == 'D':
            categories.append('removal')
        if not categories:
            categories.append('implementation_review')
        rows.append({
            'change': state, 'iosPath': path,
            'previousPath': old_path if path != old_path else None,
            'categories': categories,
            'androidModules': sorted({m['androidModule'] for m in mappings}),
            'androidTargets': sorted({m['android'] for m in behaviors}),
            'androidTests': sorted({t for m in behaviors for t in m.get('tests', [])}),
            'declarationsAdded': sorted(new_symbols - old_symbols),
            'declarationsRemoved': sorted(old_symbols - new_symbols),
            'dependencyBefore': old if dependency else None,
            'dependencyAfter': new if dependency else None,
            'action': 'Review behavior and tests; record unadopted changes under the affected task',
        })
    return {'from': start, 'to': end,
            'lastFullyAdoptedRevision': ledger['lastFullyAdoptedRevision'],
            'changes': rows,
            'note': 'No files changed or revisions adopted. Declaration differences are lexical hints; '
                    'manual review is required for multiline signatures, extensions, behavior and fixes.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, default=ROOT.parent / 'thunderbird-ios')
    parser.add_argument('--from-revision', required=True)
    parser.add_argument('--to-revision', required=True)
    args = parser.parse_args()
    ledger = json.loads((ROOT / 'Android/core/ios-parity.json').read_text())
    print(json.dumps(report(args.repository, args.from_revision, args.to_revision, ledger), indent=2))


if __name__ == '__main__':
    main()
