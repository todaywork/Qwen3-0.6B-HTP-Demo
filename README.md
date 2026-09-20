# Qwen3-0.6B HTP Demo（SA8295P / SA8255P / SA8775P）

基于 Qualcomm Genie / QNN HTP（QAIRT）的 Qwen3-0.6B 端侧推理 Demo 应用，
在 SA8295P（HTP v68）或 SA8255P/SA8775P（HTP v73）的 NPU 上运行 W4A16 量化模型。

- 应用包名：`com.qairt.qwen3htp`
- 支持单次对话推理和 Excel 语料批量推理
- HTP 架构由 Gradle 渠道在编译期固定，不再根据 SOC_MODEL 回退或跨架构切换。
- `app/src/v68`、`app/src/v73` 分别包含独立的 Java 架构配置、ARM64 so 和 DSP assets；公共业务代码在 `main`。
- 两个渠道保留相同包名，安装时相互覆盖，兼容现有批跑脚本。

## 一、构建和安装

```bat
gradlew.bat :app:assembleV68Debug :app:assembleV73Debug
REM 分别安装对应设备的渠道
gradlew.bat :app:installV68Debug
gradlew.bat :app:installV73Debug
```

产物分别为 `app/build/outputs/apk/v68/debug/app-v68-debug.apk` 和
`app/build/outputs/apk/v73/debug/app-v73-debug.apk`。
Release 构建使用 `assembleV68Release` / `assembleV73Release`，发布前需配置签名。

运行库来源：

- v68：`E:\QualComm\ai-hub-compiles\qwen3_0_6b-geniex_qairt-w4a16-qualcomm-sa8295p-ctx512-qnn234-local`
- v73：`E:\QualComm\ai-hub-compiles\qwen3_0_6b-geniex_qairt-w4a16-sa8775p-ctx512-qairt246-local`

各来源的 `genie_cli/libs` 对应渠道 `jniLibs/arm64-v8a`，`dsp` 对应渠道
`assets/qnn/vXX/dsp`。运行 `python scripts/refresh_channel_runtime.py --channel 73` 可单独同步 V73（V68 使用 `--channel 68`），
并生成包含 SHA-256 的 `runtime-manifest.json`。日常构建直接使用项目内副本，不依赖 E 盘。
旧 `main/jniLibs` 和 `main/assets` 已从构建中排除，不参与链接和打包。

## 二、模型安装（推送路径）

应用优先从当前 Android 用户的应用私有目录 `files/models/v68` 或 `files/models/v73`
加载模型（按 APK 渠道选择）。未创建该目录时兼容旧路径：

```
/data/local/tmp/genie_qwen3_quality
```

> SELinux Enforcing 设备可能禁止应用读取 `/data/local/tmp`，即使 chmod 777 也无效。
> Debug APK 可用 `scripts/install_private_model.ps1` 将已推送的模型复制到应用私有目录；无需关闭 SELinux。
> `-Serial` 指定设备，`-Channel v68` 或 `v73` 指定渠道。脚本自动获取当前 Android 用户。

### 目录结构要求

```
/data/local/tmp/genie_qwen3_quality/
├── tokenizer.json                # Qwen3 分词器
├── htp_backend_ext_config.json   # HTP 后端扩展配置（推送根目录下的 htp_backend_ext_config.device.json 并重命名）
├── part1_of_2.bin                # Genie context binary 第 1 段（约 297 MiB）
└── part2_of_2.bin                # Genie context binary 第 2 段（约 380 MiB）
```

JNI 层启动时会校验以下模型文件和自动解压的 Skel 是否存在，缺一即报错：

- `tokenizer.json`
- `htp_backend_ext_config.json`
- `part1_of_2.bin`
- `part2_of_2.bin`
- SA8295P：应用私有目录中的 `libQnnHtpV68Skel.so`
- SA8255P / SA8775P：应用私有目录中的 `libQnnHtpV73Skel.so`

`htp_backend_ext_config.json` 和 context binary 必须与目标 SoC/HTP 架构匹配。
请安装与目标设备匹配的渠道；`SOC_MODEL` 仅用于显示，不决定运行架构。

### 推送命令示例

```bash
MODEL_ROOT=/data/local/tmp/genie_qwen3_quality

adb shell mkdir -p $MODEL_ROOT

# 模型文件（AI Hub 编译产物）
adb push tokenizer.json                $MODEL_ROOT/
adb push part1_of_2.bin                $MODEL_ROOT/
adb push part2_of_2.bin                $MODEL_ROOT/
adb push htp_backend_ext_config.device.json $MODEL_ROOT/htp_backend_ext_config.json
```

每个 APK 只包含自身渠道的 DSP 运行库，每次进程启动后首次使用时自动刷新到私有目录。
推理的 DSP 搜索路径优先使用该目录，不使用模型目录下的旧 DSP 库。JNI 会拒绝与渠道不一致的架构参数。

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

### 渠道运行库

v68 运行库已切换为 QNN 2.34；v73 继续使用 QAIRT 2.46。
注意：当前 JNI 仍调用 QNN 2.34 未提供的手动分词、动态 token 上限接口，V68 重新构建前需要适配旧版 API 和配置；本次仅替换运行库。
应用显示名称分别为 `Qwen3 0.6B HTP Demo V68` 和 `Qwen3 0.6B HTP Demo V73`。
