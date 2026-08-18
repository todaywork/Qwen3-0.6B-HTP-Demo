# Qwen3-0.6B HTP Demo（SA8775 ADP）

基于 Qualcomm Genie / QNN HTP（QAIRT）的 Qwen3-0.6B 端侧推理 Demo 应用，
在 SA8775 ADP 开发板的 NPU（HTP v73）上运行 W4A16 量化模型。

- 应用包名：`com.qairt.qwen3htp`
- 支持单次对话推理和 Excel 语料批量推理

## 一、安装应用

```bash
# 方式 1：Gradle 构建并安装（需要 JDK）
gradlew.bat installDebug

# 方式 2：直接安装已构建的 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 二、模型安装（推送路径）

应用启动时从设备固定路径加载模型：

```
/data/local/tmp/genie_qwen3_quality
```

> 该路径定义在 `app/src/main/java/com/qairt/qwen3htp/MainActivity.java` 的 `MODEL_ROOT`。

### 目录结构要求

```
/data/local/tmp/genie_qwen3_quality/
├── tokenizer.json                # Qwen3 分词器
├── htp_backend_ext_config.json   # HTP 后端扩展配置（推送根目录下的 htp_backend_ext_config.device.json 并重命名）
├── part1_of_2.bin                # Genie context binary 第 1 段（约 297 MiB）
├── part2_of_2.bin                # Genie context binary 第 2 段（约 380 MiB）
├── dsp/
│   └── libQnnHtpV73Skel.so       # HTP v73 Skel 库（DSP 侧，必须放在 dsp/ 子目录）
└── lib/                          # QNN/Genie 运行时库（可选，也可放模型根目录）
    ├── libGenie.so
    ├── libQnnHtp.so
    ├── libQnnHtpNetRunExtensions.so
    ├── libQnnHtpV73Stub.so
    └── libQnnSystem.so
```

JNI 层启动时会校验以下 5 个文件是否存在，缺一即报错：

- `tokenizer.json`
- `htp_backend_ext_config.json`
- `part1_of_2.bin`
- `part2_of_2.bin`
- `dsp/libQnnHtpV73Skel.so`

### 推送命令示例

```bash
MODEL_ROOT=/data/local/tmp/genie_qwen3_quality

adb shell mkdir -p $MODEL_ROOT/dsp $MODEL_ROOT/lib

# 模型文件（AI Hub 编译产物）
adb push tokenizer.json                $MODEL_ROOT/
adb push part1_of_2.bin                $MODEL_ROOT/
adb push part2_of_2.bin                $MODEL_ROOT/
adb push htp_backend_ext_config.device.json $MODEL_ROOT/htp_backend_ext_config.json

# DSP Skel 库
adb push libQnnHtpV73Skel.so           $MODEL_ROOT/dsp/

# 运行时库（项目 lib/ 目录已包含）
adb push lib/                          $MODEL_ROOT/lib/
```

也可以使用项目自带的推送脚本（PowerShell，包含完整性校验）：

```powershell
powershell -File scripts/genie_cli/run_genie_cli.ps1 `
    -DeviceModelRoot "/data/local/tmp/genie_qwen3_quality"
```

## 三、批量推理语料（可选）

批量测试的 Excel 语料推送目录：

```
/data/local/tmp/nlutest/
```

应用默认选中“推送目录中的 Excel”，会按文件名顺序读取目录内全部 `.xlsx`
文件并合并批跑（忽略 `~$` 开头的 Excel 临时文件）。也可以在界面切换为
“输入框内容”，每行输入一条语料；两种来源互斥，只执行当前选中的来源。

```bash
adb shell mkdir -p /data/local/tmp/nlutest
adb push nlutest.xlsx /data/local/tmp/nlutest/
```

自动化批跑脚本见 `scripts/语料批跑自动化/run_batch_inference.py`，
说明文档见 `docs/batch-inference-automation.zh-CN.md`。

## 四、模型编译（云端）

模型需先在 AutoDL 上完成 W4A16 量化，再通过 Qualcomm AI Hub 编译为
GenieX-QAIRT 产物（context binary），流程详见：

- `AutoDL_AIHub_Qwen3-0.6B_GenieX-QAIRT_SA8775部署指南.md`
- `docs/Qwen3-0.6B-CAR-SA8775P-GenieX-QAIRT-CL512-部署方案.md`

## 五、验证

```bash
# 查看推理日志
adb logcat -s Qwen3GenieJni QNN_GENIE_LOG
```

应用界面会显示当前模型根目录（Model root）和加载状态。
