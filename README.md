# 网易iOS登录 · NetEase Android Login iOS

在 Android 设备上，用同一个网易账号登录网易官服游戏里的 **iOS 角色**。

项目是**一个 APK、两种身份**：

| 身份 | 作用 | 需要 root / XP 框架 |
| --- | --- | --- |
| **iOS 登录助手**（普通 App） | 借游戏自带的网页桥，写入一份"iOS 客户端"的 MPay 会话种子，让后续登录落到 iOS 角色 | 不需要 |
| **Xposed 模块** | 把游戏伪装成 MuMu 模拟器，让官服 SDK 放出**平台选择**（Android / iOS / 鸿蒙 / PC） | 需要 |

两条路径互相独立：`iOS 登录` 走本地回环桥，`模块` 走 Xposed 注入。你可以只用其中一个。

> 仅适配本人合法持有的账号，不修改游戏包体、不拦截账号密码与登录凭据。详见文末免责声明。

---

## 目录

- [功能总览](#功能总览)
- [一、iOS 登录（无需框架）](#一ios-登录无需框架)
- [二、Xposed 模块](#二xposed-模块)
- [三、网易应用过滤与目标选择](#三网易应用过滤与目标选择)
- [四、二合一架构是怎么做的](#四二合一架构是怎么做的)
- [五、兼容性矩阵](#五兼容性矩阵)
- [六、安装与使用](#六安装与使用)
- [七、构建](#七构建)
- [八、项目结构](#八项目结构)
- [九、技术细节](#九技术细节)
- [十、已知限制](#十已知限制)
- [致谢与许可](#致谢与许可)

---

## 功能总览

### iOS 登录（App 侧）

- **任意网易官服游戏**：不写死游戏清单，从本机已安装应用里选，游戏代号（`ma75`、`g37` 等）运行时从 `gameContextInfo` 自动读取。
- **一键识别并准备**：在游戏里正常登录后，点一次按钮完成"读账号 → 写种子 → 加载会话"，不需要来回切窗口。
- **两种写入通道**：标准缓存（`<包名>.widget_file_provider`）与 SAF 共享目录（`Android/media/<包名>`，给阴阳师这类没有缓存 Provider 的游戏）。
- **网页桥活动自动探测**：不同 UniSDK 大版本的桥活动类名不一致，默认 `gmbridge5` 不存在时自动在包内挑选可用的 `*GMWebviewActivity`。
- **不改包、不抓包、不需要证书**：全程只用游戏自己的导出组件与本地回环。

### Xposed 模块（注入侧）

- **MuMu 渠道伪装**：`netease.mumu2_free_dev`，覆盖 4 个取渠道的出口。
- **MuMu 设备标识**：`SystemProperties.get("ro.build.id")` → `V417IR`。
- **平台选择**：`select_platform` / `select_platforms` 三端开关，光·遇为「自选三端」，第五人格为「PC」。
- **容器适配**：多 ClassLoader 观察、延迟注入、`Instrumentation.newActivity` 拿真实 loader。
- **诊断与自保**：`WYLoginIOS` 日志、四路落盘、hook 全包 try/catch、启动检测。

---

## 一、iOS 登录（无需框架）

### 原理

网易的 MPay / UniSDK 允许客户端用一份「会话种子」文件告诉 SDK"当前是哪个平台的客户端"。种子里的 `pc_ext_info.src_client_type` 就是平台标识（`2` = iOS）。这个能力本来是给"同一账号多端互踢恢复"用的，因此**不需要 root、不需要改包**，只要能把文件写进游戏的缓存目录、再让游戏自己加载它。

完整链路：

```
App 在本机 127.0.0.1 起一个一次性 HTTP 服务（随机端口 + 24 字节 nonce 路径）
        │
        ├─ 拉起目标游戏的 GMWebviewActivity（extra: GM_WEBVIEW_URL）
        │      └─ 游戏用自己导出的网页容器打开这个地址
        │
        ├─ 页面 JS 调 gameContextInfo，拿到当前登录账号 UID 与 game_id
        │
        ├─ App 生成种子：token 为空、need_mask=true、src_client_type=2(iOS)
        │      写入 <包名>.widget_file_provider 缓存，或 SAF 的 Android/media/<包名>
        │
        └─ 回包给页面，JS 调 execute_extend_func → loadMpaySession(path)
               → SDK 吃掉种子，此后同一账号重登即按 iOS 客户端处理
```

安全边界：

- 只监听 `127.0.0.1`，随机端口；路径带 24 字节随机 nonce。
- 校验 `Host` 与 `Origin`，不匹配直接 403。
- 45 秒未完成自动销毁，完成后立即关闭，种子文件定时清理。
- 种子 `token` 为空串——**从不接触密码、验证码、真实登录 token**。

### 种子内容

```json
{"user":{"id":"<UID>","token":"","need_mask":true,"login_type":1,
         "login_channel":"netease",
         "pc_ext_info":{"src_client_type":2,"is_remember":true,"is_from_ticket_login":true}}}
```

`is_remember` 是兼容部分游戏（如决战平安京）渠道识别的必要字段，缺了会导致切换账号后仍退回安卓角色。

### 使用步骤

1. 打开目标游戏，用要切到 iOS 的网易账号**正常登录**，停在主界面。
2. 回到本 App，选这个游戏（默认只显示网易应用，可关掉筛选）。
3. 一般游戏用「标准缓存」，阴阳师一类选「共享目录方式（SAF）」并授权一次 `Android/media`。
4. 点「一键准备 iOS 登录」。
5. 回游戏点「切换账号」，用**同一个账号**重新登录 → 进入 iOS 角色。

> **防卡死**：如果准备前你已经点开了「切换账号」弹窗，返回后**不要直接点旧列表条目**（旧会话已失效，会一直转圈）。先点右上角 `×` 关掉，再重新打开列表。

### 常见问题

**Q：切到别的账号后，原来那个 iOS 号又变安卓了？**
不用重开 App，直接在游戏里点「切换账号」，把那个账号再点一次登录即可。

**Q：怎么彻底变回安卓角色？**
在游戏「切换账号」列表里删掉该账号记录，重新输入账号正常登录。

**Q：提示「SDK 已读取文件，但未接受账号准备格式」？**
说明这条游戏还没适配种子格式（`sdk_invalid_session`），需要逐个游戏验证。

---

## 二、Xposed 模块

模块做的事只有一句：**让游戏以为自己在 MuMu 模拟器里跑**。MuMu 渠道的官服 SDK 会放出平台选择与扫码入口，这才有了"选 iOS / 鸿蒙 / PC"的机会。

### 1. 渠道伪装

渠道固定为 `netease.mumu2_free_dev`，覆盖四个出口（不同游戏版本、不同 SDK 走哪一个不一样，全部挂上才稳）：

| 出口 | 方法 |
| --- | --- |
| 反射查找层 | `SdkNetease.getCustomCpsChannel(Object[])` |
| APK 签名层 | `ApkChanneling.getChannel(File)` |
| 模块层 | `AppChannel.getChannel(Context)` / `getAppChannel(...)` |
| 设备信息层 | `DeviceUtils.appChannel`（静态字段种子） |

设计上不是"逐个 hook 返回值"，而是在**反射查找阶段返回模块内的代理方法**（`getDeclaredMethod` / `getMethod` 命中即替换），这样即使游戏把方法改名或换类，只要还走反射查找就仍然有效。

### 2. 设备标识

```java
SystemProperties.get("ro.build.id", def) → "V417IR"   // MuMu 的 build id
```

`V417IR` 之外 `W528JS` 也可用，模块固定用前者。

### 3. 平台选择

| 游戏 | 策略 | 说明 |
| --- | --- | --- |
| 光·遇 `com.netease.sky` | 自选三端 | **不 hook** `SdkNetease.getPlatform()`，让 SDK 按渠道放出选择界面 |
| 第五人格 `com.netease.dwrg` | 固定 PC | hook `getPlatform()` 强制返回 `pc` |

为什么光遇不能强制平台：`SdkNetease.getPlatform()` 的返回值就是"玩家选了哪一端"，强制它会**顶掉选择界面**，直接进游戏。所以光遇只伪装渠道，让 SDK 自己弹三端；第五人格则是需要固定成 PC。

同时兜住登录响应里的三端开关：

```java
JSONObject.optBoolean("select_platform")  → true
JSONObject.optJSONArray("select_platforms") → [1, 2, 8]   // 1=Android 2=iOS 8=模拟器
```

`select_platforms` 会在两个地方出现（登录响应 / mpay 请求 `com.netease.mpay.server.request.k`），谁先被调用就改谁，两处都挂。

### 4. 容器适配（重点）

在 Dual Space、元萝卜这类虚拟空间里，游戏不是独立进程，而是跑在宿主进程内、由沙盒自己的 `ClassLoader` 加载。这带来三个坑，模块逐个处理：

| 坑 | 处理 |
| --- | --- |
| hook 到的是宿主的同名类 | `PlatformHook.DONE` 用 `IdentityHashMap` 按 **Class 对象身份**去重，不是按类名 |
| 注入时机太早，真实 dex 还没解密完 | `ClassWatcher` 250ms 起轮询 `findLoadedClass`，最长 6 分钟，支持多 ClassLoader |
| `Application.onCreate` 在容器里不回调 | 就绪点改到 `Instrumentation.newActivity`，并用 1 秒轮询兜底 |

### 5. 诊断

容器内 `/sdcard` 写入会被重定向到沙盒，外部看不到，所以**日志一律看 logcat**：

```bash
adb logcat -s WYLoginIOS
```

模块会打：

- 门禁判定（`注入 pkg=... proc=...`）
- 每个 hook 出口的挂载结果与首次命中
- `appChannel: unknown -> mumu` 这类状态迁移
- 自检行：`机型=OK 三端=OK 渠道=OK 平台=自选 hits=...`

`Diag` 还会在 `/sdcard/Android/media/<包名>/` 留一份（能写就写，写不进不影响功能）。

### 6. 诊断开关

`xposed/HookFlags.java` 里有 5 个编译期开关，排查时逐段关掉定位问题：

```java
DISABLE_ALL_HOOKS      // 全关，只保留日志
DISABLE_SYSTEM_HOOK    // 关设备标识
DISABLE_JSON_HOOK      // 关三端开关
DISABLE_REFLECT_HOOK   // 关反射代理
FORCE_PLATFORM         // 强制平台
```

---

## 三、网易应用过滤与目标选择

这是两条路径**故意做成不一样**的地方，也是这个项目最容易误解的点。

### App 侧（iOS 登录）：尽量宽

- 枚举本机**所有带启动图标的应用**，不只是网易游戏。
- 顶部有「仅网易应用」开关，默认开启；判定规则是包名等于 `com.netease` 或以 `com.netease.` 开头。
- 列表里网易应用会打「网易」标签，其余应用排在后面。
- 关掉开关即可看到全部应用——有些渠道包、测试包不一定符合前缀规则。

为什么这么宽：iOS 登录**不需要针对游戏改代码**，能力来自游戏自带的网页桥。能不能用只取决于两个运行时条件：

1. 客户端是网易官服（渠道服账号体系不同，不适用）
2. 客户端导出了 `com.netease.unisdk.gmbridge5.activity.GMWebviewActivity`

所以这里不做白名单，让用户自己试——试不通会明确报错（`sdk_read_failed` / `bridge_missing` / `sdk_rejected`）。

### XP 侧：必须严

`core/GameProfile` 是**硬白名单**，目前只有两款：

```java
SKY  = new GameProfile("com.netease.sky",  "光·遇",   true, true);
IDV  = new GameProfile("com.netease.dwrg", "第五人格", true, true);
```

双层门禁：

1. `XposedEntry.looksLikeTarget()`：包名/进程名必须精确等于 `com.netease.sky` / `com.netease.dwrg`，或命中容器前缀；**不再用** `startsWith("com.netease.")`——那会把所有网易应用都注进去。
2. 容器前缀兜底：`com.xunijun.app`（Dual Space）、`top.bienvenido.`（元萝卜）、`com.katana` 等，用于"游戏跑在宿主进程里"的场景。

为什么这么严：hook 是**改游戏内部行为**，渠道值、平台值、支付 JSON 的字段名每个游戏都不一样。注错了轻则无效，重则闪退。所以新增游戏必须**逐游戏实测**后再 `register` 一行，界面上的「适配范围」卡片也会同步显示。

---

## 四、二合一架构是怎么做的

一个 APK 同时是"可安装的普通 App"和"Xposed 模块"，靠的是三样东西：

1. **`assets/xposed_init`** —— 内容只有一行 `cn.ratwl.androidloginios.xposed.XposedEntry`，Xposed 框架据此找到入口。
2. **`AndroidManifest.xml` 里的 `xposedmodule` meta-data** —— 声明模块身份、描述、最低 API 与作用域数组。
3. **入口类实现 `IXposedHookLoadPackage`** —— 但只有门禁通过的进程才会真正往下走。

两条路径的运行环境完全不同：

```
点桌面图标 → MainActivity（普通 App 进程，包名 cn.ratwl.androidloginios）
               └─ bridge/ 本地回环 + 种子写入

游戏启动    → 框架加载模块 dex → XposedEntry.handleLoadPackage
               └─ 门禁通过 → xposed/ 渠道与平台 hook
```

互不干扰的原因：

- `bridge/` 只被 `MainActivity` 调用，跑在模块自己的进程里。
- `xposed/` 只在门禁通过的游戏进程里执行，且所有 hook 体都包在 `HookGuard.safe()` 里，单个出口失败不影响其它出口，更不会把游戏带崩。

---

## 五、兼容性矩阵

| 环境 | iOS 登录 | Xposed 模块 | 备注 |
| --- | :-: | :-: | --- |
| 真机，无 root | ✅ | ❌ | App 侧照常可用 |
| Magisk + LSPosed | ✅ | ✅ | 勾选两款游戏后强制停止再启动 |
| LSPatch 内嵌 | ✅ | ✅ | 模块 APK 作为内嵌模块打进游戏 |
| Dual Space 1.94 | ✅ | ✅ | 已实测：光遇出三端，日志全绿 |
| 元萝卜 1.0.14 + Android 15 及以下 | ✅ | 未验证 | 官方适配范围是 Android 12–15 |
| 元萝卜 1.0.14 + Android 16/17 | ✅ | ❌ | 见下 |

**关于元萝卜在 Android 16/17 上不生效**：现象是模块代码确实在跑（logcat 有输出），但所有 hook 一次都不触发，连 `android.app.Application.onCreate` 这种框架类都不进，`ro.build.id` 也没被改写。根因不在模块——元萝卜内置的 Pine 版引擎按旧符号 `art::hiddenapi::ShouldDenyAccessToMember` 解析，而 Android 16/17 的 ART 给该函数加了 `Handle` 参数，符号签名变化后解析失败，整个引擎失效。同一个 APK 在 Dual Space 上完全正常，可作为对照。

---

## 六、安装与使用

1. 安装 APK（既是 App 也是模块）。
2. **只用 iOS 登录**：直接打开 App，按[上文步骤](#使用步骤)操作，不需要任何框架。
3. **要用平台选择**：
   - LSPosed：启用模块 → 勾选《光·遇》/《第五人格》→ 强制停止游戏 → 重新启动。
   - Dual Space：把模块和游戏都装进空间 → 长按游戏 →「管理插件」→ 启用本模块 → 作用域选「当前应用」。
4. 进游戏后：光遇点账号图标 → 切换账号，能看到 iOS / Android / 鸿蒙三端；第五人格直接以 PC 端登录。

模块配置（渠道 / 设备标识开关）存在模块自己的 SharedPreferences 里，改完要重启游戏进程才生效。

---

## 七、构建

| 项 | 版本 |
| --- | --- |
| JDK | 17+（本项目用 26 验证） |
| Gradle Wrapper | 9.6.0 |
| AGP | 9.x |
| compileSdk / targetSdk | 36 |
| minSdk | 26（Android 8.0） |

```bash
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 发布包
```

未配置签名时 release 回退 debug 签名，产物可直接安装。要正式签名，注入四个环境变量：

```bash
RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
```

> **不要打开 R8**。容器会把模块 dex 加载进**宿主** ClassLoader，混淆后的单字母类名（如 `HookConfig` → `q`）会和容器自己的类撞名，运行时直接 `NoSuchMethodError`。`minifyEnabled false` 是刻意保留的。

图标由 `tools/IconGen.java` 生成（JDK `java.awt`，无第三方依赖）：

```bash
javac -encoding UTF-8 -d . tools/IconGen.java
java -Djava.awt.headless=true -cp . IconGen app/src/main/res/drawable-xxxhdpi/ic_launcher_fg.png 116
```

---

## 八、项目结构

```text
app/src/main/
├── assets/xposed_init                     # Xposed 入口声明（一行）
├── java/cn/ratwl/androidloginios/
│   ├── MainActivity / ModuleInfo          # 界面入口、版本与链接常量
│   ├── bridge/                            # iOS 登录（来自 netease-ios 的 Java 移植）
│   │   ├── MpayBridgeSession.java         # 回环服务、种子构造、状态机
│   │   ├── BridgeProtocol / GameSpec      # 协议常量与目标游戏规格
│   │   ├── GameTarget / InstalledApp      # 应用枚举、图标缓存、桥活动探测
│   │   ├── SeedStorage                    # 标准缓存 / SAF 双通道写入
│   │   ├── BridgePage / HttpRequest       # 桥接页与最小 HTTP 解析
│   │   └── BridgePrefs                    # 会话状态持久化
│   ├── core/                              # 模块配置与适配白名单
│   │   ├── GameProfile                    # 硬白名单（光遇 / 第五）
│   │   ├── ModuleConfig / HookConfig      # 开关与运行时配置
│   │   └── CloneHosts / Xlog
│   ├── xposed/                            # 注入侧
│   │   ├── XposedEntry                    # 门禁 + 入口
│   │   ├── PlatformHook                   # 渠道 4 出口 + 平台策略
│   │   ├── SystemHook                     # ro.build.id
│   │   ├── JsonHook                       # select_platform(s)
│   │   ├── ChannelHook                    # 渠道（兼容旧路径）
│   │   ├── ClassWatcher                   # 多 ClassLoader 观察
│   │   ├── AppLifecycle                   # 注入时机与就绪点
│   │   ├── Diag / HookLogger              # 日志与自检
│   │   ├── HookGuard / CrashGuard         # 异常隔离与崩溃防护
│   │   ├── BootGuard                      # 启动检测
│   │   └── HookFlags                      # 5 个编译期诊断开关
│   └── ui/                                # 纯平台 View 实现，无 Material 依赖
└── res/                                   # 自适应图标（网易红 + 白字）
```

---

## 九、技术细节

### 为什么 UI 不用 AppCompat / Compose

模块会被塞进游戏进程，任何多余的库都会进入宿主 ClassLoader，撞名风险随体积线性上升。所以整界面是平台原生 `View` + 自绘 `UiKit`，APK 只有 80KB 左右。

### 门禁为什么不用 `startsWith`

`com.netease.` 前缀下还有云音乐、新闻、邮箱、UU 加速器等等。全注进去的后果是这些应用也被改 `ro.build.id`、也会被塞渠道值——既无意义又容易崩。精确包名 + 容器前缀是唯一稳妥的判定。

### 平台策略为什么不统一

渠道伪装是"让 SDK 愿意提供选择"，平台 hook 是"替用户做选择"。前者对所有游戏一致，后者必须按游戏特性定制：

- 光遇的 `getPlatform()` 直接对应登录界面里的选项，hook 它等于取消选择。
- 第五人格需要的是确定性地进 PC 端，所以强制 `pc`。

### 种子写入为什么需要两条通道

`widget_file_provider` 是 UniSDK 给游戏开放的缓存 Provider，不是每个游戏都有。没有的话只能走 SAF 让用户授权 `Android/media/<包名>` 目录，再由 App 直接写文件。两条通道在 `SeedStorage` 里统一成同一个写入接口，失败时分别返回 `storage_failed` / `directory_required`。

---

## 十、已知限制

- **仅网易官服**：B 站服、渠道服账号体系不同，不适用。
- **依赖游戏导出网页桥**：未导出 `GMWebviewActivity` 的客户端无法使用 iOS 登录。
- **XP 注入只适配两款游戏**：光遇、第五人格。其它网易应用不会注入（这是刻意设计）。
- **元萝卜 + Android 16/17 不可用**：容器引擎与新版 ART 不兼容，与本模块无关。
- **种子格式需逐游戏验证**：不同游戏对 `pc_ext_info` 的接受程度不同，报 `sdk_invalid_session` 即表示尚未适配。

---

## 致谢与许可

本项目的 **iOS 登录部分（`bridge/` 包）是 [Naptila/netease-ios](https://github.com/Naptila/netease-ios) 的 Java 移植**，协议常量、种子结构、回环桥与状态机均来自该项目，并按上游 v1.2 对齐（含 `is_remember` 字段）。感谢原作者的开源工作。

Xposed 模块部分的思路参考了社区公开的 MuMu 渠道伪装方案（如 [Naptila/netease-mumu](https://github.com/Naptila/netease-mumu)），以及早期版本 SkyXP 1.19 的 hook 组织方式；本项目的实现已按容器场景重写（多 ClassLoader、身份去重、异常隔离、诊断）。

由于 `netease-ios` 采用 **GPL-3.0**，本项目作为其衍生作品同样以 **GPL-3.0** 发布，见 [LICENSE](LICENSE)。任何基于本项目的修改版本在分发时也必须以 GPL-3.0 开源并保留原始版权声明。

---

## 免责声明

本项目仅供个人跨平台游玩**本人合法持有**的游戏账号及技术学习交流使用。不修改游戏客户端文件、不拦截或收集任何账号密码与登录凭据。使用前请自行确认符合对应游戏的用户服务协议；因使用本工具产生的一切后果由使用者自行承担。
