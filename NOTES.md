## 修好了：设置无法送达

**症状**：改了「译文颜色」和「斜体」，WhatsApp 里没有任何变化。

**原因**：模块设置页要把配置通过 LSPosed 的服务通道送给 WhatsApp 进程，而这个通道一直没建立起来。服务 binder 其实送到了，但下一行就崩了：

```
XposedProvider: binder received: android.os.BinderProxy@...
XposedServiceHelper: java.lang.NoClassDefFoundError:
    Failed resolution of: Lio/github/libxposed/service/IXposedService$Stub;
```

`XposedService`、`XposedServiceHelper` 和 AIDL 生成的类**分属不同的构件**。
`service` 把 `io.github.libxposed:interface` 声明为运行时依赖，用 Gradle 构建时会自动带上，
所以官方模板不会踩到；本项目不用 Gradle，只取了 `api` 和 `service`，于是 `IXposedService`
在 dex 里只是「被引用」而从未「被定义」，每次握手都在第一行抛异常。

**顺带修掉的两个问题**

- 补上 `interface.jar` 后又暴露出 lambda 没被降级（`--no-desugaring`），运行时报
  `NoSuchMethodError: LambdaMetafactory.metafactory`。现已开启 desugaring。
- 配置推送原来是**只增不减**的，本地删掉的键会永远留在框架里，导致钩子侧继续执行用户
  已经看不到的设置。现在改为整体镜像。

**本版影响范围**：所有设置项都受影响，不只颜色和斜体——这两项只是因为默认值看起来跟
「没生效」一样才最容易被发现。

真机验证（从 WhatsApp 进程回读）：

```
pushed 3 setting(s) to the framework
rendered colour=ff8a8a8a italic=true scale=0.85 -> ...
```

---

APK 见下方附件。

SHA-256 见发布页。
