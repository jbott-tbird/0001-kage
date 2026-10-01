# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('review', Path(__file__).parents[1] / 'review-ios-changes.py')
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)


class UpstreamReportTest(unittest.TestCase):
    def test_rename_api_dependencies_binary_fixture_and_no_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args], text=True).strip()
            def write(path, content):
                p = root / path
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(content.encode() if isinstance(content, str) else content)
            git('init', '-q')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            # Enough unchanged lines to exercise Git rename detection with one API edit.
            body = '\n'.join('// retained line ' + str(i) for i in range(30))
            write('Core/Sources/Account/Old.swift', body + '\npublic var name: String\n')
            write('Core/Sources/Account/Removed.swift', 'public func remove() {}\n')
            write('Core/Package.swift', '// dependency v1\n')
            git('add', '.')
            git('commit', '-qm', 'baseline')
            start = git('rev-parse', 'HEAD')
            git('mv', 'Core/Sources/Account/Old.swift', 'Core/Sources/Account/New.swift')
            write('Core/Sources/Account/New.swift', body + '\npublic var name: String?\n')
            (root / 'Core/Sources/Account/Removed.swift').unlink()
            write('Core/Package.swift', '// dependency v2\n')
            write('Core/Tests/AccountTests/Resources/binary.eml', b'\xff\x00\x80')
            git('add', '.')
            git('commit', '-qm', 'changes')
            end = git('rev-parse', 'HEAD')
            ledger = {'lastFullyAdoptedRevision': None,
                      'modules': [{'iosModule': 'Account', 'androidModule': ':core:account'}],
                      'behaviorMappings': [{'ios': 'Core/Sources/Account/Old.swift',
                                            'android': 'Models.kt', 'tests': ['AccountModelTest']}]}
            result = review.report(root, start, end, ledger)
            rows = {r['iosPath']: r for r in result['changes']}
            rename = rows['Core/Sources/Account/New.swift']
            self.assertIn('rename', rename['categories'])
            self.assertEqual(['public var name: String?'], rename['declarationsAdded'])
            self.assertEqual(['public var name: String'], rename['declarationsRemoved'])
            self.assertEqual(['AccountModelTest'], rename['androidTests'])
            self.assertEqual([':core:account'], rename['androidModules'])
            self.assertEqual('D', rows['Core/Sources/Account/Removed.swift']['change'])
            self.assertEqual('// dependency v1\n', rows['Core/Package.swift']['dependencyBefore'])
            self.assertIn('tests_or_fixtures', rows['Core/Tests/AccountTests/Resources/binary.eml']['categories'])
            self.assertEqual('', git('status', '--porcelain'))
            self.assertEqual(end, git('rev-parse', 'HEAD'))
            self.assertEqual([], review.report(root, end, end, ledger)['changes'])


if __name__ == '__main__':
    unittest.main()
