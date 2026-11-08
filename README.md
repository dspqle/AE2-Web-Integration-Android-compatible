# AE2 Android Compat

让 **AE2 Web Integration 2.0.0** 在 Android / FCL 上正常运行的 NeoForge 兼容 Mod。

A standalone NeoForge compatibility mod that makes **AE2 Web Integration 2.0.0** work on Android (FCL / PojavLauncher) without modifying a single byte of the original jar.

---

## 问题 / The Problem

AE2 Web Integration 依赖 Xerial `sqlite-jdbc`，它在 Android 上无法加载原生库：

- Android 使用 **bionic libc**，`sqlite-jdbc` 内嵌的 `.so` 多数变体不适配
- `/storage/emulated/0` 为 **noexec** 挂载，释放出的 `.so` 无法 `dlopen`
- 结果：`NativeLibraryNotFoundException`，游戏启动崩溃

AE2 Web Integration depends on Xerial `sqlite-jdbc`, which fails to load its native library on Android:

- Android uses **bionic libc**; most `.so` variants bundled in `sqlite-jdbc` are not compatible
- `/storage/emulated/0` is mounted **noexec**, so an extracted `.so` cannot be `dlopen`-ed
- Result: `NativeLibraryNotFoundException`, crash on startup

## 为什么不用 Mixin / ITransformer

`sqlite-jdbc` 被 **JarJar** 提升为 **PLUGIN 层模块**，由该层独立的 `ModuleClassLoader` 加载；而 `ClassTransformer` 只挂在 **GAME 层**，**结构上永远不会生效**。

验证方式：崩溃日志中 `org.sqlite.*` 栈帧的 audit-trail 标记为空 `{}`，而同日志中 `minecraft.*`、`ae2webintegration.*` 均为 `{re:classloading}`；85 条 `TRANSFORMER` 记录中无一来自 `org.xerial.sqlitejdbc`。

Because `sqlite-jdbc` is promoted by **JarJar** into a **PLUGIN-layer module** loaded by that layer's own `ModuleClassLoader`, while `ClassTransformer` is only attached to the **GAME-layer** `TransformingClassLoader`. An `ITransformer` can therefore **never** take effect on those classes.

## 原理 / How It Works

`SQLiteJDBCLoader.loadSQLiteNativeLibrary()` 会先读系统属性 `org.sqlite.lib.path`，非空则直接 `System.load(绝对路径)` 返回。

本 Mod 据此做三件事：

1. 自带一个可在 bionic 上加载的 `libsqlitejdbc.so`（aarch64）
2. 极早期释放到**可写且可执行**的目录（优先 `java.io.tmpdir`）
3. `System.setProperty("org.sqlite.lib.path", <目录>)`

走绝对路径，**不受 `java.library.path` 约束，无需修改任何 JVM 参数**。原 Mod jar 一个字节不改。

双入口（`ITransformationService` + `ICoreMod`）互为保险，初始化幂等（`synchronized` + `volatile boolean done`）。

`SQLiteJDBCLoader.loadSQLiteNativeLibrary()` first reads the system property `org.sqlite.lib.path`. If it is non-empty and the file exists, it calls `System.load(<absolute path>)` and returns immediately.

This mod exploits that early-return branch:

1. Ship a `libsqlitejdbc.so` that loads under bionic (aarch64)
2. Extract it, very early, into a **writable and executable** directory (`java.io.tmpdir` preferred)
3. Call `System.setProperty("org.sqlite.lib.path", <dir>)`

Because loading goes through an absolute path, it is **not constrained by `java.library.path`** and **requires no JVM arguments**. The original mod jar is left untouched.

Two entry points (`ITransformationService` + `ICoreMod`) act as mutual fallbacks; initialization is idempotent.

### 释放目录优先级 / Extraction Directory Priority

1. `java.io.tmpdir` — app-private under FCL/Android, writable & executable (**actually used**)
2. `user.home`
3. `<gamedir>/.ae2compat-native/`

`/storage/emulated/0` is excluded because it is `noexec`.

## 已验证环境 / Verified Environment

| 项目 / Item | 版本 / Version |
|---|---|
| 设备 / Device | Xiaomi 23127PN0CC (SM8650, arm64) |
| 系统 / OS | Android 16 (SDK 36) |
| 启动器 / Launcher | FCL 1.3.4.5 |
| Java | 21.0.1 |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.252 |
| 渲染器 / Renderer | MobileGlues 2.0.0 |
| 目标 / Target | AE2 Web Integration 2.0.0 |

## 兼容性 / Compatibility

- 需要 **arm64 Android + bionic libc**
- 仅支持 **NeoForge**
- 原 Mod jar 无需任何修改
- 其他架构：替换 `res/native/libsqlitejdbc.so` 后重新构建即可，Java 代码无需改动

- Requires **arm64 Android + bionic libc**
- **NeoForge** only
- Original mod jar requires no modification
- Other architectures: replace `res/native/libsqlitejdbc.so` and rebuild; no Java changes needed

## 构建 / Build

```bash
# 需要 JDK 21 / Requires JDK 21
bash build.sh
# 产物 / Output: ae2androidcompat-1.0.0.jar
```

`lib/` 与 `res/` 均已包含，无需联网。

`lib/` and `res/` are included; no network access needed.

## 目录结构 / Layout

```
.
+-- build.sh
+-- lib/                    # 编译期依赖 / compile-time deps
|   +-- modlauncher-11.0.5.jar
|   +-- loader-4.0.44.jar
+-- res/
|   +-- META-INF/neoforge.mods.toml
|   +-- META-INF/services/
|   +-- native/libsqlitejdbc.so
+-- src/ae2compat/
    +-- AE2AndroidCompatTransformationService.java
    +-- AE2AndroidCompatCoreMod.java
    +-- NativeLib.java
    +-- Log.java
```

## 原生库来源 / Native Library Origin

`res/native/libsqlitejdbc.so`（1,729,520 字节，MD5 `bd64cd4336b22200c8d57351a5da03bc`）提取自 Xerial `sqlite-jdbc` 3.53.4.0 官方发行版中为 `Linux/aarch64` 预编译的变体，经 AE2 Web Integration 的 JarJar 内嵌。**未做任何修改，逐字节一致。**

`res/native/libsqlitejdbc.so` (1,729,520 bytes, MD5 `bd64cd4336b22200c8d57351a5da03bc`) is extracted, byte-for-byte unmodified, from the official Xerial `sqlite-jdbc` 3.53.4.0 release (`Linux/aarch64` prebuilt variant), as bundled by AE2 Web Integration via JarJar.

## 已知限制 / Known Limitations

- 依赖 sqlite-jdbc 对 `org.sqlite.lib.path` 的处理逻辑，上游若改变该行为需相应调整
- 属**环境适配层**，非功能 Mod，仅解决原生库加载

- Depends on sqlite-jdbc honoring `org.sqlite.lib.path`; upstream changes to that behavior would require adaptation
- An **environment compatibility layer**, not a feature mod

## 许可 / License

MIT

> 分发的 `libsqlitejdbc.so` 源自 Xerial sqlite-jdbc（Apache-2.0）。
> The distributed `libsqlitejdbc.so` originates from Xerial sqlite-jdbc (Apache-2.0).
