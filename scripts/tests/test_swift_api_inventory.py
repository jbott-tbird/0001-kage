import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parents[1]))
from swift_api_inventory import declarations


class SwiftApiInventoryTest(unittest.TestCase):
    def test_public_extensions_cases_and_private_implementation(self):
        source = '''// public func fake() {}
public enum Mode {
    case online(String), offline
    public var title: String {
        switch self {
        case .online: return "{"
        case .offline: return "}"
        }
    }
}
public extension Mode {
    func connect() {
        let implementationOnly = 1
    }
    private func helper() {}
    var label: String { "name" }
}
struct Internal {
    func hidden() {}
}
'''
        found = declarations(source)
        self.assertIn('case online(String), offline', found)
        self.assertIn('func connect() {', found)
        self.assertIn('var label: String { "name" }', found)
        self.assertFalse(any('helper' in x or 'implementationOnly' in x or 'hidden' in x or 'fake' in x for x in found))
        self.assertFalse(any(x.startswith('case .') for x in found))


if __name__ == '__main__':
    unittest.main()
