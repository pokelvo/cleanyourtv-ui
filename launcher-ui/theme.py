#!/usr/bin/env python3
"""Turns a CleanYourTV theme (themes/<name>/config.json) into CleanYourTV UI's config.json.

A theme there sets the original launcher's dock, grid and look. This home screen has its own look (the background
comes from the recommendations' photos), so only what both launchers share carries over:

  dock_apps    -> shelf          (the apps on the home screen, in order)
  hidden_apps  -> hidden_apps    (apps never shown)
  app_keys     -> buttons        ("scan:<n>:<app or input:...>" -> "s<n>", "<keycode>:<app>" -> "k<keycode>")

Everything else in the theme is listed as not used. With a base config.json, the theme's parts replace the base's
and the rest of the base (city, services, logos) stays.

  python3 launcher-ui/theme.py themes/aurora [my-config.json] > out.json
  TV_HOST=... launcher-ui/config.sh --theme themes/aurora [my-config.json]
"""
import json
import os
import re
import sys

SHARED = {"dock_apps", "hidden_apps", "app_keys"}


def convert(theme_dir, base_path=None):
    with open(os.path.join(theme_dir, "config.json"), encoding="utf-8") as f:
        theme = json.load(f)
    out = {}
    if base_path:
        with open(base_path, encoding="utf-8") as f:
            out = json.load(f)
    notes = []
    if isinstance(theme.get("dock_apps"), list):
        out["shelf"] = [p for p in theme["dock_apps"] if isinstance(p, str)]
    if isinstance(theme.get("hidden_apps"), list):
        out["hidden_apps"] = [p for p in theme["hidden_apps"] if isinstance(p, str)]
    if isinstance(theme.get("app_keys"), list):
        buttons = dict(out.get("buttons", {}))
        for entry in theme["app_keys"]:
            if not isinstance(entry, str) or entry.startswith("hold:"):
                notes.append(f"app_keys {entry!r}: long presses are not supported here")
                continue
            m = re.fullmatch(r"scan:(\d{1,6}):(.+)", entry) or re.fullmatch(r"(\d{1,6}):(.+)", entry)
            if not m:
                notes.append(f"app_keys {entry!r}: only scancodes and numeric keycodes carry over")
                continue
            buttons[("s" if entry.startswith("scan:") else "k") + m.group(1)] = m.group(2)
        out["buttons"] = buttons
    unused = sorted(k for k in theme if k not in SHARED)
    if unused:
        notes.append("not used by this home screen: " + ", ".join(unused))
    return out, notes


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit("usage: theme.py <theme folder> [base config.json]")
    out, notes = convert(sys.argv[1], sys.argv[2] if len(sys.argv) == 3 else None)
    for n in notes:
        print(n, file=sys.stderr)
    json.dump(out, sys.stdout, indent=2, ensure_ascii=False)
    print()


if __name__ == "__main__":
    main()
