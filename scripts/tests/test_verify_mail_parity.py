import copy
import json
import sys
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from verify_mail_parity import verify


class ParityIntegrityTest(unittest.TestCase):
    def test_detects_mapping_holes_unknown_tasks_and_changed_fixture_hashes(self):
        ledger = json.loads((ROOT / 'Android/core/ios-parity.json').read_text())
        errors, _, _ = verify(ledger, ROOT)
        self.assertEqual([], errors)
        bad = copy.deepcopy(ledger)
        source = bad['modules'][0]['sources'][0]
        source['symbolMappings'].pop()
        source['symbolMappings'][0]['task'] = 'T999'
        bad['fixtures'][0]['sha256'] = '0' * 64
        errors, _, _ = verify(bad, ROOT)
        self.assertTrue(any('Incomplete/duplicate' in error for error in errors))
        self.assertTrue(any('Unknown task' in error for error in errors))
        self.assertTrue(any('Fixture provenance mismatch' in error for error in errors))


if __name__ == '__main__':
    unittest.main()
