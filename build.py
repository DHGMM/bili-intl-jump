#!/usr/bin/env python3
"""跳转国际版b站模块 · 便携构建脚本（Termux / Linux 通用）。

流程：aapt2 编译并链接资源 -> javac 编译源码 -> d8 生成 classes.dex ->
把 dex 塞进 APK -> zipalign 对齐 -> apksigner 签名。

依赖：JDK 17+（javac/java）、aapt2、d8、zipalign、apksigner、keytool，
以及一份 android.jar（默认取本机已有的 android-34）。
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = ROOT / "app"
BUILD = ROOT / "build"
DIST = ROOT / "dist"
KEYS = ROOT / "keys"

def find_android_jar():
    """依次尝试 ANDROID_JAR、ANDROID_HOME / ANDROID_SDK_ROOT 下的平台包。"""
    explicit = os.environ.get("ANDROID_JAR")
    if explicit:
        return Path(explicit)
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        for api in ("android-36", "android-35", "android-34", "android-33"):
            candidate = Path(sdk) / "platforms" / api / "android.jar"
            if candidate.is_file():
                return candidate
    return None


SDK_JAR = find_android_jar()

VERSION = "1.5"
VERSION_CODE = "6"
APK_NAME = f"跳转国际版b站-{VERSION}.apk"

KEYSTORE = KEYS / "bili.jks"
KEY_ALIAS = "bili"
KEY_PASS = "biliintl"


def run(cmd, **kwargs):
    printable = " ".join(str(c) for c in cmd)
    print("+", printable)
    subprocess.run([str(c) for c in cmd], check=True, **kwargs)


def which(name: str) -> str | None:
    return shutil.which(name)


def zipalign_path() -> str | None:
    env = os.environ.get("ZIPALIGN")
    if env and Path(env).is_file():
        return env
    local = Path.home() / "bin/zipalign"
    if local.is_file() and os.access(local, os.X_OK):
        return str(local)
    return which("zipalign")


def check_deps() -> bool:
    ok = True
    for tool in ("aapt2", "d8", "apksigner", "keytool", "javac", "java"):
        path = which(tool)
        print(f"  {tool:<10} {path or '缺失'}")
        ok = ok and bool(path)
    za = zipalign_path()
    print(f"  {'zipalign':<10} {za or '缺失'}")
    if SDK_JAR is None:
        print(f"  {'android.jar':<10} 缺失（设置 ANDROID_JAR 或 ANDROID_HOME）")
        ok = False
    else:
        print(f"  {'android.jar':<10} {SDK_JAR} {'OK' if SDK_JAR.is_file() else '缺失'}")
        ok = ok and SDK_JAR.is_file()
    return ok


def ensure_keystore() -> None:
    if KEYSTORE.is_file():
        return
    KEYS.mkdir(parents=True, exist_ok=True)
    run([
        "keytool", "-genkeypair", "-v",
        "-keystore", KEYSTORE,
        "-alias", KEY_ALIAS,
        "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000",
        "-storepass", KEY_PASS, "-keypass", KEY_PASS,
        "-dname", "CN=BiliIntl, OU=Local, O=Local, L=NA, ST=NA, C=CN",
    ])


def collect(pattern: str, base: Path) -> list[str]:
    return sorted(str(p) for p in base.rglob(pattern))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只检查依赖")
    parser.add_argument("--unsigned", action="store_true", help="只产出未签名 APK")
    parser.add_argument("--regen-keystore", action="store_true", help="重建签名证书")
    args = parser.parse_args()

    print("依赖检查：")
    if not check_deps():
        print("缺少依赖，无法构建。")
        return 1
    if args.check:
        print("PASS")
        return 0

    if args.regen_keystore and KEYSTORE.is_file():
        KEYSTORE.unlink()

    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir(parents=True)
    DIST.mkdir(exist_ok=True)
    (BUILD / "gen").mkdir()
    (BUILD / "classes").mkdir()
    (BUILD / "stub-classes").mkdir()
    (BUILD / "dex").mkdir()

    android_jar = str(SDK_JAR)

    # 1) 编译资源
    run(["aapt2", "compile", "--dir", APP / "res", "-o", BUILD / "res.zip"])

    # 2) 链接资源并生成 R.java 与 APK 骨架
    run([
        "aapt2", "link",
        "-o", BUILD / "base.apk",
        "-I", android_jar,
        "--manifest", APP / "AndroidManifest.xml",
        "-R", BUILD / "res.zip",
        "-A", APP / "assets",
        "--java", BUILD / "gen",
        "--auto-add-overlay",
    ])

    # 3a) 先编译 Xposed 编译桩：只用于后续编译，绝不进入 APK
    stub_sources = collect("*.java", APP / "stubs")
    if stub_sources:
        stub_argfile = BUILD / "stub-sources.txt"
        stub_argfile.write_text("\n".join(stub_sources) + "\n", encoding="utf-8")
        run([
            "javac", "--release", "8", "-encoding", "UTF-8",
            "-classpath", android_jar,
            "-d", BUILD / "stub-classes",
            f"@{stub_argfile}",
        ])

    # 3b) 编译模块主体（桩只在 classpath 里，不打进 dex）
    sources = collect("*.java", APP / "src") + collect("*.java", BUILD / "gen")
    if not sources:
        print("没有找到 Java 源码")
        return 1
    compile_classpath = android_jar + os.pathsep + str(BUILD / "stub-classes")
    argfile = BUILD / "sources.txt"
    argfile.write_text("\n".join(sources) + "\n", encoding="utf-8")
    run([
        "javac", "--release", "8", "-encoding", "UTF-8",
        "-classpath", compile_classpath,
        "-d", BUILD / "classes",
        f"@{argfile}",
    ])

    # 4) d8 生成 dex（只包含模块自身的类）
    classes = collect("*.class", BUILD / "classes")
    run([
        "d8", "--min-api", "24", "--lib", android_jar,
        "--output", BUILD / "dex", *classes,
    ])
    dex_files = sorted((BUILD / "dex").glob("*.dex"))
    if len(dex_files) != 1:
        print(f"期望恰好一个 dex，实际 {len(dex_files)} 个")
        return 1

    # 5) 组装 APK：骨架 + classes.dex
    unaligned = BUILD / "unaligned.apk"
    shutil.copyfile(BUILD / "base.apk", unaligned)
    with zipfile.ZipFile(unaligned, "a", zipfile.ZIP_DEFLATED) as zf:
        zf.write(dex_files[0], "classes.dex")

    # 6) 对齐
    aligned = BUILD / "aligned.apk"
    run([zipalign_path(), "-f", "4", unaligned, aligned])

    if args.unsigned:
        target = DIST / APK_NAME.replace(".apk", "-unsigned.apk")
        shutil.copyfile(aligned, target)
        print("已输出：", target)
        return 0

    # 7) 签名
    ensure_keystore()
    signed = DIST / APK_NAME
    run([
        "apksigner", "sign",
        "--ks", KEYSTORE,
        "--ks-key-alias", KEY_ALIAS,
        "--ks-pass", f"pass:{KEY_PASS}",
        "--key-pass", f"pass:{KEY_PASS}",
        "--out", signed,
        aligned,
    ])
    print("已输出：", signed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
