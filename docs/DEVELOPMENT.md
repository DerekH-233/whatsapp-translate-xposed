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
6. 译文外观（颜色、字号、斜体）有单独的指纹，发生变化时重绘屏幕上已有的消息；
   否则只有之后重新绑定的消息会采用新样式，看起来像设置无效

### 会话识别

发消息时要知道对方是哪国人，才能自动选用对方那种语言。

1. 挂 `Activity.onCreate(Bundle)`，只处理类名含 `Conversation` 的页面
2. 该页面把目标会话放在 intent 的 `jid` extra 里，形如
   `8613800000000@s.whatsapp.net`；群聊是 `...@g.us`，隐藏号码是 `...@lid`
3. 取号码部分交给 `LocaleInfo.forPhone`，由离线表得出国家、语言、货币、当地时间
4. 群聊与 `@lid` 没有单一国家，直接留空，调用方回退到设置里指定的语言

整套推导**每次实时进行，不落任何存储**——这一点是刻意的：框架给被 hook 一侧的
远程配置是只读的，而 libxposed 没有反向通道能把数据送回模块 App。

会话进程里没有模块自己的 Context，`getAssets()` 取到的是宿主 App 的资源，
因此 `LocaleInfo.loadFromApk()` 直接从模块 APK 的 zip 里读 `assets/*.json`。
这样也避开了 `AssetManager.addAssetPath` 这个在新版 Android 上受限的隐藏 API。

### 输入框翻译

输入框上方注入两个按钮（语言 / 翻译），作为 `conversation_layout` 的子节点定位在
`footer` 之上。

1. 由 `TextView.setText` 钩子识别输入框（资源名 `entry` / `text_entry`），首次出现时注入
2. 药丸行以 `layout_above=footer` 定位；消息列表的规则同步改为位于药丸行之上
3. 右边缘对齐输入框本体（资源名 `input_layout`），不越过发送键那一列，
   以免遮挡 WhatsApp 的「置底」按钮
4. 颜色取自输入框自身的文字颜色并降低不透明度，深浅主题下均与界面一致
5. 翻译期间用户若改了草稿，结果直接丢弃，绝不覆盖
6. 长按发送键保留为等效入口

语言选择保存在宿主应用的私有目录。框架给被 hook 一侧的远程配置是只读的，
且没有反向通道，该侧没有其它可持久化的位置。

实现上踩过的两个坑：

- **不能向 composer 内部插入子节点**。在输入行与其父容器之间插入会把 `footer`
  高度从 222px 撑到 2312px，消息区被压成一条——那个容器按只有一个子节点设计
- **`uiautomator` 输出的层级是压平的**。按层数向上数会落到
  `MaxHeightHorizontalFlexLayout`，与实际层级不符；改为按资源名（`footer`）定位

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
- 被 hook 的进程用 `getRemotePreferences("prefs")` 读取，并按 800ms 缓存重新拉取

缓存窗口意味着改动会在下一次读取时生效，而不是写入的那一刻。实际体验上是「改完很快就能看到」，
但不要写成「立即生效、无需重启」这种绝对说法：拉取周期、进程存活状态、框架实现都可能让这句话不成立。

推送时会先清空框架侧已有的键再复制，否则编辑器里删掉或清空的项会残留在框架那一侧。

更早的版本依赖 `XSharedPreferences` 加一个世界可读的镜像文件，因为 targetSdk 34 下
`MODE_WORLD_READABLE` 已受限制、应用私有目录跨进程读不到。迁移到现代 API 后这套变通方案
已完全删除。

### 设置界面

无 ActionBar，标题栏自绘，避免系统主题强制其配色。配色按当前主题在运行期填充，
深浅两套均取自 WhatsApp 自身的色值。

四个分页在 `onCreate` 中一次性构建，每个分区由 `guarded()` 单独包裹：某一分区抛出异常时
只在该位置显示一行提示，设置页仍可打开。原先无此隔离，任一分区出错会导致整个界面无法启动。

分组行统一按「标签保持自然宽度、值占据剩余宽度」分配，否则较长的值会把标签压成零宽。

---

## 构建

无需 Gradle。依赖 JDK 17 或更高版本、Android SDK（含 `build-tools` 与
`platforms/android-34`）以及 Python 3。

首次构建前先取一次依赖：

```powershell
.\tools\fetch_libxposed.ps1   # 从 Maven Central 取 libxposed api / service / interface
```

```powershell
.\build.ps1              # 构建、签名并安装到已连接设备
.\build.ps1 -SkipInstall # 仅构建
```

构建流程：`javac(app) → d8 → aapt2 compile/link → repack → zipalign → apksigner`

三个 libxposed 构件的处理方式**不同**，这一点不能弄错：

| 构件 | 处理 | 原因 |
|---|---|---|
| `io.github.libxposed:api` | 仅加入编译 classpath，**不打包** | 运行期由框架提供；打进 APK 会与框架的类冲突 |
| `io.github.libxposed:service` | **打包进 dex** | 其中的 `XposedProvider` 必须随 APK 发布，框架才能把服务 binder 交给设置页 |
| `io.github.libxposed:interface` | **打包进 dex** | `service` 的运行期依赖，提供 `IXposedService` 等 AIDL 存根。缺失时 binder 能连上，但解析存根抛 `NoClassDefFoundError` |

`d8` 接收的是打包后的单个 jar，而不是逐个 `.class` 文件——源码以匿名内部类为主，
文件数增长后路径列表会超过 Windows 的命令行长度上限，报错却是含糊的
`The command line is too long`。

`tools/dex_defined_classes.py` 可以直接核验构件是否真的进了 dex：它会读取 dex 的
`class_defs`，报告某个类究竟是被*定义*进 dex 还是仅被引用，而不像字符串扫描那样两者不分。

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
res/drawable/                底部导航与箭头的矢量图标
res/values/strings.xml       模块名称与说明（框架从这里读取）
src/io/github/derekh_233/watranslate/
  ModuleEntry.java           XposedModule 子类，仅对 com.whatsapp 与 com.whatsapp.w4b 生效
  MessageHook.java           核心：setText 钩子与译文渲染
  InputTranslator.java       输入框翻译：草稿译文、还原与引擎选择
  ComposerPills.java         输入框上方的药丸行：注入、配色与语言选择
  ChatContext.java           识别当前会话，解析对方国家与语言
  Translators.java           8 个引擎的实现
  Engine.java                引擎枚举
  LocaleInfo.java            国家、语言、货币与当地时间
  PickerDialog.java          可搜索选择器（语言与区号）
  Prefs.java                 被 hook 侧：读框架的远程配置
  PrefsBridge.java           设置页侧：把修改推送到框架
  SettingsActivity.java      设置界面（四个分页），同时是模块入口
  Logger.java                日志
tools/
  release.ps1                构建、签名并发布到源码仓库
  fetch_libxposed.ps1        下载 libxposed api / service / interface
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

源码发布到 `DerekH-233/whatsapp-translate-xposed`。日常开发、tag 与 release 都在这个仓库。

LSPosed 管理器的「仓库」标签页索引的是模块仓库（`Xposed-Modules-Repo/<包名>`）下的 release。
`tools/release.ps1` 会在推送前检查该仓库的写权限：没有权限时只发布源码仓库并给出提示，
不会中断构建。

```powershell
.\tools\release.ps1 -Version 1.3.0 -VersionCode 7
.\tools\release.ps1 -Version 1.3.0 -VersionCode 7 -Notes .\NOTES.md
```

脚本会依次：改 `build.ps1` 里的版本号 → 构建签名 → 推送源码、tag 与 release。
运行前会检查工作区是否干净。

几个已知的坑，脚本已处理：

- **模块仓库的 release tag 必须是 `<versionCode>-<versionName>`**（例如 `7-1.3.0`），
  且必须带 apk 附件
- **空仓库不能建 release**，GitHub 会返回 `422 Repository is empty`，所以第一次要先推
  README（`tools/seed_market_repo.py`）
- **只推 tag 不建 release** 会让 GitHub 的 "Latest" 停留在上一个版本，两边看起来不一致
- 官方说明：单纯替换 release 的附件不会触发索引，必须改动 release 本身，因此每次都创建
  新的 release 而不是覆盖旧的

提交模块入口：<https://modules.lsposed.org/submission>

仓库描述需在网页上设置，它会作为模块在市场中的显示名称。协作者权限下该字段的 API
更新不可用，只能通过网页操作。

---

## 关于 Xposed API 版本

模块使用 **Modern Xposed API**（`io.github.libxposed:api` 102）。早期版本基于传统的
`de.robv.android.xposed`，该 API 已被 LSPosed 标记为废弃并会在未来移除，模块在管理器里
会被显示为 `legacy` 并提示「使用了已废弃且即将移除的功能」。

迁移涉及：入口改为 `XposedModule` 子类并由 `META-INF/xposed/` 注册；钩子改为拦截器链；
跨进程配置改用框架的远程配置；不再携带任何手写的 `de.robv` 声明。

顺带一提：**仅升级 API 版本号不等于迁移**。把 `xposedminversion` 从 93 改成 102 会让
框架按新协议去找 `META-INF/xposed/java_init.list` 里的入口，找不到就直接不加载。
