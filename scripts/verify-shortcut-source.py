#!/usr/bin/env python3
"""Verify the complete static shortcut identity for one build variant."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ANDROID = "{http://schemas.android.com/apk/res/android}"
EXPECTED_ACTION = "android.intent.action.MAIN"
EXPECTED_CLASS = "dev.octoshrimpy.quik.feature.compose.ComposeActivity"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("xml", type=Path)
    parser.add_argument("expected_package")
    args = parser.parse_args()

    try:
        root = ET.parse(args.xml).getroot()
    except (OSError, ET.ParseError) as error:
        print(f"cannot parse shortcut XML: {error}", file=sys.stderr)
        return 1

    shortcuts = list(root.findall("shortcut"))
    intents = list(root.iter("intent"))
    observed = [
        (
            intent.get(f"{ANDROID}action"),
            intent.get(f"{ANDROID}targetClass"),
            intent.get(f"{ANDROID}targetPackage"),
        )
        for intent in intents
    ]
    expected = [(EXPECTED_ACTION, EXPECTED_CLASS, args.expected_package)]

    if root.tag != "shortcuts" or len(shortcuts) != 1:
        print("shortcut XML must contain exactly one shortcut", file=sys.stderr)
        return 1
    if shortcuts[0].get(f"{ANDROID}shortcutId") != "compose":
        print("the only shortcut must have shortcutId=compose", file=sys.stderr)
        return 1
    if observed != expected:
        print(f"shortcut intent multiset differs: {observed!r}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
