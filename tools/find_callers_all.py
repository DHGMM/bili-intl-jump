#!/usr/bin/env python3
"""跨 dex 查找某个类方法的调用者。

按「类名 + 方法名」在每个 dex 内定位 method_idx，再扫描该 dex 的 invoke 指令。

用法：python3 tools/find_callers_all.py <apk> <类描述符> <方法名>
"""
import struct
import sys
import zipfile

LEN = [1] * 256
for op in ([0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23]
           + list(range(0x2d, 0x3e))
           + list(range(0x44, 0x6e))
           + list(range(0x90, 0xb0))
           + list(range(0xb0, 0xc3))
           + [0xfe, 0xff]):
    LEN[op] = 2
for op in ([0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c]
           + list(range(0x6e, 0x73)) + list(range(0x74, 0x79)) + [0xfc, 0xfd]):
    LEN[op] = 3
for op in [0x18, 0xfa, 0xfb]:
    LEN[op] = 5

INVOKES = set(list(range(0x6E, 0x73)) + list(range(0x74, 0x79)) + [0xfc, 0xfd])


def uleb(data, off):
    r = 0
    sh = 0
    while True:
        b = data[off]
        off += 1
        r |= (b & 0x7F) << sh
        if not b & 0x80:
            break
        sh += 7
    return r, off


class Dex:
    def __init__(self, data):
        self.d = data
        self.si = self.u4(0x3C)
        self.ti = self.u4(0x44)
        self.mi = self.u4(0x5C)
        self.cdo = self.u4(0x64)
        self.csz = self.u4(0x60)

    def u4(self, o):
        return struct.unpack_from("<I", self.d, o)[0]

    def u2(self, o):
        return struct.unpack_from("<H", self.d, o)[0]

    def string(self, i):
        o = self.u4(self.si + i * 4)
        p = o
        ln = 0
        sh = 0
        while True:
            b = self.d[p]
            p += 1
            ln |= (b & 0x7F) << sh
            if not b & 0x80:
                break
            sh += 7
        return self.d[p:p + ln].decode("utf-8", "replace")

    def tname(self, i):
        return self.string(self.u4(self.ti + i * 4))

    def method_name(self, i):
        o = self.mi + i * 8
        return self.tname(self.u2(o)), self.string(self.u4(o + 4))

    def methods(self):
        """yield (midx, code_off)"""
        d = self.d
        for ci in range(self.csz):
            o = self.cdo + ci * 32
            cdoff = self.u4(o + 24)
            if not cdoff:
                continue
            p = cdoff
            sf, p = uleb(d, p)
            inf, p = uleb(d, p)
            dm, p = uleb(d, p)
            vm, p = uleb(d, p)
            for _ in range(sf + inf):
                _, p = uleb(d, p)
                _, p = uleb(d, p)
            for cnt in (dm, vm):
                midx = 0
                for _ in range(cnt):
                    diff, p = uleb(d, p)
                    _, p = uleb(d, p)
                    code_off, p = uleb(d, p)
                    midx += diff
                    if code_off:
                        yield midx, code_off


def scan(dex, targets):
    """返回 [(caller_desc, target_idx)]"""
    found = []
    for midx, code_off in dex.methods():
        insns_size = dex.u4(code_off + 12)
        base = code_off + 16
        i = 0
        hit = set()
        while i < insns_size:
            op = dex.u2(base + i * 2) & 0xFF
            if op in INVOKES and i + 1 < insns_size:
                ti = dex.u2(base + (i + 1) * 2)
                if ti in targets:
                    hit.add(ti)
            i += LEN[op]
        for ti in hit:
            cname, mname = dex.method_name(midx)
            found.append((cname, mname, ti))
    return found


def main():
    apk, want_class, want_method = sys.argv[1], sys.argv[2], sys.argv[3]
    with zipfile.ZipFile(apk) as zf:
        names = [n for n in zf.namelist() if n.endswith(".dex")]
    for name in names:
        with zipfile.ZipFile(apk) as zf:
            dex = Dex(zf.read(name))
        targets = set()
        for midx, _ in dex.methods():
            cls, meth = dex.method_name(midx)
            if cls == want_class and meth == want_method:
                targets.add(midx)
        if not targets:
            continue
        print(f"--- {name}: 目标 idx={sorted(targets)}")
        for cname, mname, ti in scan(dex, targets):
            print(f"    {cname}->{mname}")


if __name__ == "__main__":
    main()
