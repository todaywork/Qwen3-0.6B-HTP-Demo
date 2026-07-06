# Qwen3GenieDemo 新设备部署指导

本文档用于把当前 Android 项目部署到一台新的 SM8550/Android 设备上，并验证 App 通过 JNI 调用 Genie/QNN HTP 完成端侧推理。

当前项目路径：

```text
D:\QAIRT-Workspace\android-apps\Qwen3GenieDemo
```

当前 App 路线：

```text
Android App UI
-> Java MainActivity
-> GenieNative JNI
-> C++ libqwen3genie.so
-> libGenie.so
-> libQnnHtp.so
-> libQnnHtpV73Stub.so
-> 设备 CDSP/HTP
-> Genie 返回文本和 profile
-> App UI 展示输出、性能指标、HTP 运行证据
```

注意：当前项目不是在 App 内执行 `qnn-net-run` 或 `genie-t2t-run` 命令。`genie-t2t-run` 只作为 CLI 对照验证工具，App 正式验证路线是 JNI 集成路线。

## 1. 目标设备要求

新设备需要满足：

```text
芯片: SM8550 / Snapdragon 8 Gen 2
系统: Android arm64
BSP/镜像: 已启用 HTP/CDSP
ADB: 可连接
存储: /data/local/tmp 至少预留 2GB
权限: 开发验证阶段允许 App 读取 /data/local/tmp 下的模型资源
```

设备侧模型目录固定为：

```text
/data/local/tmp/genie_qwen25_quality
```

当前 Java/C++ 代码中已经写死该路径。如果后续要换目录，需要同步修改：

```text
app/src/main/java/com/qairt/qwen3geniedemo/MainActivity.java
app/src/main/cpp/qwen3_genie_jni.cpp
```

## 2. Windows 主机环境要求

主机建议使用当前已验证的 Windows 环境：

```text
Android SDK: D:\Sdk
Gradle: D:\Android\gradle-8.4\bin\gradle.bat
项目: D:\QAIRT-Workspace\android-apps\Qwen3GenieDemo
```

项目中的 `local.properties` 当前配置为：

```properties
sdk.dir=D\:\\Sdk
```

需要确认以下工具可用：

```powershell
adb version
D:\Android\gradle-8.4\bin\gradle.bat -v
```

如果 `adb` 不在 PATH 中，可以使用 Android SDK 内的 adb：

```powershell
D:\Sdk\platform-tools\adb.exe version
```

## 3. 当前依赖资源来源

当前 App 使用 Qwen2.5-0.5B Instruct HTP quality baseline 资源。

主机侧基线目录：

```text
D:\QAIRT-Workspace\baselines\qwen2.5-0.5b-instruct-htp-quality-baseline
```

该目录应包含：

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

重要约束：

```text
当前 baseline 使用 Genie 1.8.0。
不要把 Qwen2.5 serialized bin 和其他版本的 Genie runtime 混用。
混用后可能出现 GenieDialog_create failed, status=-1。
```

## 4. 新设备连接检查

连接设备后执行：

```powershell
adb devices
adb shell getprop ro.soc.model
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.build.version.release
adb shell "df -h /data/local/tmp"
```

预期结果：

```text
adb devices 能看到 device 状态
ro.soc.model 返回 SM8550 或等效平台标识
ro.product.cpu.abi 返回 arm64-v8a
/data/local/tmp 剩余空间充足
```

如果设备未授权，先在设备端确认 USB 调试授权，再重新执行：

```powershell
adb kill-server
adb start-server
adb devices
```

## 5. 推送模型和 HTP 运行时资源

执行以下命令，把模型、配置、HTP skel 和 CLI 对照工具推送到新设备：

```powershell
$baseline = "D:\QAIRT-Workspace\baselines\qwen2.5-0.5b-instruct-htp-quality-baseline"
$dst = "/data/local/tmp/genie_qwen25_quality"

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
adb shell "ls -lh $dst"
```

有效判断：

```text
目录中必须能看到 tokenizer json
目录中必须能看到两个 serialized.bin
目录中必须能看到 htp_backend_ext_config.json
目录中必须能看到 libQnnHtpV73Skel.so
目录权限应允许普通 App 读取
```

## 6. 构建 APK

在项目根目录执行：

```powershell
cd D:\QAIRT-Workspace\android-apps\Qwen3GenieDemo
D:\Android\gradle-8.4\bin\gradle.bat clean assembleDebug
```

APK 产物路径：

```text
D:\QAIRT-Workspace\android-apps\Qwen3GenieDemo\app\build\outputs\apk\debug\app-debug.apk
```

如果只想快速增量构建，可以执行：

```powershell
D:\Android\gradle-8.4\bin\gradle.bat assembleDebug
```

## 7. 安装 APK

```powershell
$apk = "D:\QAIRT-Workspace\android-apps\Qwen3GenieDemo\app\build\outputs\apk\debug\app-debug.apk"
adb install -r $apk
```

应用包名：

```text
com.qairt.qwen3geniedemo
```

Launcher 显示名称：

```text
Qwen2.5 HTP Demo
```

## 8. 启动 App 并自动运行一次

先清空日志，再启动：

```powershell
adb logcat -c
adb shell am force-stop com.qairt.qwen3geniedemo
adb shell "am start -n com.qairt.qwen3geniedemo/.MainActivity --ez autorun true --es prompt '把空调调到24度，只回答意图。'"
```

也可以从设备 Launcher 手动打开 `Qwen2.5 HTP Demo`，输入文本后点击 `Run`。

## 9. UI 预期结果

App 页面应能看到：

```text
Qwen2.5 QNN HTP Demo
Genie: 1.8.0
Route: Genie / QnnHtp / Qwen2.5-0.5B / SM8550 V73
Model root: /data/local/tmp/genie_qwen25_quality
```

性能区域应显示：

```text
Session init
Query wall time
End-to-end time
Prompt chars
Output chars
Prompt tokens
Generated tokens
TTFT
Prompt rate
Token rate
```

HTP 证据区域应显示类似：

```text
HTP evidence: DETECTED
Genie loaded: yes
QnnHtp loaded: yes
HTP/DSP/RPC terms in loaded maps: yes
Matched libraries:
  libGenie.so
  libQnnHtp.so
  libQnnHtpV73Stub.so
  ...
```

## 10. 日志验证 HTP 是否加载

执行：

```powershell
adb logcat -d -v time | Select-String -Pattern "QNN_GENIE_LOG|QNN_BACKEND_PROOF|Qwen3GenieDemo|Qwen3GenieJni|QnnHtp|QnnDsp|HTP evidence|libcdsprpc|libadsprpc|libQnnHtpV73Skel|GenieDialog"
```

有效日志通常包含：

```text
Loading native library: QnnSystem
Loading native library: QnnHtp
Loading native library: Genie
Native libraries loaded.
GenieDialog_create success
GenieDialog_query finished, status=0
Runtime evidence:
HTP evidence: DETECTED
QnnHtp loaded: yes
```

当前版本已经在 JNI 中注册 Genie/QNN 日志回调，底层 Genie/QNN 日志会转发到 Android logcat 的 `QNN_GENIE_LOG` tag。

最强的 HTP/NPU 运行证据不是只看 `/proc/pid/maps`，而是看 QNN 系统日志中是否出现：

```text
QNN_BACKEND_PROOF backend=QnnHtp
QNN_GENIE_LOG ... QnnDsp
QnnGraph_execute started
QnnGraph_execute done. status 0x0
Graph ar1_cl4096_1_of_2 execution finished with result 0
Graph ar1_cl4096_2_of_2 execution finished with result 0
qnn-htp: run-inference complete
GenieDialog_query finished, status=0
```

如果 `android:extractNativeLibs="false"`，`/proc/pid/maps` 中很多 APK 内置 so 会显示为 `base.apk`，不一定显示 `libQnnHtp.so` 文件名。因此 `QnnHtp loaded: no` 可能只是 maps 文件名检测误判；此时应以 `QNN_GENIE_LOG` 中的 `QnnDsp/QnnGraph_execute/qnn-htp` 日志作为更强证据。

如果日志中出现 CDSP/skel 加载记录，也可以作为更强证据：

```text
libQnnHtpV73Skel.so
cdsp
adsprpc
fastrpc
```

## 11. CLI 对照验证

CLI 验证不是 App 的运行方式，只用于排查模型资源和 runtime 是否可用。

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
能够输出中文或车辆控制意图文本
```

如果 CLI 成功但 App 失败，优先检查：

```text
APK 内置 so 版本
App 是否能读取 /data/local/tmp/genie_qwen25_quality
App JNI 设置的 ADSP_LIBRARY_PATH/CDSP_LIBRARY_PATH
Android 普通 UID 对 /data/local/tmp 资源的读取权限
```

## 12. 常见问题

### 12.1 App 提示 Missing required file

原因：设备侧模型目录缺文件，或者路径不一致。

检查：

```powershell
adb shell "ls -lh /data/local/tmp/genie_qwen25_quality"
```

必须存在：

```text
qwen2.5-0.5b-instruct-tokenizer.json
qwen2.5-0.5b-instruct_qnn229_qcs8550_4096_1_of_2.serialized.bin
qwen2.5-0.5b-instruct_qnn229_qcs8550_4096_2_of_2.serialized.bin
htp_backend_ext_config.json
```

### 12.2 GenieDialog_create failed, status=-1

优先检查：

```text
libGenie.so 是否为当前 baseline 的 Genie 1.8.0
Qwen2.5 serialized bin 是否和 runtime 版本匹配
libQnnHtpV73Skel.so 是否已推送到模型目录
ADSP_LIBRARY_PATH/CDSP_LIBRARY_PATH 是否包含模型目录
BSP 是否启用 HTP/CDSP
```

### 12.3 HTP evidence 显示 NOT DETECTED

说明 App 进程 maps 中没有采集到 HTP/DSP/RPC 相关库。继续检查：

```powershell
adb logcat -d -v time | Select-String -Pattern "QnnHtp|V73Skel|cdsp|adsprpc|fastrpc"
```

如果完全没有相关日志，说明可能没有进入 QnnHtp backend，或者 skel/CDSP 加载失败。

### 12.4 Launcher 不显示 App

当前 Manifest 已配置 launcher activity：

```xml
<action android:name="android.intent.action.MAIN" />
<category android:name="android.intent.category.LAUNCHER" />
```

如果仍不显示，执行：

```powershell
adb shell pm list packages | Select-String "qairt"
adb shell cmd package resolve-activity --brief com.qairt.qwen3geniedemo
adb shell am start -n com.qairt.qwen3geniedemo/.MainActivity
```

### 12.5 中文显示异常

如果 App 源码里中文字符串显示异常，优先确认源码文件编码是否为 UTF-8。当前核心验证不依赖默认中文 prompt，可以通过 `am start --es prompt` 或 UI 手动输入中文。

## 13. 新设备部署完成判断

满足以下条件即可认为新设备部署成功：

```text
1. adb 能稳定连接新设备
2. ro.soc.model 确认为 SM8550 或等效目标平台
3. /data/local/tmp/genie_qwen25_quality 资源完整
4. APK 安装成功
5. App 可从 Launcher 或 am start 启动
6. 点击 Run 后 UI 能显示推理输出
7. UI 能显示性能指标
8. Runtime evidence 显示 HTP evidence: DETECTED
9. logcat 能看到 GenieDialog_create success 和 GenieDialog_query finished
```

## 14. 部署记录模板

每次换新设备建议记录：

```text
设备名称:
设备序列号:
ro.soc.model:
Android 版本:
部署日期:
APK 路径:
模型目录:
模型资源是否完整:
App 是否启动:
推理是否有输出:
HTP evidence:
Session init:
TTFT:
Token rate:
问题与处理:
最终结论:
```
