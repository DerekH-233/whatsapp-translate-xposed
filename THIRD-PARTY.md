# 第三方组件与数据来源

本项目的**代码**（`src/`、`stubs/`、`tools/`、`build.ps1`、`repack.py`）为本仓库原创，采用
[GPL-3.0](LICENSE)。

下面这些**数据与接口**来自第三方，各自按其原许可证使用，一并在此署名。

---

## 1. 国际电话拨号代码（`tools/data/PhoneNumberMetadata.xml`）

| | |
|---|---|
| 来源 | [google/libphonenumber](https://github.com/google/libphonenumber) |
| 文件 | `resources/PhoneNumberMetadata.xml` |
| 许可证 | **Apache License 2.0**（见 `licenses/Apache-2.0.txt`） |

版权声明（依 Apache-2.0 第 4 条保留）：

```
Copyright (C) 2009 The Libphonenumber Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

用途：`tools/gen_locale_data.py` 从该文件提取「地区代码 → 国际拨号代码」，
生成 `assets/regions.json`。

---

## 2. 语言名称、国名、货币、时区（`assets/languages.json`、`assets/regions.json`）

| | |
|---|---|
| 来源 | [Unicode CLDR](https://cldr.unicode.org/)（经 [Babel](https://babel.pocoo.org/) 读取） |
| 许可证 | **Unicode License v3**（见 `licenses/UNICODE-LICENSE.txt`） |

```
Copyright © 1991-2026 Unicode, Inc. All rights reserved.
Distributed under the Terms of Use in https://www.unicode.org/copyright.html
```

用途：

- `assets/languages.json` —— 569 种语言的中文/英文名称
- `assets/regions.json` 的 `country`（中文国名）、`cur`（当前流通货币）、
  `tz`（主要时区）、`lang`（各地区主要语言）四项

`tools/gen_locale_data.py` 里的 `PRIMARY_TZ`（31 个多时区地区的主时区）与
`PRIMARY_CC`（12 个共享拨号代码的主地区）是本项目自行编写的修正表，
因为 CLDR 的时区列表按字母序排列，直接取首项会得到 `US → America/Adak` 这类边角结果。

---

## 3. Xposed API 声明（`stubs/de/robv/android/xposed/`）

`stubs/` 下的文件是**对 Xposed 公开 API 方法签名的独立重写**，仅用于编译期，
运行期会被框架自身的实现覆盖。

Xposed API 由 rovo89 创建，现由 [LSPosed](https://github.com/LSPosed/LSPosed) 维护，
以 **Apache License 2.0** 发布。

---

## 4. Google 翻译公开客户端密钥

`src/com/littlesauce/watrans/Translators.java` 中的
`AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520` 是 **Google 自家网页客户端源码里印着的公开密钥**，
用于免注册调用 `translate-pa.googleapis.com/v1/translateHtml`。

它不是本项目的凭据，也不是任何用户的凭据；`tools/check_secrets.py` 对它做了白名单例外。

---

## 5. 曾被使用、现已移除的数据源

早期版本还使用了 [datasets/country-codes](https://github.com/datasets/country-codes)
的 `country-codes.csv`。**该仓库未声明任何许可证**，不适合放进开源项目，
因此已整体替换为上述 libphonenumber + CLDR 方案。
如果你在旧提交里看到 `country-codes.csv`，那是历史遗留，当前代码不依赖它。

---

## 6. 商标声明

WhatsApp 是 Meta Platforms, Inc. 的商标。本项目是**非官方的第三方 Xposed 模块**，
与 Meta 无任何关联、未获其授权或背书。项目名称中的 "WhatsApp" 仅用于说明适配对象。

本项目的许可证只覆盖本仓库的代码，不授予任何对 WhatsApp 客户端本身的权利。
