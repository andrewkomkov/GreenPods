#!/usr/bin/env python3
"""Reassembles a GreenPods state dump out of logcat.

The dump is emitted in `[n/total]` chunks because logcat drops anything past roughly
4 kB in one message, and a truncated JSON document is worse than none: it parses as far
as the cut and then lies. See docs/adb.md.
"""
import subprocess, re, json, sys
raw = subprocess.run(['adb','logcat','-d','-s','GreenPodsDebug'], capture_output=True, text=True).stdout
parts = {}
plain = []
for line in raw.splitlines():
    if 'GreenPodsDebug:' not in line: continue
    msg = line.split('GreenPodsDebug:',1)[1].strip()
    m = re.match(r'\[(\d+)/(\d+)\]\s(.*)', msg, re.S)
    if m: parts[int(m.group(1))] = m.group(3)
    else: plain.append(msg)
doc = ''.join(parts[k] for k in sorted(parts)) if parts else (plain[-1] if plain else '')
try:
    print(json.dumps(json.loads(doc), indent=1, ensure_ascii=False))
except Exception:
    print('\n'.join(plain[-6:]))
