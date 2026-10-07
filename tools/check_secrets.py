"""Refuse to publish if anything looks like a credential.

Usage:  python tools/check_secrets.py            # scan the module tree
Exit code 1 means: do NOT commit.
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))

SKIP_DIRS = {'build', '.git', '.idea', '.gradle'}
SKIP_EXT = {'.apk', '.dex', '.jar', '.zip', '.png', '.jpg', '.webp', '.idsig'}

PATTERNS = [
    (re.compile(r'sk-[A-Za-z0-9]{16,}'), 'OpenAI/DeepSeek style secret key'),
    (re.compile(r'AIza[0-9A-Za-z_\-]{30,}'), 'Google API key'),
    (re.compile(r'Bearer\s+[A-Za-z0-9\-_.]{20,}'), 'inline bearer token'),
    (re.compile(r'(?i)(api[_-]?key|secret|token|password)\s*[=:]\s*[\'"]?[A-Za-z0-9\-_]{16,}'),
     'key/secret assignment'),
]

# these are fine: public client keys that Google ships in its own pages,
# used as plain constants in the translation back-ends.
ALLOW = [
    'AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520',
]

hits = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for fn in filenames:
        if os.path.splitext(fn)[1].lower() in SKIP_EXT:
            continue
        p = os.path.join(dirpath, fn)
        try:
            text = io.open(p, encoding='utf-8', errors='ignore').read()
        except Exception:
            continue
        for line_no, line in enumerate(text.splitlines(), 1):
            if any(a in line for a in ALLOW):
                continue
            for pat, why in PATTERNS:
                if pat.search(line):
                    hits.append((os.path.relpath(p, ROOT), line_no, why, line.strip()[:110]))

if hits:
    print('BLOCKED - possible credential(s) found:\n')
    for rel, ln, why, sample in hits:
        print('  %s:%d  [%s]' % (rel, ln, why))
        print('      %s' % sample)
    print('\n%d finding(s). Fix or .gitignore them before committing.' % len(hits))
    sys.exit(1)

print('OK - no credentials detected in %s' % ROOT)
