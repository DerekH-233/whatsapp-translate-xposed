"""Fetch the two upstream data files used to build the locale tables.

  PhoneNumberMetadata.xml   google/libphonenumber   Apache-2.0
  (timezones, currencies, languages and names all come from CLDR via Babel,
   which is resolved from the Python package index - no download needed)

Run this only when you want to refresh the tables; the generated json files are
committed so normal builds do not need network access.
"""
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, 'data')

SOURCES = [
    ('PhoneNumberMetadata.xml',
     'https://raw.githubusercontent.com/google/libphonenumber/master/resources/PhoneNumberMetadata.xml'),
]

if __name__ == '__main__':
    os.makedirs(DATA, exist_ok=True)
    for name, url in SOURCES:
        out = os.path.join(DATA, name)
        print('fetching %s' % name)
        try:
            with urllib.request.urlopen(url, timeout=120) as r, open(out, 'wb') as f:
                f.write(r.read())
        except Exception as e:
            sys.exit('download failed: %s' % e)
        print('   -> %s (%d bytes)' % (out, os.path.getsize(out)))
    print('now run: python tools/gen_locale_data.py')
