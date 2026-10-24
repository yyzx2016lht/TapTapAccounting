#!/usr/bin/env python3
"""Check that every arm64-v8a shared library is aligned for 16 KB page size devices.

Android 15+ devices can run with a 16 KB kernel page size. Any `.so` whose PT_LOAD
segments are only 4 KB aligned makes the package manager launch the app in "16 KB
backward compatibility mode", which shows the system "Android 应用兼容性" warning on
first launch and is listed as an ELF alignment failure. Google Play requires 16 KB
support for apps targeting Android 15 (API 35)+.

The offending libraries in this project have always come from third-party prebuilts
(`org.tensorflow:tensorflow-lite` and the bundled `app/libs/sherpa-onnx.aar`), so run
this after touching either of them.

Usage:
    python tools/check-native-16kb.py app/build/outputs/apk/debug/app-debug.apk
    python tools/check-native-16kb.py app/libs/sherpa-onnx.aar

Exits non-zero when a non-aligned library is found. `zipalign -c -P 16 -v 4 <apk>`
does not replace this: it only checks ZIP entry alignment, not ELF segment alignment.
"""

import struct
import sys
import zipfile

MIN_ALIGN = 16384
# Third-party libs that legitimately live in the archive but never ship in the APK.
IGNORED_ABIS = ("x86", "x86_64", "armeabi-v7a")


def load_alignments(data):
    """Return the p_align values of every PT_LOAD segment, or None if not an ELF."""
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        phoff, entsize, count = (
            struct.unpack_from(endian + "Q", data, 0x20)[0],
            struct.unpack_from(endian + "H", data, 0x36)[0],
            struct.unpack_from(endian + "H", data, 0x38)[0],
        )
        align_at = 0x30
    else:
        phoff, entsize, count = (
            struct.unpack_from(endian + "I", data, 0x1C)[0],
            struct.unpack_from(endian + "H", data, 0x2A)[0],
            struct.unpack_from(endian + "H", data, 0x2C)[0],
        )
        align_at = 0x1C
    aligns = []
    for i in range(count):
        off = phoff + i * entsize
        if struct.unpack_from(endian + "I", data, off)[0] != 1:
            continue
        aligns.append(struct.unpack_from(endian + "Q" if is64 else "I", data, off + align_at)[0])
    return aligns


def entries(path):
    if path.lower().endswith((".apk", ".aar", ".zip", ".jar")):
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.endswith(".so"):
                    yield name, archive.read(name)
        return
    with open(path, "rb") as handle:
        yield path, handle.read()


def main(paths):
    if not paths:
        print(__doc__)
        return 2
    bad = []
    checked = 0
    for path in paths:
        for name, data in entries(path):
            if name.split("/")[0] not in ("lib", "jni") or any(a in name for a in IGNORED_ABIS):
                continue
            aligns = load_alignments(data)
            if aligns is None:
                continue
            checked += 1
            worst = min(aligns)
            state = "OK " if worst >= MIN_ALIGN else "BAD"
            if worst < MIN_ALIGN:
                bad.append(name)
            print(f"  [{state}] {name}: PT_LOAD p_align = {[hex(a) for a in aligns]}")
    print(f"\n{checked} shared libraries checked, {len(bad)} not 16 KB aligned.")
    if bad:
        print("Not aligned: " + ", ".join(bad))
        print("Fix: use a prebuilt that declares p_align 0x4000 for every PT_LOAD segment,")
        print("or build it with NDK r28+, or with -Wl,-z,max-page-size=16384.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
