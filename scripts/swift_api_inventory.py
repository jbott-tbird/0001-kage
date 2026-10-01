#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/

"""Lexical Swift API inventory for human review, including public extensions and enum cases.

This records declaration starts, not parsed signatures or semantic compatibility.
"""
import re


def declarations(source):
    # Remove comments and string contents before tracking scope. Preserve newlines for line mapping.
    pattern = r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"'
    def blank(match):
        return ''.join('\n' if c == '\n' else ' ' for c in match.group())
    clean = re.sub(pattern, blank, source, flags=re.S)
    original = source.splitlines()
    result = []
    scopes = []
    depth = 0
    for number, line in enumerate(clean.splitlines()):
        text = line.strip()
        inherited = any(kind == 'extension' and level == depth for kind, level in scopes)
        enum_case = any(kind == 'enum' and level == depth for kind, level in scopes) and re.match(r'case\s', text)
        explicit = re.search(r'\b(?:public|open)\s+(?:(?:static|class|final|indirect|override|nonisolated|mutating)\s+)*', text)
        member = re.match(r'(?!(?:private|fileprivate|internal)\b)(?:(?:static|class|mutating|nonmutating)\s+)*(?:func|var|let|init|subscript|typealias|struct|enum)\b', text)
        if explicit or enum_case or (inherited and member):
            result.append(original[number].strip())
        if re.search(r'\bpublic\s+extension\b', text) and '{' in text:
            scopes.append(('extension', depth + 1))
        if (explicit or (inherited and member)) and re.search(r'\benum\s+\w+', text) and '{' in text:
            scopes.append(('enum', depth + 1))
        depth += line.count('{') - line.count('}')
        scopes = [(kind, level) for kind, level in scopes if level <= depth]
    return result
