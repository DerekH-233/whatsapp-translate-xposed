# 开发与发布

面向改这个模块的人。普通用户请看 [README](../README.md)。

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

早期版本第一条走的是 `android.app.AndroidAppHelper` 反射，在 LSPosed 上一直抛
`ClassNotFoundException`，实际从未生效；改为从 view 取资源表后名称查询才真正工作。

### 配置的跨进程读取

设置页与被 hook 的进程之间走框架提供的远程配置：

- 设置页把每次修改通过 `XposedService` 推送到框架（`PrefsBridge`），框架负责持久化
- 被 hook 的进程用 `getRemotePreferences("prefs")` 读取，并按 800ms 缓存重新拉取，
  因此设置修改后无需重启 WhatsApp

更早的版本依赖 `XSharedPreferences` 加一个世界可读的镜像文件，因为 targetSdk 34 下
`MODE_WORLD_READABLE` 已受限制、应用私有目录跨进程读不到。迁移到现代 API 后这套变通方案
已完全删除。

---

## 构建

无需 Gradle。依赖 JDK 17 或更高版本、Android SDK（含 `build-tools` 与
`platforms/android-34`）以及 Python 3。

首次构建前先取一次依赖：

```powershell
.\tools\fetch_libxposed.ps1   # 从 Maven Central 取 libxposed api / service
```

```powershell
.\build.ps1              # 构建、签名并安装到已连接设备
.\build.ps1 -SkipInstall # 仅构建
```

构建流程：`javac(app) → d8 → aapt2 compile/link → repack → zipalign → apksigner`

两个 libxposed 构件的处理方式**不同**，这一点不能弄错：

| 构件 | 处理 | 原因 |
|---|---|---|
| `io.github.libxposed:api` | 仅加入编译 classpath，**不打包** | 运行期由框架提供；打进 APK 会与框架的类冲突 |
| `io.github.libxposed:service` | **打包进 dex** | 其中的 `XposedProvider` 必须随 APK 发布，框架才能把服务 binder 交给设置页 |

`tools/dex_defined_classes.py` 可以直接核验这一点：它会读取 dex 的 `class_defs`，
报告某个类究竟是被*定义*进 dex 还是仅被引用，而不像字符串扫描那样两者不分。

签名密钥库按以下顺序查找，均不存在时自动生成一个本地调试用密钥库（已被 `.gitignore`
排除），因此新克隆的仓库可直接构建：

1. 环境变量 `LSTRANS_KEYSTORE`
2. `../watrans.keystore`
3. `./debug.keystore`

发布用的签名密钥库不应提交到仓库。它一旦丢失，已发布的版本将无法再被覆盖升级。

---

## 重新生成语言与区号表

```powershell
python tools\fetch_locale_sources.py   # 下载 libphonenumber 元数据
python tools\gen_locale_data.py        # 生成 assets/languages.json 与 regions.json
```

`assets/` 下为生成物，已提交到仓库，常规构建无需网络访问。

CLDR 的时区列表按字母序排列，直接取首项会得到并非主要时区的结果（例如
`US → America/Adak`），因此生成器用显式表指定了 31 个多时区地区与 12 个共享拨号代码的
主要项。

---

## 项目结构

```
AndroidManifest.xml          组件与 XposedProvider 声明
META-INF/xposed/
  java_init.list             入口类（现代 API 的注册方式）
  module.prop                minApiVersion / targetApiVersion / staticScope
  scope.list                 声明的作用域
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
  seed_market_repo.py        给模块仓库推 README（空仓库不能建 release）
  lsp_enable.py              辅助启用模块
build.ps1, repack.py         构建脚本
```

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
.\tools\release.ps1 -Version 1.3.0 -VersionCode 4
.\tools\release.ps1 -Version 1.3.0 -VersionCode 4 -Notes .\NOTES.md
.\tools\release.ps1 -Version 1.3.0 -VersionCode 4 -SkipMarket   # 只发源码
```

它会依次：改 `build.ps1` 里的版本号 → 构建签名 → 推送源码、tag 与源码 release →
在模块仓库创建 release。脚本会先检查工作区是否干净、模块仓库是否存在，避免发出版本与
代码不对应。

几个已知的坑，脚本已处理：

- **模块仓库的 release tag 必须是 `<versionCode>-<versionName>`**（例如 `3-1.2.0`），
  且必须带 apk 附件
- **空仓库不能建 release**，GitHub 会返回 `422 Repository is empty`，所以第一次要先推
  README（`tools/seed_market_repo.py`）
- **只推 tag 不建 release** 会让 GitHub 的 "Latest" 停留在上一个版本，两边看起来不一致
- 官方说明：单纯替换 release 的附件不会触发索引，必须改动 release 本身，因此每次都创建
  新的 release 而不是覆盖旧的

提交模块到仓库：<https://modules.lsposed.org/submission>

**仓库描述必须在网页上设置**。它会变成模块在市场里的显示名称，而协作者只有 `maintain`
权限、没有 `admin`，API 更新会返回 `404 Not Found`。

### 与仓库维护方沟通

那个仓库的机器人**只按标题前缀分类**，没有前缀的 issue 会立刻被打上 `spam` 并锁定，
连回复都没有。可用的前缀（从 300 条已有 issue 里统计出来的）：

| 前缀 | 用途 |
|---|---|
| `[submission] <包名>` | 申请新模块（表单生成的就是这个） |
| `[issue] ...` | 反馈问题 |
| `[appeal] ...` | 申诉包名或归属 |
| `[suggestion] ...` | 建议 |


---

## 关于 Xposed API 版本

模块使用 **Modern Xposed API**（`io.github.libxposed:api` 102）。早期版本基于传统的
`de.robv.android.xposed`，该 API 已被 LSPosed 标记为废弃并会在未来移除，模块在管理器里
会被显示为 `legacy` 并提示「使用了已废弃且即将移除的功能」。

迁移涉及：入口改为 `XposedModule` 子类并由 `META-INF/xposed/` 注册；钩子改为拦截器链；
跨进程配置改用框架的远程配置；不再携带任何手写的 `de.robv` 声明。

顺带一提：**仅升级 API 版本号不等于迁移**。把 `xposedminversion` 从 93 改成 102 会让
框架按新协议去找 `META-INF/xposed/java_init.list` 里的入口，找不到就直接不加载。
