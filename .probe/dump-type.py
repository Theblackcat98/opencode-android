import re
import sys

src = open('.vendor-tmp/package/dist/promise/generated/types.d.ts').read()


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
