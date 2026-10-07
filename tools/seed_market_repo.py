"""Seed the module repository with a README.

GitHub refuses to create a release in a repository that has no commits, so the
first thing the market repo needs is one. Its README is also what the module
listing renders, so it doubles as the store description.
"""
import base64
import io
import json
import os
import subprocess
import sys

REPO = 'Xposed-Modules-Repo/io.github.derekh_233.watranslate'
SRC = 'https://github.com/DerekH-233/whatsapp-translate-xposed'

README = """<h1 align="center">WhatsApp 翻译助手</h1>

<p align="center">
把 WhatsApp 聊天消息翻译成你设定的语言，译文以小字显示在原文下方，颜色和字号可自定义。<br/>
免费、无需账号、无订阅，不经过任何第三方服务器。
</p>

---

## 功能

- **569 种目标语言**，可按中文名或英文名搜索
- **8 个翻译引擎**
  - 免密钥：Google 翻译、Google 备用通道、Bing、MyMemory
  - 需自填账号：百度、DeepL、微软、任意 OpenAI 兼容的 AI 接口
- 自定义 AI 引擎可编辑系统提示词
- 按对方号码的国家代码**离线**查表，得到国家、语言、货币与当地时间
- 译文颜色、字号、是否斜体均可调
- 设置修改后即时生效，无需重启 WhatsApp

## 环境要求

- 已 root 的 Android 10 及以上设备
- **LSPosed 2.x**（API 101 及以上）

本模块使用 **Modern Xposed API**（`io.github.libxposed`），不再依赖已废弃的
`de.robv.android.xposed`，因此不会出现「此模块使用了已废弃且即将移除的功能」提示。

## 安装

1. 安装 APK
2. LSPosed → **模块** → 启用 **WhatsApp 翻译助手**
3. 进入模块页面 → **作用域** → 勾选 WhatsApp
   - 普通版：`com.whatsapp`
   - Business 版：`com.whatsapp.w4b`
4. 强制停止 WhatsApp 后重新打开

## 隐私

- 仅申请 `INTERNET` 与 `ACCESS_NETWORK_STATE`
- 仅 hook `com.whatsapp` 与 `com.whatsapp.w4b`
- 唯一的网络行为由所选引擎决定；使用公开引擎时文本发送至对应服务，
  使用自填引擎时仅发送至自行配置的端点
- 无账号、无统计上报、无第三方服务器
- 对方信息由国家代码离线查表得出，号码不会离开设备

## 源码与许可

源码：{src}

代码采用 [GPL-3.0]({src}/blob/main/LICENSE)。语言与区号数据来自 Unicode CLDR 与
libphonenumber。
""".format(src=SRC)


def gh(args, stdin=None):
    r = subprocess.run(['gh'] + args, input=stdin, capture_output=True, text=True,
                       encoding='utf-8')
    return r.returncode, r.stdout, r.stderr


def main():
    # does the repo already have a README?
    code, out, err = gh(['api', 'repos/%s/contents/README.md' % REPO])
    sha = None
    if code == 0:
        try:
            sha = json.loads(out).get('sha')
        except Exception:
            pass
        print('README already present (sha %s); updating' % sha)
    else:
        print('README absent; creating the first commit')

    payload = {
        'message': 'Add module description',
        'content': base64.b64encode(README.encode('utf-8')).decode('ascii'),
        'branch': 'main',
    }
    if sha:
        payload['sha'] = sha

    tmp = os.path.join(os.environ['TEMP'], 'market_readme.json')
    with io.open(tmp, 'w', encoding='utf-8') as f:
        json.dump(payload, f)

    code, out, err = gh(['api', '-X', 'PUT',
                         'repos/%s/contents/README.md' % REPO, '--input', tmp])
    os.remove(tmp)
    if code != 0:
        print('FAILED:', err.strip()[:400])
        return 1
    print('README pushed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
