#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 classes.dex 以 STORED(不压缩) 方式注入 aapt2 link 产出的 APK。"""
import zipfile, os, sys

dex = sys.argv[1] if len(sys.argv) > 1 else "build/dexout/classes.dex"
apk = "build/app-unsigned.apk"
tmp = "build/app-dex.apk"

with zipfile.ZipFile(apk, 'r') as zin, \
     zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        if it.filename.startswith("classes"):
            continue
        zout.writestr(it, zin.read(it.filename))
    with open(dex, 'rb') as f:
        data = f.read()
    zi = zipfile.ZipInfo("classes.dex")
    zi.compress_type = zipfile.ZIP_STORED
    zi.external_attr = 0o644 << 16
    zout.writestr(zi, data)

os.replace(tmp, apk)
print("dex injected, size", os.path.getsize(apk))
