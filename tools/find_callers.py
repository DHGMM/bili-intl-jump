#!/usr/bin/env python3
"""扫描 dex 中所有 invoke-* 指令，找出调用了指定 method_idx 的方法。

用法：python3 tools/find_callers.py <dex> <target_method_idx>
"""
import struct
import sys

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


def main():
    path = sys.argv[1]
    target = int(sys.argv[2])
    with open(path, "rb") as f:
        data = f.read()
    string_ids_off = u4(data, 0x3C)
    type_ids_off = u4(data, 0x44)
    method_ids_off = u4(data, 0x5C)
    class_defs_off = u4(data, 0x64)
    class_defs_size = u4(data, 0x60)

    def string(i):
        o = u4(data, string_ids_off + i * 4)
        p = o
        ln = 0
        sh = 0
        while True:
            b = data[p]
            p += 1
            ln |= (b & 0x7F) << sh
            if not b & 0x80:
                break
            sh += 7
        return data[p:p + ln].decode("utf-8", "replace")

    def type_name(i):
        return string(u4(data, type_ids_off + i * 4))

    def method_name(i):
        o = method_ids_off + i * 8
        return type_name(u2(data, o)), string(u4(data, o + 4))

    tcls, tname = method_name(target)
    print(f"目标: {tcls}->{tname} (idx={target})")

    for ci in range(class_defs_size):
        o = class_defs_off + ci * 32
        cname = type_name(u4(data, o))
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
                if not code_off:
                    continue
                insns_size = u4(data, code_off + 12)
                base = code_off + 16
                i = 0
                while i < insns_size:
                    unit = u2(data, base + i * 2)
                    op = unit & 0xFF
                    if op in INVOKES and i + 1 < insns_size:
                        if u2(data, base + (i + 1) * 2) == target:
                            _, caller = method_name(midx)
                            print(f"  {cname}->{caller}")
                            break
                    i += LEN[op]


if __name__ == "__main__":
    main()
