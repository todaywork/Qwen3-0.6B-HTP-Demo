# Qwen2.5-0.5B Instruct HTP JNI App 部署文档

## 1. 当前版本定位

当前 APK 是 Qwen2.5-0.5B Instruct 的 Genie/QNN HTP App 集成验证版本。

它走的是 JNI 集成路线，不是在 App 内执行 `qnn-net-run` 或 `genie-t2t-run` shell 命令。

完整链路：

```text
Android App UI
-> Java MainActivity
-> GenieNative JNI
-> C++ libqwen3genie.so
-> libGenie.so
-> libQnnHtp.so
-> libQnnHtpV73Stub.so
-> libQnnHtpV73Skel.so / CDSP
-> HTP 推理
-> JNI 返回文本和 profile
-> Java UI 显示输出、性能和 HTP 证据
```

这个版本的目标不是替代 Qwen3-0.6B 主线模型，而是作为 known-good 质量基线，用来证明 App、普通 UID、Genie、QNN HTP、SM8550 设备这条链路可以输出可读中文自然语言。

## 2. 已验证环境

主机：

```text
Windows + Android Gradle 构建环境
Gradle: D:\Android\gradle-8.4\bin\gradle.bat
项目目录: D:\QAIRT-Workspace\android-apps\Qwen3GenieDemo
```

目标设备：

```text
SM8550 / Snapdragon 8 Gen 2 Android 设备
设备 BSP/镜像已启用 HTP/CDSP
adb 可连接
```

设备侧模型目录：

```text
/data/local/tmp/genie_qwen25_quality
```

## 3. Qwen2.5 baseline 产物来源

原始来源：

```text
E:\QualComm\Qnnllm_handoff_FULL_20260617_090120
```

隔离归档：

```text
D:\QAIRT-Workspace\baselines\qwen2.5-0.5b-instruct-htp-quality-baseline
```

归档内结构：

```text
config/
  htp_backend_ext_config.json
  qwen2.5-0.5b-instruct-htp.json

model/
  qwen2.5-0.5b-instruct-tokenizer.json
  qwen2.5-0.5b-instruct_qnn229_qcs8550_4096_1_of_2.serialized.bin
  qwen2.5-0.5b-instruct_qnn229_qcs8550_4096_2_of_2.serialized.bin

runtime/arm64-v8a/
  libGenie.so
  libQnnHtp.so
  libQnnHtpNetRunExtensions.so
  libQnnHtpV73Skel.so
  libQnnHtpV73Stub.so
  libQnnSystem.so
  libc++.so.1
  libc++abi.so.1

tools/android-arm64/
  genie-t2t-run
  qnn-net-run
```

注意：这套 baseline 使用 Genie `1.8.0`。不要只替换模型文件而继续混用 Genie `1.18.0`，否则可能出现 `GenieDialog_create failed, status=-1`。

## 4. 设备侧模型部署

从隔离归档目录推送到设备：

```powershell
$baseline='D:\QAIRT-Workspace\baselines\qwen2.5-0.5b-instruct-htp-quality-baseline'
$dst='/data/local/tmp/genie_qwen25_quality'

adb shell "rm -rf $dst && mkdir -p $dst"

adb push "$baseline\model\qwen2.5-0.5b-instruct-tokenizer.json" "$dst/"
adb push "$baseline\model\qwen2.5-0.5b-instruct_qnn229_qcs8550_4096_1_of_2.serialized.bin" "$dst/"
adb push "$baseline\model\qwen2.5-0.5b-instruct_qnn229_qcs8550_4096_2_of_2.serialized.bin" "$dst/"

adb push "$baseline\config\qwen2.5-0.5b-instruct-htp.json" "$dst/"
adb push "$baseline\config\htp_backend_ext_config.json" "$dst/"

adb push "$baseline\runtime\arm64-v8a\libGenie.so" "$dst/"
adb push "$baseline\runtime\arm64-v8a\libQnnHtp.so" "$dst/"
adb push "$baseline\runtime\arm64-v8a\libQnnHtpNetRunExtensions.so" "$dst/"
adb push "$baseline\runtime\arm64-v8a\libQnnHtpV73Skel.so" "$dst/"
adb push "$baseline\runtime\arm64-v8a\libQnnHtpV73Stub.so" "$dst/"
adb push "$baseline\runtime\arm64-v8a\libQnnSystem.so" "$dst/"
adb push "$baseline\runtime\arm64-v8a\libc++.so.1" "$dst/"
adb push "$baseline\runtime\arm64-v8a\libc++abi.so.1" "$dst/"

adb push "$baseline\tools\android-arm64\genie-t2t-run" "$dst/"
adb push "$baseline\tools\android-arm64\qnn-net-run" "$dst/"

adb shell "chmod -R 755 $dst"
```

## 5. APK 内置 runtime

当前 App 在 `app/src/main/jniLibs/arm64-v8a/` 内置以下 Qwen2.5 baseline runtime：

```text
libGenie.so
libQnnHtp.so
libQnnHtpNetRunExtensions.so
libQnnHtpV73Stub.so
libQnnSystem.so
libc++.so.1
libc++abi.so.1
```

`libQnnHtpV73Skel.so` 放在设备模型目录 `/data/local/tmp/genie_qwen25_quality`，通过 JNI 中设置的环境变量让 CDSP 加载：

```text
ADSP_LIBRARY_PATH=/vendor/lib/rfsa/adsp;/data/local/tmp/genie_qwen25_quality;/data/local/tmp/genie_qwen25_quality/dsp;/data/local/tmp/genie_qwen25_quality/lib
CDSP_LIBRARY_PATH=同上
CDSP1_LIBRARY_PATH=同上
```

## 6. App 构建

```powershell
cd D:\QAIRT-Workspace\android-apps\Qwen3GenieDemo
D:\Android\gradle-8.4\bin\gradle.bat assembleDebug
```

产物：

```text
D:\QAIRT-Workspace\android-apps\Qwen3GenieDemo\app\build\outputs\apk\debug\app-debug.apk
```

## 7. App 安装和运行

```powershell
$apk='D:\QAIRT-Workspace\android-apps\Qwen3GenieDemo\app\build\outputs\apk\debug\app-debug.apk'

adb install -r $apk
adb logcat -c
adb shell am force-stop com.qairt.qwen3geniedemo
adb shell "am start -n com.qairt.qwen3geniedemo/.MainActivity --ez autorun true --es prompt '把空调调到24度，只回答意图。'"
```

App 也可以从 Launcher 手动打开，名称为：

```text
Qwen2.5 HTP Demo
```

## 8. 预期 UI 输出

状态区：

```text
Qwen2.5 QNN HTP Demo
Genie: 1.8.0
Route: Genie / QnnHtp / Qwen2.5-0.5B / SM8550 V73
Model root: /data/local/tmp/genie_qwen25_quality
Quality note: HTP natural-language quality baseline.
```

输入：

```text
把空调调到24度，只回答意图。
```

输出：

```text
降低温度
```

性能基线：

```text
Session init: 1396 ms
Query wall time: 69 ms
End-to-end time: 1466 ms
Prompt tokens: 36
Generated tokens: 3
TTFT: 43.849 ms
Prompt rate: 821.0 tok/s
Token rate: 126.8 tok/s
```

## 9. HTP 证据验证

抓取日志：

```powershell
adb logcat -d -v time | Select-String -Pattern 'Qwen3GenieDemo|Qwen3GenieJni|QnnHtp|HTP evidence|libcdsprpc|libQnnHtpV73Skel'
```

有效运行应包含：

```text
GenieDialog_create success
GenieDialog_query finished, status=0
HTP evidence: DETECTED
QnnHtp loaded: yes
libQnnHtp.so
libQnnHtpNetRunExtensions.so
libQnnHtpV73Stub.so
libcdsprpc.so
Successfully opened handle ... for file:///libQnnHtpV73Skel.so ... _dom=cdsp
```

## 10. CLI 对照验证

CLI 不是当前 APK 的运行方式，但可作为 baseline 对照。

```powershell
adb shell "cat > /data/local/tmp/genie_qwen25_quality/prompt.txt <<'EOF'
<|im_start|>system
You are a vehicle assistant. Reply briefly in Chinese.<|im_end|>
<|im_start|>user
把空调调到24度，只回答意图。<|im_end|>
<|im_start|>assistant
EOF"

adb shell "cd /data/local/tmp/genie_qwen25_quality && \
  export LD_LIBRARY_PATH=/data/local/tmp/genie_qwen25_quality && \
  export ADSP_LIBRARY_PATH='/vendor/lib/rfsa/adsp;/data/local/tmp/genie_qwen25_quality;.' && \
  export CDSP_LIBRARY_PATH='/vendor/lib/rfsa/adsp;/data/local/tmp/genie_qwen25_quality;.' && \
  export CDSP1_LIBRARY_PATH='/vendor/lib/rfsa/adsp;/data/local/tmp/genie_qwen25_quality;.' && \
  ./genie-t2t-run -c qwen2.5-0.5b-instruct-htp.json --prompt_file prompt.txt --profile qwen25_quality_profile.csv --log info"
```

预期：

```text
Using libGenie.so version 1.8.0
[BEGIN]: 降低温度[END]
```

## 11. 常见问题

### 11.1 GenieDialog_create failed, status=-1

优先检查：

- APK 内置 `libGenie.so` 是否是 Qwen2.5 baseline 的 Genie 1.8.0。
- `/data/local/tmp/genie_qwen25_quality` 是否包含 tokenizer、两个 serialized bin、HTP config 和 skel。
- `ADSP_LIBRARY_PATH` / `CDSP_LIBRARY_PATH` 是否包含模型目录。
- 不要混用 Qwen2.5 serialized bin 和 QAIRT 2.46 / Genie 1.18.0 runtime。

### 11.2 UI 没有显示 HTP evidence

检查日志是否包含：

```text
libQnnHtp.so
libQnnHtpV73Stub.so
libcdsprpc.so
libQnnHtpV73Skel.so
```

如果没有，说明可能没有加载到 HTP backend 或 DSP skel。

### 11.3 输出中文质量不稳定

当前 baseline 是 0.5B 小模型，适合开发验证。中文和英文短指令可用，日文/混合语言存在输出不自然或异常字符风险。

## 12. 当前结论

当前版本已经完成：

- App JNI 集成可用。
- 普通 UID App 可运行 Genie/QNN HTP。
- Qwen2.5-0.5B Instruct 可输出可读中文。
- UI 可显示输出文本、性能指标和 HTP evidence。
- 该版本可作为 Qwen3-0.6B HTP 产物重建时的 known-good 对照基线。
