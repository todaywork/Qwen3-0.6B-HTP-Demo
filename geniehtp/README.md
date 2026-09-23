# GenieHtpEngine — Qwen3 0.6B HTP 推理 SDK（AAR）

基于 Qualcomm Genie / QNN HTP 后端的端侧大模型推理库，以 AAR 形式提供给上层 App 集成。
当前模型为 **Qwen3-0.6B（W4A16 量化）**，推理全程在设备 NPU（HTP/DSP）上执行。

上层 App 只需面对一个门面类 `GenieHtpEngine` 和一个配置类 `EngineConfig`，
无需关心 JNI、QNN so 加载、DSP skel 提取等底层细节。

---

## 1. 渠道矩阵

AAR 按 **HTP 架构 × QNN 版本** 分为 4 个渠道，**必须**与目标设备的 SoC 架构和模型编译版本匹配：

| 渠道 | HTP 架构 | QNN 版本 | AAR 文件名 |
|---|---|---|---|
| v68 + qnn234 | V68（如 SA8295P） | QNN 2.34 | `geniehtp-v68-qnn234-release.aar` |
| v68 + qnn246 | V68 | QNN 2.46 | `geniehtp-v68-qnn246-release.aar` |
| v73 + qnn234 | V73（如 SA8775P / 荣耀平台） | QNN 2.34 | `geniehtp-v73-qnn234-release.aar` |
| v73 + qnn246 | V73 | QNN 2.46 | `geniehtp-v73-qnn246-release.aar` |

> 渠道不匹配会在 `prepare()` / 首次 `query()` 时直接失败（HTP 初始化错误）。

## 2. 构建 AAR

在本仓库根目录执行（需要 JDK 17/21、Android SDK、CMake 3.22.1+、NDK）：

```bash
# 四个渠道分别构建
gradlew :geniehtp:assembleV68Qnn234Release
gradlew :geniehtp:assembleV73Qnn234Release
gradlew :geniehtp:assembleV68Qnn246Release
gradlew :geniehtp:assembleV73Qnn246Release
```

产物输出在：

```
geniehtp/build/outputs/aar/geniehtp-v<68|73>-qnn<234|246>-release.aar
```

## 3. App 集成步骤

### 3.1 放入 AAR

将对应渠道的 AAR 拷贝到 App 模块的 `libs/` 目录：

```
app/
└── libs/
    └── geniehtp-v73-qnn234-release.aar
```

### 3.2 配置 build.gradle

```groovy
android {
    compileSdk 35

    defaultConfig {
        minSdk 26          // 库最低支持 26
        ndk {
            abiFilters "arm64-v8a"   // 库仅包含 arm64-v8a
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging false   // 建议：避免 so 解压到文件系统的兼容问题
        }
    }
}

dependencies {
    implementation files('libs/geniehtp-v73-qnn234-release.aar')
}
```

### 3.3 混淆规则（release 包必须加）

JNI 方法按类名/方法名绑定，混淆会导致 native 调用失败：

```proguard
-keep class com.qairt.qwen3htp.GenieNative { *; }
```

### 3.4 权限

库本身 **不需要任何 Android 权限**（Manifest 为空）。

## 4. 模型文件准备

模型不打包进 AAR/Apk，需在首次运行前推送到设备：

```bash
adb push <模型目录>/. /data/local/tmp/genie_qwen3_quality/
```

模型根目录解析顺序（`EngineConfig.modelRoot` 未显式指定时）：

1. **应用私有目录** `files/models/<渠道名>/`（存在则优先，适配 SELinux Enforcing / 多用户场景）
2. **外部推送目录** `/data/local/tmp/genie_qwen3_quality`（兜底，即上面的 adb push 目标）

> 模型同样分 v68 / v73 架构，必须与 AAR 渠道一致。

## 5. 快速开始

```java
import com.qairt.qwen3htp.EngineConfig;
import com.qairt.qwen3htp.GenieHtpEngine;
import com.qairt.qwen3htp.RunMetrics;

// 建议在单一后台线程串行调用 query / resetDialog（prepare/close 线程安全且幂等）
GenieHtpEngine engine = new GenieHtpEngine(context,
        EngineConfig.builder()
                .modelRoot("/data/local/tmp/genie_qwen3_quality")  // 可省略，走默认解析
                .contextSize(512)
                .maxTokens(256)
                .maxOutputTokens(128)
                .build());

engine.prepare();                       // 加载 native 库、提取 DSP 运行时（幂等，可提前做）
String answer = engine.query("你好");     // 阻塞式推理，返回完整文本
RunMetrics metrics = engine.lastMetrics();  // 上次 query 的性能数据
engine.resetDialog();                   // 开启全新对话（清空 KV cache）
engine.close();                         // 释放会话，AutoCloseable 可配合 try-with-resources
```

流式输出：

```java
String full = engine.queryStreaming("你好", token -> {
    // 注意：回调在推理线程，刷新 UI 请自行 post 到主线程
    runOnUiThread(() -> textView.append(token));
});
```

## 6. API 说明

### GenieHtpEngine（门面类）

| 方法 | 说明 |
|---|---|
| `prepare()` | 加载 native 库并提取 DSP 运行时；幂等。**首次 query 时才真正创建会话** |
| `isPrepared()` | 是否已 prepare |
| `query(String prompt)` | 等价于 `query(prompt, true)` |
| `query(String prompt, boolean reuseKvCache)` | 阻塞推理。`reuseKvCache=false` 表示全新对话；`true` 表示 REWIND 复用上一轮 KV cache |
| `queryStreaming(String, StreamCallback)` | 流式推理，`onToken` 逐 token 回调，返回完整文本 |
| `resetDialog()` | 重置对话，下一条 query 从全新上下文开始 |
| `lastMetrics()` | 上次 query 的 `RunMetrics`（未 prepare 时返回 null） |
| `lastInputTokenIds()` | 上次输入的 token id 串（调试用） |
| `version()` | Genie 版本串 |
| `close()` | 释放会话；AutoCloseable |
| `htpArch()` / `target()` / `channel()` | 本 AAR 的静态渠道信息（68/73、平台标识、渠道名） |
| `compileTimeUseMmap()` | 本 AAR 编译期写死的 use-mmap 开关值 |
| `defaultModelRoot(Context)` | 默认模型根目录解析逻辑（见第 4 节） |

**线程约定**：请在**单一后台线程**串行调用 `query` / `queryStreaming` / `resetDialog`；
`prepare` / `close` 幂等且线程安全。不要在主线程调用 `query`。

### EngineConfig（Builder，全部可选）

| 参数 | 默认值 | 约束 / 说明 |
|---|---|---|
| `modelRoot(String)` | 自动解析 | 见第 4 节 |
| `contextSize(int)` | 512 | > 0，需与模型编译的 ctx 一致 |
| `maxTokens(int)` | 256 | [1, contextSize] |
| `maxOutputTokens(int)` | 128 | [1, maxTokens] |
| `threadCount(int)` | 4 | CPU 侧线程数 |
| `greedy(boolean)` | true | 贪心解码 |
| `topK(int)` / `topP(float)` | 40 / 0.95 | 采样参数（greedy 时无效） |
| `temperature(float)` | 0.0 | 采样温度 |
| `presencePenalty(float)` | 0.0 | 存在惩罚 |
| `useMmap(Boolean)` | null | null = 用 AAR 编译期开关（按渠道写死）；显式 true/false = 运行期覆盖 |

### RunMetrics（性能数据）

| 字段 | 说明 |
|---|---|
| `sessionCreateMs` | 会话创建耗时 |
| `queryMs` / `totalMs` | 单条推理耗时 / 端到端耗时（含前后处理） |
| `promptChars` / `outputChars` / `outputCharsPerSecond` | 字符级统计 |
| `maxOutputTokens` | 本次输出 token 上限 |
| `profile`（`ProfileMetrics`） | Genie SDK 原始 profile：promptTokens、generatedTokens、timeToFirstTokenUs、durationUs、promptTokensPerSecond、generatedTokensPerSecond 等 |

## 7. 注意事项

1. **渠道匹配**：AAR 渠道（v68/v73）、模型架构、设备 SoC 三者必须一致，否则 HTP 初始化失败。
2. **AAR 已内置全部 native 运行时**：QNN/Genie so（jni）+ DSP skel（assets），`prepare()` 会把当前渠道的 skel 提取到应用私有目录。集成方**不要**再往自己的 `jniLibs/`、`assets/` 里放任何 QNN so，避免渠道文件互相覆盖。
3. **use-mmap**：各渠道编译期开关写死在 `geniehtp/build.gradle` 的 `productFlavors` 中；一般无需运行期覆盖，仅特殊 BSP（如 fastrpc 驱动不支持新映射命令的车机）才需要 `useMmap(false)` 显式关闭。
4. **设备要求**：arm64-v8a、Android 8.0（API 26）+；设备需允许应用访问 CDSP（零售手机/车机工程模式均可，部分封锁 DSP 的设备无法使用）。
5. **模型不随包分发**：Release 发布流程中记得把对应渠道的模型目录推送步骤纳入产线/测试 SOP。

## 8. 完整示例

参考本仓库 `app/` 模块（`MainActivity.java`）：纯外壳 App，UI + 批跑测试工具，
全部推理能力通过 `implementation project(":geniehtp")` 引入，即为本 SDK 的标准集成示范。
