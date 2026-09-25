#!/usr/bin/env python3
"""Read-only upstream change report. Only inspects explicitly supplied local revisions; never fetches."""
import argparse
import json
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repository', type=Path, default=ROOT.parent / 'thunderbird-ios')
parser.add_argument('--from-revision', required=True)
parser.add_argument('--to-revision', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
ledger = json.loads((root / 'Android/core/ios-parity.json').read_text())
def git(*command):
    return subprocess.check_output(['git', '-C', str(args.repository), *command], text=True).strip()
# Resolve hashes first: options and revision expressions never reach the diff command unvalidated.
start = git('rev-parse', '--verify', '--end-of-options', args.from_revision + '^{commit}')
end = git('rev-parse', '--verify', '--end-of-options', args.to_revision + '^{commit}')
changed = git('diff', '--name-status', '--no-renames', start, end, '--', 'Core', 'Feature', 'Thunderbird')
rows = []
for line in changed.splitlines():
    state, path = line.split('\t', 1)
    mapping = next((m for m in ledger['modules'] if path.startswith('Core/Sources/' + m['iosModule'] + '/') or path.startswith('Core/Tests/' + m['iosModule'] + 'Tests/')), None)
    rows.append({'change':state,'iosPath':path,'androidModule':mapping['androidModule'] if mapping else 'Review cross-module impact',
                 'action':'Review API, entity fields, behavior and associated tests; add tasks for unadopted differences'})
print(json.dumps({'from': start, 'to':end, 'lastFullyAdoptedRevision':ledger['lastFullyAdoptedRevision'],
                  'changes':rows,'note':'No files changed or revisions adopted. Deleted/added paths can represent a rename; review together.'},indent=2))
