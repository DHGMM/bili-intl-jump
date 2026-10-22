#!/usr/bin/env python3
"""极简 DEX 反汇编：打印指定类/方法的指令，重点解析 invoke / const-string。

用法：
  python3 tools/disasm_method.py <dex> <类名(内部描述符)> <方法名>
"""
import struct
import sys

# opcode -> 指令占用的 16 位单元数
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

NAMES = {
    0x0e: "return-void", 0x0f: "return", 0x10: "return-wide", 0x11: "return-object",
    0x1a: "const-string", 0x1b: "const-string/jumbo", 0x14: "const", 0x13: "const/16",
    0x15: "const/high16", 0x12: "const/4",
    0x6e: "invoke-virtual", 0x6f: "invoke-super", 0x70: "invoke-direct",
    0x71: "invoke-static", 0x72: "invoke-interface",
    0x74: "invoke-virtual/range", 0x75: "invoke-super/range", 0x76: "invoke-direct/range",
    0x77: "invoke-static/range", 0x78: "invoke-interface/range",
    0x1f: "check-cast", 0x22: "new-instance", 0x27: "throw", 0x28: "goto",
    0x21: "array-length", 0x23: "new-array",
}


def uleb(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            break
        shift += 7
    return result, off


def u4(data, o):
    return struct.unpack_from("<I", data, o)[0]


def u2(data, o):
    return struct.unpack_from("<H", data, o)[0]


class Dex:
    def __init__(self, data):
        self.data = data
        self.string_ids_off = u4(data, 0x3C)
        self.type_ids_off = u4(data, 0x44)
        self.proto_ids_off = u4(data, 0x4C)
        self.method_ids_off = u4(data, 0x5C)
        self.class_defs_off = u4(data, 0x64)
        self.class_defs_size = u4(data, 0x60)

    def string(self, i):
        o = u4(self.data, self.string_ids_off + i * 4)
        p = o
        ln = 0
        sh = 0
        while True:
            b = self.data[p]
            p += 1
            ln |= (b & 0x7F) << sh
            if not b & 0x80:
                break
            sh += 7
        return self.data[p:p + ln].decode("utf-8", "replace")

    def type_name(self, i):
        return self.string(u4(self.data, self.type_ids_off + i * 4))

    def method_sig(self, i):
        o = self.method_ids_off + i * 8
        cls = self.type_name(u2(self.data, o))
        proto = u2(self.data, o + 2)
        name = self.string(u4(self.data, o + 4))
        return f"{cls}->{name}(proto@{proto})"

    def find_method(self, want_class, want_name):
        data = self.data
        for ci in range(self.class_defs_size):
            o = self.class_defs_off + ci * 32
            cname = self.type_name(u4(data, o))
            if cname != want_class:
                continue
            class_data_off = u4(data, o + 24)
            if not class_data_off:
                continue
            p = class_data_off
            sf, p = uleb(data, p)
            inf, p = uleb(data, p)
            dm, p = uleb(data, p)
            vm, p = uleb(data, p)
            for _ in range(sf + inf):
                _, p = uleb(data, p)
                _, p = uleb(data, p)
            for cnt in (dm, vm):
                midx = 0
                for _ in range(cnt):
                    diff, p = uleb(data, p)
                    _, p = uleb(data, p)
                    code_off, p = uleb(data, p)
                    midx += diff
                    name = self.string(u4(data, self.method_ids_off + midx * 8 + 4))
                    if name == want_name and code_off:
                        yield midx, code_off


def dump(dex, code_off):
    data = dex.data
    insns_size = u4(data, code_off + 12)
    base = code_off + 16
    units = [u2(data, base + i * 2) for i in range(insns_size)]
    i = 0
    while i < insns_size:
        unit = units[i]
        op = unit & 0xFF
        length = LEN[op]
        if op in (0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
            midx = units[i + 1]
            print(f"  {i:04x}: {NAMES[op]:<24} {dex.method_sig(midx)}")
        elif op == 0x1A:
            sidx = units[i + 1]
            print(f"  {i:04x}: const-string            {dex.string(sidx)!r}")
        elif op == 0x1B:
            sidx = units[i + 1] | (units[i + 2] << 16)
            print(f"  {i:04x}: const-string/jumbo        {dex.string(sidx)!r}")
        elif op in (0x13, 0x14, 0x15, 0x12):
            if op == 0x14:
                val = units[i + 1] | (units[i + 2] << 16)
            elif op == 0x15:
                val = units[i + 1] << 16
            else:
                val = units[i + 1]
            print(f"  {i:04x}: {NAMES[op]:<24} 0x{val:x}")
        elif op in NAMES:
            print(f"  {i:04x}: {NAMES[op]}")
        i += length


def main():
    path, cls, name = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(path, "rb") as f:
        data = f.read()
    dex = Dex(data)
    found = False
    for midx, code_off in dex.find_method(cls, name):
        found = True
        print(f"== {cls}->{name} (method_idx={midx}) ==")
        dump(dex, code_off)
    if not found:
        print("not found")


if __name__ == "__main__":
    main()
