#!/usr/bin/env python3
"""Prints one generated TypeScript type from the vendored @opencode/client.

The OpenAPI spec types every event payload as an opaque JSON string, so the event
contract is `@opencode/client`'s generated types. When a release adds or changes
one, this is how the declaration is read back without unpacking the tarball by
hand; `api/opencode-2.0.x/events.json` is what it produced.

    python3 tools/dump-client-type.py V2EventSessionStepEnded
    python3 tools/dump-client-type.py SessionMessageInfo
"""
import re
import sys

TYPES = '.vendor-tmp/package/dist/promise/generated/types.d.ts'
src = open(TYPES).read()


def full(name):
    m = re.search(r'export type ' + name + r' = ', src)
    if not m:
        return 'NOT FOUND'
    tail = src[m.end():m.end() + 400]
    i = src.index('{', m.end()) if '{' in tail else -1
    if i < 0 or i > m.end() + 400:
        m2 = re.search(r'export type ' + name + r' = ([^;]+);', src, re.S)
        return m2.group(1).strip() if m2 else 'NOT FOUND'
    d = 0
    instr = None
    j = i
    while True:
        ch = src[j]
        if instr:
            if ch == '\\':
                j += 1
            elif ch == instr:
                instr = None
        elif ch in ('"', "'", '`'):
            instr = ch
        elif ch == '{':
            d += 1
        elif ch == '}':
            d -= 1
            if d == 0:
                break
        j += 1
    return src[i:j + 1]


for n in sys.argv[1:]:
    print('### ' + n)
    print(full(n)[:1800])
    print()
