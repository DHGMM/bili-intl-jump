#!/usr/bin/env python3
"""在微信 dex 中定位引用了某个资源 id 的方法。

资源字符串在资源表里，代码里用 const 指令加载 int 型资源 id。
本脚本解析 dex 的 class_def -> method -> code_item，找出字节偏移
落在哪个方法的指令流内。
"""
import struct
import sys
import zipfile

APK = sys.argv[1]
TARGET = int(sys.argv[2], 16)
PATTERN = struct.pack("<I", TARGET)


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


def parse(data):
    string_ids_off = u4(data, 0x3C)
    type_ids_off = u4(data, 0x44)
    method_ids_off = u4(data, 0x5C)
    class_defs_size = u4(data, 0x60)
    class_defs_off = u4(data, 0x64)

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

    def method_info(i):
        o = method_ids_off + i * 8
        return type_name(struct.unpack_from("<H", data, o)[0]), string(u4(data, o + 4))

    matches = []
    hits = []
    start = 0
    while True:
        i = data.find(PATTERN, start)
        if i < 0:
            break
        hits.append(i)
        start = i + 1
    if not hits:
        return matches

    for ci in range(class_defs_size):
        o = class_defs_off + ci * 32
        class_idx = u4(data, o)
        class_data_off = u4(data, o + 24)
        if class_data_off == 0:
            continue
        cname = type_name(class_idx)
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
                if code_off == 0:
                    continue
                insns_size = u4(data, code_off + 12)
                code_start = code_off + 16
                code_end = code_start + insns_size * 2
                for h in hits:
                    if code_start <= h < code_end:
                        mclass, mname = method_info(midx)
                        matches.append((cname, mname, h))
    return matches


def main():
    with zipfile.ZipFile(APK) as zf:
        names = [n for n in zf.namelist() if n.endswith(".dex")]
    seen = set()
    for name in names:
        with zipfile.ZipFile(APK) as zf:
            data = zf.read(name)
        for cname, mname, off in parse(data):
            key = (cname, mname)
            if key in seen:
                continue
            seen.add(key)
            print(f"{name}: {cname} -> {mname}  (offset 0x{off:x})")


if __name__ == "__main__":
    main()
