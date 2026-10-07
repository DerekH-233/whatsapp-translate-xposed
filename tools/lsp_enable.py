"""Enable a module in LSPosed's database and give it scope, the same way the
LSPosed manager GUI does. Run against a *pulled copy* of modules_config.db.

Usage: python lsp_enable.py <db_path> <module_pkg> <apk_path> <scope_pkg> [scope_pkg...]
"""
import sqlite3
import sys

db = sys.argv[1]
mod = sys.argv[2]
apk = sys.argv[3]
scopes = sys.argv[4:]

c = sqlite3.connect(db)
cur = c.cursor()

print('existing module row:',
      list(cur.execute('select * from modules where module_pkg_name=?', (mod,))))
cur.execute('insert or replace into modules(module_pkg_name, apk_path) values(?,?)', (mod, apk))

print('existing state row:',
      list(cur.execute('select * from modules_state where module_pkg_name=?', (mod,))))
cur.execute('insert or replace into modules_state(module_pkg_name, user_id, enabled, scope_request_blocked)'
            ' values(?,?,?,?)', (mod, 0, 1, 0))

for s in scopes:
    cur.execute('insert or replace into scope(module_pkg_name, app_pkg_name, user_id) values(?,?,?)',
                (mod, s, 0))

c.commit()

print('module:', list(cur.execute('select * from modules where module_pkg_name=?', (mod,))))
print('state :', list(cur.execute('select * from modules_state where module_pkg_name=?', (mod,))))
print('scope :', list(cur.execute('select * from scope where module_pkg_name=?', (mod,))))
c.close()
