"""Regenerate the module's locale tables. Every input is properly licensed.

Inputs
  libphonenumber  PhoneNumberMetadata.xml   Apache-2.0   -> 拨号代码
  Unicode CLDR    via Babel                 Unicode Lic. -> 国名/货币/时区/语言

Both are fetched by tools/fetch_locale_sources.py, so this file only needs to
read them locally.

Earlier revisions also used datasets/country-codes, but that repository declares
no license at all, so it was dropped - see THIRD-PARTY.md.

Outputs
  assets/languages.json   {"en": {"zh": "英语", "en": "English"}, ...}
  assets/regions.json     {"44": {"cc","country","lang","tz","cur"}, ...}
"""
import io
import json
import os
import re

from babel import Locale
from babel.core import get_global

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, 'data')
META = os.path.join(DATA, 'PhoneNumberMetadata.xml')
ASSETS = os.path.abspath(os.path.join(HERE, '..', 'assets'))

# CLDR lists a territory's zones alphabetically, so "first zone" can be a tiny
# outlier (US -> America/Adak). These 31 multi-zone territories get an explicit
# primary zone; the other ~170 have exactly one zone and need no override.
PRIMARY_TZ = {
    'US': 'America/New_York', 'RU': 'Europe/Moscow', 'CA': 'America/Toronto',
    'BR': 'America/Sao_Paulo', 'AR': 'America/Argentina/Buenos_Aires',
    'AU': 'Australia/Sydney', 'MX': 'America/Mexico_City',
    'KZ': 'Asia/Almaty', 'GL': 'America/Nuuk', 'ID': 'Asia/Jakarta',
    'CL': 'America/Santiago', 'PF': 'Pacific/Tahiti', 'KI': 'Pacific/Tarawa',
    'FM': 'Pacific/Pohnpei', 'PT': 'Europe/Lisbon', 'ES': 'Europe/Madrid',
    'CN': 'Asia/Shanghai', 'CY': 'Asia/Nicosia', 'CD': 'Africa/Kinshasa',
    'EC': 'America/Guayaquil', 'DE': 'Europe/Berlin', 'MY': 'Asia/Kuala_Lumpur',
    'MH': 'Pacific/Majuro', 'MN': 'Asia/Ulaanbaatar', 'NZ': 'Pacific/Auckland',
    'PG': 'Pacific/Port_Moresby', 'PS': 'Asia/Gaza', 'UA': 'Europe/Kiev',
    'UM': 'Pacific/Wake', 'UZ': 'Asia/Tashkent', 'AQ': 'Antarctica/McMurdo',
}

# 12 dialling codes shared by several territories -> who the code "belongs" to.
PRIMARY_CC = {
    '1': 'US', '7': 'RU', '39': 'IT', '44': 'GB', '47': 'NO', '61': 'AU',
    '212': 'MA', '262': 'RE', '290': 'SH', '358': 'FI', '590': 'GP', '599': 'CW',
}

# CLDR knows these as territory languages but has no Chinese name; supplied here
# so regions.json never points at a missing key.
EXTRA_LANGS = {
    'aeb': ('突尼斯阿拉伯语', 'Tunisian Arabic'),
    'apc': ('黎凡特阿拉伯语', 'North Levantine Arabic'),
    'apd': ('苏丹阿拉伯语', 'Sudanese Arabic'),
    'arq': ('阿尔及利亚阿拉伯语', 'Algerian Arabic'),
    'ary': ('摩洛哥阿拉伯语', 'Moroccan Arabic'),
    'kri': ('克里奥尔语', 'Krio'),
}


def normalize_lang(code):
    if not code:
        return ''
    if code == 'zh_Hant':
        return 'zh-TW'
    if code in ('zh_Hans', 'zh'):
        return 'zh-CN'
    return code.split('_')[0]


def build_languages():
    zh_names = dict(Locale.parse('zh_Hans').languages)
    en_names = dict(Locale.parse('en').languages)
    out = {}
    for code, name_en in en_names.items():
        name_zh = zh_names.get(code)
        if not name_zh:
            continue
        if not (2 <= len(code) <= 3) or not code.isalpha():
            continue
        out[code] = {'zh': name_zh, 'en': name_en}
    out.setdefault('zh-CN', {'zh': '中文（简体）', 'en': 'Chinese (Simplified)'})
    out.setdefault('zh-TW', {'zh': '中文（繁体）', 'en': 'Chinese (Traditional)'})
    for code, (z, e) in EXTRA_LANGS.items():
        out.setdefault(code, {'zh': z, 'en': e})
    return out


def dial_codes():
    """region -> dialling code, from libphonenumber (Apache-2.0)."""
    s = io.open(META, encoding='utf-8', errors='replace').read()
    pairs = re.findall(r'<territory\s+id="([A-Z]{2})"[^>]*?countryCode="(\d+)"', s)
    return dict(pairs)


def pick_currency(territory):
    """Current circulating currency for a territory, from CLDR."""
    entries = (get_global('territory_currencies') or {}).get(territory) or []
    for cur, _start, end, tender in entries:
        if end is None and tender:
            return cur
    return entries[0][0] if entries else ''


def build_regions():
    zones = get_global('territory_zones') or {}
    languages = get_global('territory_languages') or {}
    territories_zh = dict(Locale.parse('zh_Hans').territories)

    by_dial = {}
    for cc, dial in sorted(dial_codes().items()):
        prefer = PRIMARY_CC.get(dial)
        if prefer and cc != prefer:
            continue
        if not prefer and dial in by_dial:
            continue

        tz_list = zones.get(cc) or []
        tz = PRIMARY_TZ.get(cc) or (tz_list[0] if tz_list else '')

        lang, best = '', -1.0
        for code, meta in (languages.get(cc) or {}).items():
            pct = float(meta.get('population_percent') or 0)
            if pct > best:
                best, lang = pct, code

        by_dial[dial] = {
            'cc': cc,
            'country': territories_zh.get(cc, cc),
            'lang': normalize_lang(lang),
            'tz': tz,
            'cur': pick_currency(cc),
        }
    return by_dial


def main():
    if not os.path.isfile(META):
        raise SystemExit('missing %s - run tools/fetch_locale_sources.py first' % META)

    langs = build_languages()
    regions = build_regions()
    print('languages: %d' % len(langs))
    print('regions  : %d' % len(regions))

    for probe in ('1', '7', '39', '44', '61', '86', '81', '49', '33', '91',
                  '55', '82', '66', '84', '20', '27'):
        r = regions.get(probe)
        if r:
            print('   +%-5s %-8s %-7s %-12s %s' % (probe, r['country'], r['lang'],
                                                   r['tz'], r['cur']))

    missing = sorted({v['lang'] for v in regions.values()
                      if v['lang'] and v['lang'] not in langs})
    orphan_cc = sorted({v['cc'] for v in regions.values()
                        if not v['country'] or v['country'] == v['cc']})
    print('languages referenced but absent:', len(missing), missing[:8])
    print('territories without a Chinese name:', len(orphan_cc), orphan_cc[:8])

    os.makedirs(ASSETS, exist_ok=True)
    with io.open(os.path.join(ASSETS, 'languages.json'), 'w', encoding='utf-8') as f:
        json.dump(langs, f, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
    with io.open(os.path.join(ASSETS, 'regions.json'), 'w', encoding='utf-8') as f:
        json.dump(regions, f, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
    print('written ->', ASSETS)


if __name__ == '__main__':
    main()
