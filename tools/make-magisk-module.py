#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Nezo 的 release APK 打成一个 Magisk 模块，装进 /system/priv-app，
从而拿到 android.permission.READ_CLIPBOARD_IN_BACKGROUND，实现真正的后台剪贴板监听。

副作用说明：本模块**只**解决「后台读剪贴板」这一件事（真后台监听），
和 1.1 新增的「抖音解析 / 下载」功能没有任何关系——下载本来就不需要系统权限，
普通 APK 安装即可用；不要为了下载去刷这个模块。

用法：
    # 自动挑最新的 release APK，版本号从 APK 的 aapt2 badging 读
    python tools/make-magisk-module.py

    # 指定 APK / 输出路径
    python tools/make-magisk-module.py --apk I:\\DeepseekHarness\\03_releases\\Nezo\\Nezo-1.1.0.apk \\
        --out I:\\DeepseekHarness\\03_releases\\Nezo\\Nezo-root-magisk.zip

    # aapt2 不可用时手动给版本
    python tools/make-magisk-module.py --version 1.1.0 --version-code 4

产物：I:\\DeepseekHarness\\03_releases\\Nezo\\Nezo-root-magisk.zip
安装：
    1) 先卸载手机上已安装的Nezo（桌面长按 → 卸载），否则同名包会冲突；
    2) Magisk App → 模块 → 从本地安装 → 选 zip；
    3) 重启手机；打开Nezo → 设置页「真·后台监听（需 root）」应显示「已启用」。
卸载：
    Magisk App → 模块 → 移除 Nezo → 重启。

版本号来源优先级：--version / --version-code 参数 > APK badging > 文件名里的 x.y.z。
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import uuid
import zipfile

ROOT = r"I:\DeepseekHarness\01_projects\ClipNestAndroid"
RELEASE_DIR = r"I:\DeepseekHarness\03_releases\ClipNest"
APK_DEFAULT = os.path.join(ROOT, r"app\build\outputs\apk\release\app-release.apk")
AAPT2_DEFAULT = r"I:\DeepseekHarness\05_toolchain\nexio-build\android-sdk\build-tools\36.0.0\aapt2.exe"

MODULE_PROP_TEMPLATE = """id=clipnest_privapp
name=ClipNest Nezo（系统应用增强）
version=v{version}
versionCode={version_code}
author=ClipNest
description=把Nezo装进 /system/priv-app 并授予 READ_CLIPBOARD_IN_BACKGROUND，实现真·后台剪贴板监听。本模块只给「真后台监听」这一项系统权限，与 1.1 的抖音解析 / 下载功能无关（下载不需要 root）。卸载模块并重启即可完全移除。
"""

PRIVAPP_XML = """<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.clipnest.app">
        <permission name="android.permission.READ_CLIPBOARD_IN_BACKGROUND"/>
    </privapp-permissions>
</permissions>
"""

CUSTOMIZE_SH = """#!/system/bin/sh
# 模块安装时清理用户分区里的同名应用，避免 priv-app 与 data/app 冲突
rm -rf /data/app/*/com.clipnest.app-* 2>/dev/null
true
"""


def resolve_apk(explicit: str | None) -> str:
    """没指定就挑最新的一份 APK：release 产物优先，其次 03_releases 里最新的。"""
    if explicit:
        if not os.path.isfile(explicit):
            sys.exit("APK 不存在：%s（先跑 tools/build-apk.ps1）" % explicit)
        return explicit
    if os.path.isfile(APK_DEFAULT) and os.path.getsize(APK_DEFAULT) > 1024 * 1024:
        return APK_DEFAULT
    candidates = sorted(
        glob.glob(os.path.join(RELEASE_DIR, "Nezo-*.apk")),
        key=lambda p: os.path.getmtime(p),
        reverse=True,
    )
    if candidates:
        return candidates[0]
    sys.exit("找不到 APK：%s 或 %s\\Nezo-*.apk" % (APK_DEFAULT, RELEASE_DIR))


def read_badging(aapt2: str, apk: str) -> dict:
    """用 aapt2 dump badging 读 package / versionName / versionCode。

    stdout 重定向到临时文件（不用管道）：受限环境里管道式 stdio 可能被拒绝。
    """
    info = {"package": "", "version_name": "", "version_code": 0}
    if not os.path.isfile(aapt2):
        return info
    fd, out_path = tempfile.mkstemp(prefix="clipnest-badging-", suffix=".txt")
    os.close(fd)
    try:
        with open(out_path, "wb") as fh:
            subprocess.run([aapt2, "dump", "badging", apk],
                           stdout=fh, stderr=subprocess.STDOUT, check=False)
        try:
            with open(out_path, "r", encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError:
            return info
        m = re.search(r"^package:\s+name='([^']+)'", text, re.M)
        if m:
            info["package"] = m.group(1)
        m = re.search(r"versionName='([^']+)'", text)
        if m:
            info["version_name"] = m.group(1)
        m = re.search(r"versionCode='(\d+)'", text)
        if m:
            info["version_code"] = int(m.group(1))
    finally:
        try:
            os.remove(out_path)
        except OSError:
            pass
    return info


def version_from_filename(apk: str) -> str:
    m = re.search(r"Nezo-(\d+\.\d+\.\d+)", os.path.basename(apk))
    return m.group(1) if m else ""


def main(argv):
    ap = argparse.ArgumentParser(
        description="把 ClipNest release APK 打成 Magisk 模块（root 真后台监听，与下载功能无关）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("apk_positional", nargs="?", default=None, help="APK 路径（也可用 --apk）")
    ap.add_argument("--apk", default=None, help="APK 路径；不填则自动挑最新一份")
    ap.add_argument("--out", default=os.path.join(RELEASE_DIR, "Nezo-root-magisk.zip"),
                    help="输出 zip 路径（默认 03_releases/ClipNest/Nezo-root-magisk.zip）")
    ap.add_argument("--aapt2", default=AAPT2_DEFAULT, help="aapt2 路径（读版本号用）")
    ap.add_argument("--version", default=None, help="手动指定 versionName，如 1.1.0")
    ap.add_argument("--version-code", type=int, default=0, help="手动指定 versionCode，如 4")
    args = ap.parse_args(argv)

    apk = resolve_apk(args.apk or args.apk_positional)
    if not os.path.isfile(apk) or os.path.getsize(apk) < 1024 * 1024:
        sys.exit("APK 太小或不存在：%s" % apk)
    if not zipfile.is_zipfile(apk):
        sys.exit("不是合法 APK（zip）：%s" % apk)

    info = read_badging(args.aapt2, apk)
    version = args.version or info["version_name"] or version_from_filename(apk) or "1.1.0"
    version_code = args.version_code or info["version_code"] or 0
    if version_code <= 0:
        # 没读到就按版本号的数字段拼一个，至少保证 module.prop 合法、单调
        parts = [int(p) for p in re.findall(r"\d+", version)[:3]]
        while len(parts) < 3:
            parts.append(0)
        version_code = parts[0] * 10000 + parts[1] * 100 + parts[2]

    module_prop = MODULE_PROP_TEMPLATE.format(version=version, version_code=version_code)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)

    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("module.prop", module_prop)
        z.writestr("customize.sh", CUSTOMIZE_SH)
        z.writestr("system/etc/permissions/privapp-permissions-clipnest.xml", PRIVAPP_XML)
        z.write(apk, "system/priv-app/ClipNest/ClipNest.apk")

    size = os.path.getsize(args.out)
    with open(args.out, "rb") as fh:
        digest = hashlib.sha256(fh.read()).hexdigest()

    print("APK      : %s" % apk)
    print("包名     : %s" % (info["package"] or "（未知）"))
    print("版本     : v%s (versionCode %d)" % (version, version_code))
    print("模块     : %s" % args.out)
    print("大小     : %.2f MB (%d 字节)" % (size / 1048576.0, size))
    print("SHA256   : %s" % digest.upper())
    print("内容     : module.prop / customize.sh / privapp-permissions-clipnest.xml / system/priv-app/ClipNest/ClipNest.apk")
    print("提醒     : 本模块只解锁「后台读剪贴板」，与下载功能无关；下载不需要 root。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))