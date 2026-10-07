# WhatsApp 翻译助手

面向 WhatsApp 的 Xposed 翻译模块。将聊天消息翻译为设定的语言，译文以小字显示在原文下方，颜色与字号可自定义。

无需注册或登录，不经过任何第三方服务器；翻译由公开接口或调用者自行配置的 AI 接口完成。

> 非官方第三方模块，与 Meta 无关联。第三方组件与数据来源见 [THIRD-PARTY.md](THIRD-PARTY.md)。

---

## 功能

- 将收到的消息翻译为 569 种语言中的任意一种，译文显示在原文下方
- 4 个无需密钥的公开翻译引擎，另有 4 个支持自填账号的引擎
- 支持任意 OpenAI 兼容的 AI 接口，可自定义系统提示词
- 译文颜色、字号、是否斜体可调
- 根据对方号码的国家代码离线查表，得到国家、语言、货币与当地时间
- 设置修改后即时生效，无需重启 WhatsApp

---

## 截图

左侧为翻译效果，右侧为设置界面。

![翻译效果与设置界面](docs/preview.jpg)

---

## 环境要求

- 已 root 的 Android 10（API 29）及以上设备
- 支持 Modern Xposed API 的框架，即 **LSPosed 2.x**（API 101 及以上）

本模块使用 Modern Xposed API（`io.github.libxposed`），
不再依赖已废弃的 `de.robv.android.xposed`。框架会把模块识别为 `modern` 而非 `legacy`。

---

## 安装

1. 从 Releases 下载 `LSTrans.apk` 并安装
2. 打开 LSPosed → **模块** → 启用 **WhatsApp 翻译助手**
3. 进入模块页面 → **作用域** → 勾选 WhatsApp。作用域在 `scope.list` 里声明过，
   管理器会把它列为推荐项，但**仍需确认一次**，不会自动生效
   - 普通版：`com.whatsapp`
   - Business 版：`com.whatsapp.w4b`
4. 强制停止 WhatsApp 后重新打开
5. 从桌面图标或 LSPosed 模块列表进入设置，选择引擎与目标语言

配置修改后即时生效，无需重启 WhatsApp。

---

## 翻译引擎

切换引擎后，界面会自动展开该引擎所需的配置项。

| 引擎 | 密钥 | 说明 |
|---|---|---|
| Google 翻译 | 否 | `translate-pa.googleapis.com`，默认选项 |
| Google 翻译 · 备用通道 | 否 | `translate_a/single`，主通道异常时使用 |
| Bing 翻译 | 否 | 自动获取会话 token |
| MyMemory 翻译记忆库 | 否 | 公开翻译记忆库 |
| 百度翻译 | 是 | 需 AppID 与密钥，请求需 MD5 签名 |
| DeepL | 是 | 以 `:fx` 结尾的密钥自动使用免费端点 |
| 微软翻译 | 是 | 可选 Region 头 |
| 自定义 AI | 是 | 任意 OpenAI 兼容接口；端点可只填 base，会自动补 `/v1/chat/completions` |

前四个引擎无需任何账号即可使用。

### 自定义 AI 的系统提示词

自定义 AI 引擎会先发送一条系统提示词，再发送待翻译文本。`${toCode}` 会被替换为目标语言代码，默认值见 `Translators.DEFAULT_AI_PROMPT`。

系统提示词是必需的：若完全不发送，部分通用对话模型不会执行翻译，而是把输入当作待处理的普通提问。默认提示词包含三条规则，分别用于抑制多余的说明性前缀、保持段落结构、以及保留不应翻译的专有名词与代码。

需要说明的是，这三条规则的措辞对较新模型的翻译质量影响有限，其价值主要在于对较弱模型、复杂格式文本和容易附加解释的模型提供保障。提示词随每条消息发送，约 110 token，且仅对自定义 AI 引擎生效——其余引擎不是对话式接口，不接受系统提示词。

---

## 语言与对方信息

### 语言

内置 569 种语言，来自 Unicode CLDR，选择器支持按中文名或英文名搜索。

### 对方信息

根据对方号码的国家代码离线查表，得出国家、语言、货币与当地时间。号码不会离开设备。

CLDR 的时区列表按字母序排列，直接取首项会得到并非主要时区的结果，因此 `tools/gen_locale_data.py` 使用显式表指定了 31 个多时区地区与 12 个共享拨号代码的主要项。

---

## 工作原理

### 渲染

实现方式是在 WhatsApp 自身的消息气泡上改写文本，不注入额外视图。

1. 通过现代 API 的拦截器链接管 `TextView.setText`，覆盖所有接收 `CharSequence` 的重载
2. 仅处理资源 ID 为 `message_text`、`caption`、`quoted_text` 的 view，因此标题栏、按钮与聊天列表预览不受影响
3. 翻译在线程池中异步执行，完成后回到主线程将文本改写为 `原文 + "\n" + 译文`，仅对译文部分应用 `ForegroundColorSpan`（自定义颜色）与 `RelativeSizeSpan`（自定义字号）
4. 防递归使用 `ThreadLocal` 标记；防 view 复用错乱用一张以 view 为弱键的状态表保存源文快照，回写前比对（现代 API 不再提供附加字段）
5. 相同文本、引擎与目标语言的组合只请求一次

### 资源 ID 解析

按以下顺序解析：

1. 从目标进程任意 view 的 `getResources()` 按名称查询该应用自己的资源表
2. R 类反射（`Class.forName("<pkg>.R$id")`）
3. 内置 ID 兜底（`message_text = 0x7f0b26b8`，取自 WhatsApp 的 `resources.arsc`）

### 收发两个方向

过滤条件是 view 的资源 ID，而非消息方向，因此发出与收到的消息都会显示译文。当目标语言与原文相同时，译文与原文一致，会被跳过而不重复显示。

### 配置的跨进程读取

配置由模块设置页写入，由 WhatsApp 进程读取，走框架提供的远程配置：

- 设置页把每次修改通过 `XposedService` 推送到框架（`PrefsBridge`），框架负责持久化
- 被 hook 的进程用 `getRemotePreferences("prefs")` 读取这份配置

早期版本依赖 `XSharedPreferences` 加一个世界可读的镜像文件，因为 targetSdk 34 下
`MODE_WORLD_READABLE` 已受限制、应用私有目录跨进程读不到。迁移到现代 API 后这套
变通方案已完全删除。

---

## 隐私

- 仅申请 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限
- 仅 hook `com.whatsapp` 与 `com.whatsapp.w4b` 两个包
- 唯一的网络行为由所选引擎决定：使用公开引擎时文本发送至对应服务，使用自填引擎时仅发送至自行配置的端点
- 无账号、无统计上报、无第三方服务器
- 对方信息由国家代码离线查表得出，号码不会离开设备

---

## 构建

无需 Gradle。依赖 JDK 17 或更高版本、Android SDK（含 `build-tools` 与 `platforms/android-34`）以及 Python 3。

```powershell
.\build.ps1              # 构建、签名并安装到已连接设备
.\build.ps1 -SkipInstall # 仅构建
```

首次构建前先取一次依赖：

```powershell
.\tools\fetch_libxposed.ps1   # 从 Maven Central 取 libxposed api / service
```

构建流程：`javac(app) → d8 → aapt2 compile/link → repack → zipalign → apksigner`

两个 libxposed 构件的处理方式**不同**，这一点不能弄错：

| 构件 | 处理 | 原因 |
|---|---|---|
| `io.github.libxposed:api` | 仅加入编译 classpath，**不打包** | 运行期由框架提供；打进 APK 会与框架的类冲突 |
| `io.github.libxposed:service` | **打包进 dex** | 其中的 `XposedProvider` 必须随 APK 发布，框架才能把服务 binder 交给设置页 |

`tools/dex_defined_classes.py` 可以直接核验这一点：它会报告某个类究竟是被
*定义*进 dex 还是仅被引用，而不像字符串扫描那样两者不分。

签名密钥库按以下顺序查找，均不存在时自动生成一个本地调试用密钥库（已被 `.gitignore` 排除），因此新克隆的仓库可直接构建：

1. 环境变量 `LSTRANS_KEYSTORE`
2. `../watrans.keystore`
3. `./debug.keystore`

发布用的签名密钥库不应提交到仓库。

### 重新生成语言与区号表

```powershell
python tools\fetch_locale_sources.py   # 下载 libphonenumber 元数据
python tools\gen_locale_data.py        # 生成 assets/languages.json 与 regions.json
```

`assets/` 下为生成物，已提交到仓库，常规构建无需网络访问。

---

## 项目结构

```
AndroidManifest.xml          组件与 XposedProvider 声明
META-INF/xposed/
  java_init.list             入口类（现代 API 的注册方式）
  module.prop                minApiVersion / targetApiVersion / staticScope
  scope.list                 预声明作用域
assets/
  languages.json             569 种语言（生成物）
  regions.json               206 个地区的区号信息（生成物）
res/mipmap-*/                五档密度图标
res/values/strings.xml       模块名称与说明（框架从这里读取）
src/io/github/derekh_233/watranslate/
  ModuleEntry.java           XposedModule 子类，仅对 com.whatsapp 与 com.whatsapp.w4b 生效
  MessageHook.java           核心：setText 钩子与译文渲染
  Translators.java           8 个引擎的实现
  Engine.java                引擎枚举
  LocaleInfo.java            国家、语言、货币与当地时间
  PickerDialog.java          可搜索选择器（语言与区号）
  Prefs.java                 被 hook 侧：读框架的远程配置
  PrefsBridge.java           设置页侧：把修改推送到框架
  SettingsActivity.java      设置界面，同时是模块入口
  Logger.java                日志
tools/
  release.ps1                一条命令同时发到源码仓库与模块仓库
  fetch_libxposed.ps1        下载 libxposed api / service
  dex_defined_classes.py     核验某个类是被定义进 dex 还是仅被引用
  fetch_locale_sources.py    下载数据源
  gen_locale_data.py         生成语言与区号表
  make_icon.py               生成图标
  check_secrets.py           提交前扫描密钥
  lsp_enable.py              辅助启用模块
build.ps1, repack.py         构建脚本
```

---

## 常见问题

**译文没有出现**

在 Xposed 日志中搜索 `LSTrans`。日志会记录所用端点、HTTP 状态码与接口返回内容，可据此判断是接口不可用还是配置有误。公开引擎依赖对应服务的可用性，长期可能发生变化。

**聊天列表的预览没有翻译**

这是有意为之。列表预览使用不同的 view ID，翻译它会造成刷屏。

**修改资源 ID 后失效**

WhatsApp 的资源 ID 可能随版本变化。正常情况下模块会从目标进程的资源表按名称查到，
不受版本影响；只有连名称查询和 R 类反射都失效时才会回落到 `MessageHook` 中内置的三个 ID。

**作用域**

模块在 `META-INF/xposed/scope.list` 里声明了 `com.whatsapp` 与 `com.whatsapp.w4b`，
并设置了 `staticScope=true`。声明的作用域只是**推荐项**，管理系统会把它作为建议列出，
但**不会自动应用** —— 需要在模块页面里勾选一次。

普通版与 Business 版是两个不同的包，装着哪个就勾哪个；两个都装就都勾。
注意作用域里若包含本机未安装的包，设置会直接报错（例如只有 Business 版时勾选普通版）。

**框架版本**

模块声明 `minApiVersion=101`，需要 LSPosed 2.x。在只支持旧版 API 的框架上模块不会被加载。
可以用 `lspctl module show <包名>` 确认框架把模块识别成了 `modern` 还是 `legacy`。

---

## 发布

发布涉及两个仓库，但**源码只有一份**：

| 位置 | 内容 |
|---|---|
| `DerekH-233/whatsapp-translate-xposed` | 全部源码，日常开发都在这里 |
| `Xposed-Modules-Repo/io.github.derekh_233.watranslate` | 只有 README 与 release，供 LSPosed 管理器索引 |

第二个仓库由 LSPosed 模块仓库的组织创建，作者以协作者身份加入；里面不放任何代码。
管理器的「仓库」标签页就是索引它下面的 release，因此**新版本必须发布到那个仓库**，
只发在源码仓库不会被收录。

一条命令完成两边：

```powershell
.\tools\release.ps1 -Version 1.2.0 -VersionCode 3
.\tools\release.ps1 -Version 1.2.0 -VersionCode 3 -SkipMarket   # 只发源码
```

它会依次：改 `build.ps1` 里的版本号 → 构建签名 → 推送源码与 tag →
在模块仓库创建 release。脚本会先检查工作区是否干净、模块仓库是否存在，
避免发出版本与代码不对应。

模块仓库对 release 有格式要求：**tag 必须是 `<versionCode>-<versionName>`**
（例如 `3-1.2.0`），且必须带 apk 附件。脚本已按此拼接。
另外官方说明提到，单纯替换 release 的附件不会触发索引，必须改动 release 本身，
所以每次都创建新的 release 而不是覆盖旧的。

提交模块到仓库：<https://modules.lsposed.org/submission>

---

## 关于 Xposed API 版本

模块使用 **Modern Xposed API**（`io.github.libxposed:api` 102）。早期版本基于传统的
`de.robv.android.xposed`，该 API 已被 LSPosed 标记为废弃并会在未来移除，模块在管理器里
会被显示为 `legacy` 并提示「使用了已废弃且即将移除的功能」。

迁移涉及：入口改为 `XposedModule` 子类并由 `META-INF/xposed/` 注册；钩子改为拦截器链；
跨进程配置改用框架的远程配置；不再携带任何手写的 `de.robv` 声明。

---

## 许可

代码采用 [GPL-3.0](LICENSE)。

第三方组件与数据来源的署名见 [THIRD-PARTY.md](THIRD-PARTY.md)：Unicode CLDR（Unicode License）、libphonenumber（Apache-2.0）、Xposed API（Apache-2.0）。
