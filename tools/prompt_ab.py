"""A/B/C measurement: does the translation system prompt actually change anything?

Same source text, same model, same temperature. Only the system message differs:
  A  no system message at all
  B  the default prompt shipped with the module
  C  a one-line naive prompt

Credentials come from the environment - never hardcode a key in this file:

    set DS_API_KEY=sk-...             (Windows cmd)
    $env:DS_API_KEY="sk-..."          (PowerShell)
    export DS_API_KEY=sk-...          (bash)

Optional overrides: DS_ENDPOINT, DS_MODEL.
"""
import json
import os
import sys

import requests

ENDPOINT = os.environ.get('DS_ENDPOINT', 'https://api.deepseek.com/v1/chat/completions')
MODEL = os.environ.get('DS_MODEL', 'deepseek-v4-flash')
KEY = os.environ.get('DS_API_KEY', '')

if not KEY:
    sys.exit('DS_API_KEY is not set. Export it before running this tool - '
             'do not put your key in the source.')

SOURCE = (
    "Please check the PAYMENT_API_KEY in config.yaml before Friday.\n"
    "\n"
    "We restarted the staging server twice today, but the error still shows up in Grafana."
)

DEFAULT_PROMPT = (
    "You are a professional \u4e2d\u6587\uff08\u7b80\u4f53\uff09 native translator who needs to "
    "fluently translate text into \u4e2d\u6587\uff08\u7b80\u4f53\uff09.\n"
    "\n"
    "## Translation Rules\n"
    "1. Output only the translated content, without explanations or additional content "
    "(such as \"Here's the translation:\" or \"Translation as follows:\")\n"
    "2. The returned translation must maintain exactly the same number of paragraphs "
    "and format as the original text\n"
    "3. For content that should not be translated (such as proper nouns, code, etc.), "
    "keep the original text.\n"
    "\n"
    "## OUTPUT FORMAT:\n"
    "Output translation directly"
)

NAIVE_PROMPT = "Translate the user's text into Simplified Chinese."


def call(label, system):
    messages = []
    if system is not None:
        messages.append({'role': 'system', 'content': system})
    messages.append({'role': 'user', 'content': SOURCE})
    body = {'model': MODEL, 'temperature': 0.2, 'stream': False, 'messages': messages}
    r = requests.post(ENDPOINT, headers={
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + KEY,
    }, data=json.dumps(body), timeout=60)
    if r.status_code != 200:
        print('=== %s === HTTP %d %s' % (label, r.status_code, r.text[:200]))
        return None
    out = r.json()['choices'][0]['message']['content']
    print('=' * 70)
    print('=== %s ===' % label)
    print('--- raw ---')
    print(repr(out))
    print('--- rendered ---')
    print(out)
    return out


if __name__ == '__main__':
    a = call('A  no system prompt', None)
    b = call('B  module default prompt', DEFAULT_PROMPT)
    c = call('C  naive one-liner prompt', NAIVE_PROMPT)

    print()
    print('=' * 70)
    print('=== 判定 ===')
    for name, out in (('A no-prompt', a), ('B default', b), ('C naive', c)):
        if out is None:
            print('%-12s 无响应' % name)
            continue
        paras = len([p for p in out.split('\n') if p.strip()])
        kept = [w for w in ('PAYMENT_API_KEY', 'config.yaml', 'Grafana') if w in out]
        cjk = sum(1 for ch in out if '\u4e00' <= ch <= '\u9fff')
        print('%-12s 段落数=%d  保留标识符=%s  汉字数=%d'
              % (name, paras, kept, cjk))

    with open('tools/data/prompt_ab_result.json', 'w', encoding='utf-8') as f:
        json.dump({'source': SOURCE, 'A': a, 'B': b, 'C': c}, f,
                  ensure_ascii=False, indent=2)
    print('\n结果已写入 tools/data/prompt_ab_result.json')
