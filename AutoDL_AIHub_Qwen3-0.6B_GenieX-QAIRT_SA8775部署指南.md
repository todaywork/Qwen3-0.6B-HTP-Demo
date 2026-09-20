# AutoDL + AI Hub 编译 Qwen3-0.6B GenieX-QAIRT 模型（SA8775 ADP）

本文按当前 AutoDL 实例的实际路径，说明如何使用完整 Hugging Face 模型 /root/autodl-tmp/work/source_checkpoint 完成 W4A16 量化，再通过 Qualcomm AI Hub 编译为面向 SA8775 ADP 的 GenieX-QAIRT 产物。该路径当前是软链接，实际指向 `/root/autodl-tmp/work/qwen3-06b-car-merged-v5`。

> 本文中的 Linux 命令均在 AutoDL 网页终端或已经登录的 AutoDL Shell 中直接执行，不需要在命令外层再套 `ssh`，也不使用 `nohup`。命令以前台方式运行，并用 `tee` 实时显示和保存日志。

> 适用前提：模型结构仍为 Qwen3-0.6B。若目录中只有 LoRA/QLoRA 的 `adapter_model.safetensors`，必须先与基座模型合并；不能直接量化 adapter。

## 1. 环境要求

- AutoDL：Ubuntu 22.04、Python 3.10、CUDA 12.x，建议 RTX 3090 24GB 或更高。
- 官方 AdaScale 配方耗时和临时数据量较大，建议内存 120GB 、AutoDL 数据盘预留 200GB 以上。
- Qualcomm AI Hub 账号及 API Token。
- 本地 Qualcomm AI Hub Models Python 源码库：`E:\QualComm\ai-hub-models-0.60.0`。从github官网下载
- 本地模型目录至少包含 `config.json`、`*.safetensors`、Tokenizer 文件；分片权重还应包含 `model.safetensors.index.json`。

所有中间文件必须放在 AutoDL 数据盘 `/root/autodl-tmp`，不要放在系统盘或默认 `/tmp`。

## 2. 当前远端路径与可选上传

当前 AutoDL 已有源模型、Python 环境和源码，无需重复上传。实际路径如下：

```text
源模型入口=/root/autodl-tmp/work/source_checkpoint
源模型实际目录=/root/autodl-tmp/work/qwen3-06b-car-merged-v5
量化保存目录 checkpoint=/root/autodl-tmp/qwen3_car_w4a16_cl4096
ai-hub-models 源码=/root/autodl-tmp/ai-hub-models-0.60.0 从github官网下载
Python 环境=/root/autodl-tmp/conda-envs/qaihm060
```

只有需要替换为 Windows 上的新模型时，才在 PowerShell 中执行：

```powershell
$AutoDLHost = "root@xxx.com"
$AutoDLPort = 端口

# 上传新模型；不要覆盖当前 source_checkpoint 指向的旧模型
scp -P $AutoDLPort -r "E:\LLMProject\models\source_model" "${AutoDLHost}:/root/autodl-tmp/work/"

# 上传 Qualcomm AI Hub Models 0.60.0 Python 源码库
scp -P $AutoDLPort -r "E:\QualComm\ai-hub-models-0.60.0" "${AutoDLHost}:/root/autodl-tmp/"
```

新模型上传后的路径为 `/root/autodl-tmp/work/source_model`。本文后续命令仍使用当前已经验证过的 `/root/autodl-tmp/work/source_checkpoint`；切换新模型时，需要统一替换量化命令中的输入路径并使用新的输出目录。

```text
/root/autodl-tmp/work/source_checkpoint
/root/autodl-tmp/ai-hub-models-0.60.0
```

## 3. 使用当前 Python 环境

当前环境已经安装完成。打开 AutoDL 终端后直接执行：

```bash
source /root/miniconda3/etc/profile.d/conda.sh
conda activate /root/autodl-tmp/conda-envs/qaihm060

python --version
qai-hub-models --version
```

预期分别为 Python 3.10.x 和 AI Hub Models 0.60.0。以下安装命令仅用于重建环境，当前实例不需要重复执行：

```bash
source /root/miniconda3/etc/profile.d/conda.sh

export QAIHM_VERSION=0.60.0
export QAIHM_REPO=/root/autodl-tmp/ai-hub-models-"$QAIHM_VERSION"
export QAIHM_ENV=/root/autodl-tmp/conda-envs/qaihm060

test -d "$QAIHM_REPO/cli" || exit 1
test -d "$QAIHM_REPO/src" || exit 1

conda create -p "$QAIHM_ENV" python=3.10 -y
conda activate "$QAIHM_ENV"

export QAIHM_PY="$QAIHM_ENV/bin/python"
export SETUPTOOLS_SCM_PRETEND_VERSION="$QAIHM_VERSION"

"$QAIHM_PY" -m pip install --upgrade pip

#`qai-hub-models` 项目有两个子包：`cli/` 和 `src/`。主包（`src/`）依赖 CLI 包（`cli/`），
# 而 CLI 包不在 PyPI 上，必须先本地安装。
"$QAIHM_PY" -m pip install -e "$QAIHM_REPO/cli"
"$QAIHM_PY" -m pip install -e "$QAIHM_REPO/src"

"$QAIHM_PY" -m pip install \
  -r "$QAIHM_REPO/src/qai_hub_models/models/qwen3_0_6b/requirements-gpu.txt" \
  -i https://pypi.tuna.tsinghua.edu.cn/simple \
  --trusted-host pypi.tuna.tsinghua.edu.cn
  
  # 安装后检查环境：
"$QAIHM_PY" -m pip show qai_hub_models | grep -E 'Name|Version|Editable'
"$QAIHM_PY" -m pip show qai_hub_models_cli | grep -E 'Name|Version|Editable'
```

若仓库布局中主包位于 `src/`，则进入该目录执行 editable install。安装前可用以下命令定位文件：

```bash
find /root/autodl-tmp/ai-hub-models-0.60.0 -maxdepth 5 \( -name pyproject.toml -o -name requirements-gpu.txt \) -print
```

确认 ONNX Runtime 能使用 GPU：

```bash
/root/autodl-tmp/conda-envs/qaihm060/bin/python -c "import onnxruntime as ort; print(ort.get_available_providers())"
```

输出应包含 `CUDAExecutionProvider`。若只有 CPU Provider，卸载 CPU 版并安装与当前 CUDA 匹配的 `onnxruntime-gpu`。

## 4. 配置缓存和临时目录

```bash
mkdir -p /root/autodl-tmp/{tmp,hf-cache,qaihm-store,logs,output}

export TMPDIR=/root/autodl-tmp/tmp
export TMP=/root/autodl-tmp/tmp
export HF_HOME=/root/autodl-tmp/hf-cache
export QAIHM_STORE_ROOT=/root/autodl-tmp/qaihm-store
export HF_ENDPOINT=https://hf-mirror.com
export HF_HUB_DISABLE_XET=1
export AIMET_ADASCALE_TMPDIR=/dev/shm
export SETUPTOOLS_SCM_PRETEND_VERSION=0.60.0
```

`/dev/shm` 必须有足够空间；空间不足时不要强行使用 AdaScale RAM 临时目录。

## 5. 检查本地模型

```bash
export SOURCE_CHECKPOINT=/root/autodl-tmp/work/source_checkpoint

test -s "$SOURCE_CHECKPOINT/config.json" || { echo "缺少 config.json"; exit 1; }
find "$SOURCE_CHECKPOINT" -maxdepth 1 -type f \( -name '*.safetensors' -o -name 'tokenizer*' \) -print
```

建议同时确认 `config.json` 中的模型类型、层数、hidden size 和 attention heads 与 Qwen3-0.6B 一致。

### 5.1 检查并替换校准语料

生成chatjinja模板的校准语料：

D:\QAIRT-Workspace\android-apps\Qwen3-0.6B-HTP-Demo\calib_v5

执行脚本。生成校准语料。

**在第一次跑量化之前**就预置文件，一劳永逸：直接替换训练语料

```bash
mkdir -p /root/autodl-tmp/qaihm-store/.qaihm/qai-hub-models/datasets/generated_calibration_qwen3_0_6b/v1/
cp /path/to/your/generated_calibration_v5.txt \
   /root/autodl-tmp/qaihm-store/.qaihm/qai-hub-models/datasets/generated_calibration_qwen3_0_6b/v1/generated_calibration.txt
```



## 6. W4A16 量化

先查看当前安装版本实际支持的参数：

```bash
/root/autodl-tmp/conda-envs/qaihm060/bin/python -m qai_hub_models.models.qwen3_0_6b.quantize --help
```

当前量化 checkpoint 已经成功生成，通常应跳过本节主命令，直接进入第 7、8 节。下面是与当前产物对应的可执行命令；内置检查会在产物已存在时拒绝重复量化，避免覆盖：

```bash
cd /root/autodl-tmp/ai-hub-models-0.60.0
#  --use-spin-quant r2,r3 这个参数R2,R3版本效果远好于R1,R2.
set -o pipefail
  env \
    TMPDIR=/root/autodl-tmp/tmp \
    TMP=/root/autodl-tmp/tmp \
    HF_HOME=/root/autodl-tmp/hf-cache \
    QAIHM_STORE_ROOT=/root/autodl-tmp/qaihm-store \
    HF_ENDPOINT=https://hf-mirror.com \
    HF_HUB_DISABLE_XET=1 \
    AIMET_ADASCALE_TMPDIR=/dev/shm \
    /root/autodl-tmp/conda-envs/qaihm060/bin/python \
    -m qai_hub_models.models.qwen3_0_6b.quantize \
    --checkpoint /root/autodl-tmp/work/source_checkpoint \
    --precision w4a16 \
    --use-spin-quant r2,r3 \
    --use-seq-mse \
    --seq-mse-num-samples 12 \  --24
    --use-ada-scale \
    --ada-scale-num-samples 48 \
    --ada-scale-num-iterations 2048 \
    --num-samples 24 \  ---校准数据,48
    --context-length 4096 \
    --calibration-sequence-length 2048 \
    --output-dir /root/autodl-tmp/qwen3_car_w4a16_cl4096_v22 \
    2>&1 | tee /root/autodl-tmp/logs/quantize_w4a16_cl4096_v22.log

# 实时查看日志
tail -f /root/autodl-tmp/logs/quantize_w4a16_cl4096_v22.log

# 062版本的量化命令：

```

以上参数基于 Qualcomm 官方 Qwen3-0.6B W4A16 checkpoint 的 `args.json`，仅将 AdaScale 样本数从 128 调整为 48



### 6.1 量化完成后检查：(非必要检查)

这个脚本已经原生支持 `v19`，无需修改脚本。建议按“静态检查 → 本地结构检查 → Layer 2 云端验证 → 完整 28 层验证”的顺序执行，不建议直接 `--stage all`，因为最多会提交 29 个 compile 和 29 个 inference job。

以下命令应在保存 checkpoint 的 AutoDL Linux 主机执行。假设 v19 checkpoint 路径为 `/root/autodl-tmp/qwen3_car_w4a16_cl4096_v19`；如果实际目录不同，只改 `CHECKPOINT`。

```
# 正常执行
/root/autodl-tmp/conda-envs/qaihm060/bin/python \
  /root/autodl-tmp/tools/validate_qwen3_v17_v18.py \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v23 \
  --version v23
  
# 自动跑完全部流程：
/root/autodl-tmp/conda-envs/qaihm060/bin/python \
  /root/autodl-tmp/tools/validate_qwen3_v17_v18.py \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v21 \
  --version v21 \
  --stage all \
  --confirm-cloud
```

先确认 checkpoint 三件套存在：

```
ls -lh \
  "$CHECKPOINT/model_dynamic.onnx" \
  "$CHECKPOINT/model.data" \
  "$CHECKPOINT/model.encodings"
```

1. #### 静态量化门禁

```
"$QAIHM_PY" "$VALIDATOR" \
  --checkpoint "$CHECKPOINT" \
  --version v19 \
  --stage static

echo "exit_code=$?"
```

必须看到：

```
STATIC GATE: PASS
exit_code=0
```

查看报告：

```
"$QAIHM_PY" -m json.tool \
  "$LOG_ROOT/00_one_click_static_report.json"
```

重点检查：

- `"passed": true`
- `"failures": []`
- `layer2_down_proj.bitwidth = 8`
- `layer2_down_proj.enc_type = "PER_CHANNEL"`
- `layer2_down_proj.is_symmetric = true`
- `scale_count = 1024`
- `offset_count = 1024`
- `offset_values = [-128]`
- Layer 2 的 `gate_up/down/residual` 都是 A16
- checkpoint 三个文件都有非空 SHA-256

`layer2_down_scale_vs_other_median > 100` 只会产生 warning，不会令静态检查失败，但意味着 Layer 2 云端验证尤其重要。

#### 2. 本地检查 Layer 2 ONNX/encoding 结构

```
"$QAIHM_PY" "$VALIDATOR" \
  --checkpoint "$CHECKPOINT" \
  --version v20 \
  --stage inspect

echo "exit_code=$?"
```

日志位置：

```
less "$LOG_ROOT/01_one_click_inspect_layer2.log"
```

这一步不提交 AI Hub 任务。应确认脚本运行成功、Layer 2 的四个 tap 能找到，并且没有 tensor/encoding 缺失或维度异常。

#### 3. 只验证 Layer 2

这一步会提交 1 个 compile job 和 1 个 inference job。先确认目标设备，脚本默认是 `SA8775P ADP`。

```
"$QAIHM_PY" "$VALIDATOR" \
  --checkpoint "$CHECKPOINT" \
  --version v19 \
  --stage layer2 \
  --device "SA8775P ADP" \
  --confirm-cloud

echo "exit_code=$?"
```

检查 verdict：

```
"$QAIHM_PY" -m json.tool \
  "$LOG_ROOT/02_one_click_v19_layer2_phase_b_verdict.json"
```

必须满足：

- `"passed": true`
- `"failures": []`
- `down`: SQNR ≥ 40 dB，cosine ≥ 0.99
- `residual`: SQNR ≥ 40 dB，cosine ≥ 0.99
- `o_proj`: SQNR ≥ 44.447 dB，cosine ≥ 0.99
- `gate_up`: SQNR ≥ 41.742 dB，cosine ≥ 0.98
- 四个 tap：`o_proj/gate_up/down/residual` 全部存在
- 没有 `dropped_unencoded`

只有 Layer 2 输出：

```
PHASE B LAYER2 GATE: PASS
```

并且进程退出码为 `0`，才继续完整验证。

#### 4. 完整 28 层验证

这一步会新提交 28 个 compile job 和 28 个 inference job：

```
"$QAIHM_PY" "$VALIDATOR" \
  --checkpoint "$CHECKPOINT" \
  --version v21 \
  --stage full \
  --report "$LOG_ROOT/02_one_click_v19_layer2_phase_b_tap_per_layer.json" \
  --device "SA8775P ADP" \
  --confirm-cloud

echo "exit_code=$?"
```

检查最终 verdict：

```
"$QAIHM_PY" -m json.tool \
  "$LOG_ROOT/03_one_click_v19_phase_b_28_layers_verdict.json"
```

最终必须看到：

```
PHASE B FULL GATE: PASS
ALL REQUESTED VALIDATION GATES PASSED
exit_code=0
```

JSON 中必须满足：

- `"passed": true`
- `"failures": []`
- `"craters": []`
- Layer 0–27 一个不少、一个不多
- 每层都有 `o_proj/gate_up/down/residual`
- Layer 2 继续满足上述绝对门限
- 任意 tap 的任意层 SQNR 都不能比该 tap 的 28 层中位数低超过 12 dB

如果云端结果已经存在，只想重新判定而不重新提交任务，可以执行：

```
"$QAIHM_PY" "$VALIDATOR" \
  --checkpoint "$CHECKPOINT" \
  --version v19 \
  --stage evaluate \
  --scope full \
  --report "$LOG_ROOT/03_one_click_v19_phase_b_28_layers_tap_per_layer.json"
```

结论上，只有静态报告、Layer 2 verdict 和完整 28 层 verdict 三者全部 `passed: true`，才能认为 v19 checkpoint 的量化结果符合这个脚本定义的要求。它还不覆盖 production Genie bundle 和 Android 端 20 条语料回归，这两项仍需在 Phase B 通过后单独执行。



### 6.2 Smoke Test：验证量化后准确率（必要检查）

```bash
# 单条跑，用默认systemPrompt
/root/autodl-tmp/conda-envs/qaihm060/bin/python -m qai_hub_models.models.qwen3_0_6b.demo \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096 \
  --max-output-tokens 64 \
  --prompt "请用一句话说明什么是量化。"
  
# 指定提示词推理
/root/autodl-tmp/conda-envs/qaihm060/bin/python -m qai_hub_models.models.qwen3_0_6b.demo \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v23 \
  --max-output-tokens 64 \
  --context-length 512 \
  --sequence-length 128 1 \
  --no-thinking \
  --seed 42 \
  --raw \
  --prompt $'<|im_start|>system\nYou are a multilingual vehicle control assistant. Extract intent and slots from user commands in any language. Always respond with English JSON only. Do not think or explain.Only fill slot keys explicitly mentioned in the user command, otherwise use "slot":{}<|im_end|>\n<|im_start|>user\nУвеличь вентиляцию правого сиденья второго ряда на один уровень\n<|im_end|>\n<|im_start|>assistant\n'
  
# 批跑验证
cd  /root/autodl-tmp/
python smoke20/run_smoke20.py /root/autodl-tmp/qwen3_car_w4a16_cl4096_v21 v21
# 新协议
cd  /root/autodl-tmp/smoke20
python run_smoke_v5.py /root/autodl-tmp/qwen3_car_w4a16_cl4096_v23_062 v23_062
```

若 `demo --help` 显示还需要 context/sequence 参数，按当前版本帮助补充。

车载意图抽取场景可使用下面的 raw smoke test。`--raw` 允许自定义 system prompt，但会让 AI Hub Models 直接原样使用 prompt，绕过聊天模板及 `--no-thinking` 的自动处理。因此必须手工写入 `<|im_start|>`、`<|im_end|>` 以及空的 `<think>\n\n</think>` 块。命令中的 `--no-thinking` 在 raw 模式下是冗余参数；保留它不改变输入，真正关闭 thinking 的是手工写入的空 thinking 块



### 6.3 困惑度（PPL）测试（非必要检查）

- **困惑度（PPL）**：语言模型的核心指标，越低越好
- 量化前（原始 bfloat16）vs 量化后（W4A16）的 PPL 对比
- 判断量化损失是否在可接受范围（通常 PPL 上升 < 5% 算优秀）

```
Evaluate:
export HF_ENDPOINT=https://hf-mirror.com
# 因为你用的是**自定义微调后的权重**（汽车领域模型），建议把 `config.json` 复制到量化输出目录：
cp /root/autodl-tmp/work/source_checkpoint/config.json \
   /root/autodl-tmp/qwen3_car_w4a16_cl4096_v19/
   
/root/autodl-tmp/conda-envs/qaihm060/bin/python -m qai_hub_models.models.qwen3_0_6b.evaluate --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v19 --task wikitext
```

**困惑度结果指标：**

| 指标            | 含义                 | 判断标准               |
| --------------- | -------------------- | ---------------------- |
| **perplexity**  | 困惑度，越低越好     | 语言模型能力的核心指标 |
| PPL ≈ 原始模型  | 量化几乎无损         | ✅ 优秀                 |
| PPL 上升 < 10%  | 轻微损失，通常无感知 | ✅ 可接受               |
| PPL 上升 > 30%  | 量化损失明显         | ⚠️ 可能需要调整校准参数 |
| PPL = NaN / inf | 量化严重出错         | ❌ 必须排查             |

## 7. 配置 AI Hub

当前 AutoDL 已配置 AI Hub Token，并已验证可以列出设备。正常情况下只需执行设备检查：

```bash
/root/autodl-tmp/conda-envs/qaihm060/bin/qai-hub list-devices | grep -i 'SA8775'
```

只有 Token 失效或更换账号时，才重新配置：

```bash
/root/autodl-tmp/conda-envs/qaihm060/bin/qai-hub configure --api_token '<YOUR_QAI_HUB_API_TOKEN>'
```

不要把 Token 写入文档、脚本、日志或 Git 仓库。设备名称必须以 `list-devices` 返回值为准；本指南按原流程使用 `SA8775P ADP`。

## 8. AI Hub 编译 GenieX-QAIRT

先确认当前 `qai-hub-models` 对 Qwen3-0.6B 开放了 `geniex_qairt`：

```bash
/root/autodl-tmp/conda-envs/qaihm060/bin/qai-hub-models export qwen3_0_6b --help
```

如果帮助中包含下列选项，执行：

```bash
mkdir -p /root/autodl-tmp/output/export_sa8775p_multictx /root/autodl-tmp/logs
cd /root/autodl-tmp/ai-hub-models-0.60.0
set -o pipefail
env \
  TMPDIR=/root/autodl-tmp/tmp \
  TMP=/root/autodl-tmp/tmp \
  HF_HOME=/root/autodl-tmp/hf-cache \
  QAIHM_STORE_ROOT=/root/autodl-tmp/qaihm-store \
  HF_ENDPOINT=https://hf-mirror.com \
  HF_HUB_DISABLE_XET=1 \
  QAIHM_CLI_VERBOSE_EXCEPTIONS=1 \
  /root/autodl-tmp/conda-envs/qaihm062/bin/python -m qai_hub_models.models.qwen3_0_6b.export \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v23_062 \
  --target-runtime geniex_qairt \
  --device "SA8775P ADP" \
  --device-os 14 \
  --sequence-lengths 128,1 \
  --context-lengths 512 \
  --skip-profiling \
  --output-dir /root/autodl-tmp/output/export_sa8775p_ctx512_v23_062 \
  --zip-assets \
  2>&1 | tee /root/autodl-tmp/logs/export_sa8775p_ctx512_v23_062.log
  
# 编译8295
mkdir -p /root/autodl-tmp/output/export_sa8775p_multictx /root/autodl-tmp/logs
cd /root/autodl-tmp/ai-hub-models-0.60.0
set -o pipefail
env \
  TMPDIR=/root/autodl-tmp/tmp \
  TMP=/root/autodl-tmp/tmp \
  HF_HOME=/root/autodl-tmp/hf-cache \
  QAIHM_STORE_ROOT=/root/autodl-tmp/qaihm-store \
  HF_ENDPOINT=https://hf-mirror.com \
  HF_HUB_DISABLE_XET=1 \
  QAIHM_CLI_VERBOSE_EXCEPTIONS=1 \
  /root/autodl-tmp/conda-envs/qaihm060/bin/python -m qai_hub_models.models.qwen3_0_6b.export \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v23 \
  --target-runtime geniex_qairt \
  --device "SA8295P ADP" \
  --device-os 14 \
  --sequence-lengths 128,1 \
  --context-lengths 512 \
  --skip-profiling \
  --output-dir /root/autodl-tmp/output/export_sa8295p_ctx512_v23_01 \
  --zip-assets \
  2>&1 | tee /root/autodl-tmp/logs/export_ssa8295p_ctx512_v23_01.log

# 062版本编译命令
set -o pipefail
env \
  TMPDIR=/root/autodl-tmp/tmp \
  TMP=/root/autodl-tmp/tmp \
  HF_HOME=/root/autodl-tmp/hf-cache \
  QAIHM_STORE_ROOT=/root/autodl-tmp/qaihm-store \
  HF_ENDPOINT=https://hf-mirror.com \
  HF_HUB_DISABLE_XET=1 \
  QAIHM_CLI_VERBOSE_EXCEPTIONS=1 \
  /root/autodl-tmp/conda-envs/qaihm062/bin/python -m qai_hub_models.models.qwen3_0_6b.export \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v23_062 \
  --target-runtime geniex_qairt \
  --device "SA8775P ADP" \
  --device-os 14 \
  --sequence-lengths 128,1 \
  --context-lengths 512 \
  --skip-profiling \
  --output-dir /root/autodl-tmp/output/export_sa8775p_ctx512_v23_062 \
  --zip-assets \
  2>&1 | tee /root/autodl-tmp/logs/export_sa8775p_ctx512_v23_062.log
```

说明：

- export 会读取完整量化 checkpoint、拆分模型、上传各 Part，在 AI Hub 云端完成针对目标 HTP 的编译和链接，然后下载/组织部署产物。
- 当前多上下文配置支持 `256,512,1024,2048,3072,4096`。每增加一个 context length 都会增加云端编译任务和耗时；只需固定 4096 时可改为 `--context-lengths 4096`，需要覆盖短上下文并自动选择图时保留多档配置。
- 若当前版本的 `--help` 没有 `--device-os`、`--sequence-lengths` 或 `--context-lengths`，删除不支持的参数；CLI 帮助是最终依据。
- 除非只想提交任务而暂不下载，不要添加 `--skip-downloading`，否则部分版本不会生成完整本地配置和附属文件。

命令在当前 AutoDL 终端前台运行，日志会实时显示并保存。结束后可检查日志末尾；AI Hub 云端 job 的详细状态也可以登录 Workbench 查看：

```bash
tail -n 100 /root/autodl-tmp/logs/export_sa8775p_multictx.log
```

## 9. 打包并下载到 Windows

在 AutoDL 中执行：

```bash
cd /root/autodl-tmp
tar -czf qwen3_0.6b_sa8775_geniex_qairt_multictx.tar.gz output/export_sa8775p_multictx
```

在 Windows PowerShell 中执行：

```powershell
$AutoDLHost = "root@xxx.com"
$AutoDLPort = 实际autodl端口

scp -P $AutoDLPort "${AutoDLHost}:/root/autodl-tmp/qwen3_0.6b_sa8775_geniex_qairt_multictx.tar.gz" `
  "E:\QualComm\ai-hub-compiles\"
# 解压  
tar -xf "E:\QualComm\ai-hub-compiles\qwen3_0.6b_sa8775_geniex_qairt_multictx.tar.gz" -C "E:\QualComm\ai-hub-compiles\"
```

解压后，检查编译产物、Tokenizer、模型配置及 GenieX-QAIRT 所需 JSON 配置是否齐全，再按 GenieX-QAIRT 示例工程的目录结构放入应用 assets 或设备模型目录。



## 10. 问题汇总：

### 问题1：量化后准确率很低

原因1：完全照搬官方发布的量化模型过程中使用的参数

```
① SpinQuant r2,r3  → 旋转权重矩阵（QuantSim 创建之前的浮点图前置变换），新增参数
② Sequential MSE   → 逐层优化舍入：权重值"怎么四舍五入"，新增参数
③ AdaScale         → 逐块学习截断 scale：权重分布"在哪里切"
④ QuantSim 校准    → 统计激活 encodings
```

**1. 缺 SpinQuant r2,r3（影响最大）**

- SpinQuant 在浮点图上插入正交旋转矩阵，把权重**异常大值（outlier）摊平**，直接降低后面 4-bit 量化的截断误差。W4 每个值只有 16 个档位，一个 outlier 就会把整组 scale 撑大，让其余权重全部粗粒度化。
- 附录 A.2 里明确写了推荐值是**分模型的**：`qwen3-0.6b 推荐 r2,r3`（1.7B 才推荐 r1,r3）。第一条命令用的正是 0.6B 的推荐组合；第二条完全没开。对B 这种小模型，参数冗余度低、outlier 影响占比更大，开不开 SpinQuant 的差距会比 1.7B 更明显。
- 执行顺序上它在 QuantSim 创建**之前**改变权重分布，后面 SeqMSE、AdaScale、校准全是在"旋转后更好量化"的分布上做的——是地基性的收益。

**2. 缺 Sequential MSE（12 samples / 24 batches）**

- 不开 SeqMSE 时权重就是普通 round-to-nearest 四舍五入，只保证"每个权重离原值近"，不管"这一层输出对不对"。SeqMSE 逐层贪婪搜索最优舍入组合，选输出 MSE 最小的方案。
- AdaScale 学的是 scale（"在哪里切"），SeqMSE 改的是 rounding（"怎么舍"），两者叠加互补——缺了任何一个，误差都会在 28 层（0.6B）逐层累积放大。文档里已有前车之：早期缺 AdaScale 的产物直接在 PC 和车机上输出乱码，你这次是缺 SeqMSE + SpinQuant，属于同一类问题，只是程度轻些（准确率下降而非乱码）。

**原因2**：校准用的语料，采用官方发布的校准语料（GeneratedDataset）和wikiText语料1:1混插

```java
                        ┌─ WikiText (train) ──────────────┐
 AdaScale 权重优化 ──────┤  纯 WikiText                     │
 (2048 次梯度迭代)        └─────────────────────────────────┘

                        ┌─ GeneratedDataset（本目录 txt）──┐
 最终校准（定标）────────┤  1:1 插混                         │
 (InterleavedGenerated)  └─ WikiText (train) ───────────────┘
```

- **assistant 回答用"人工校正版本"的 JSON**，让量化器见到真实的输出 token 分布（大写枚举、嵌套 slot、花括号）——这是 GeneratedDataset完全覆盖不到、也恰恰是板上输出跑偏的部分；

**解决办法：**

1，量化过程,新增如下参数： 

```
--use-spin-quant r2,r3 \
--use-seq-mse \
--seq-mse-num-samples 12 \
```

2，修改量化校准数据集

```
# ① 备份并替换掉官方语料校准集，改为我们微调模型时校准集（最终校准的域内一半立即生效）
cp .../v1/generated_calibration.txt .../v1/generated_calibration.txt.bak
cp car_corpus.txt .../v1/generated_calibration.txt
# ③ 重跑量化，然后重新 export → 推板验证
```

**① 校准和权重优化，两个数据源各用多少样本**

以 v3 那次运行（`--num-samples 20`、`--ada-scale-num-samples 64`）的实际日志为准：

| 校准环节               | 数据集构成                                                   | 样本量                             | 日志进度条                                            |
| :--------------------- | :----------------------------------------------------------- | :--------------------------------- | :---------------------------------------------------- |
| 最终校准（定激活范围） | `GeneratedDataset` + `WikiText` 各一半（代码：`per_source = num_samples // 2` 轮转交织） | **24 条**（12 生成 + 12 wikitext） | `Pre-filling (interleaved_generated_wikitext): 24/24` |
| AdaScale 权重优化      | 纯 `WikiText`                                                | **24条**                           | `Pre-filling (wikitext): 83/96` ← 崩在这              |

合计 120 条 × 每条约 900MB KV cache ≈ 110G 磁盘，就是之前算的那笔账。

⚠️ 注意你这次手动执行的脚本把 `--ada-scale-num-samples` 从 64 改成了 **48**，所以这轮 AdaScale 的样本量和磁盘需求会比 v3 小一些。

**② 为什么权重优化原本用纯 WikiText**

这是**上游配方的设计选择，不是技术必需**。`dataset.py` 开头的注释写明了来历：

> *"Vendored from Qualcomm GenAI Lab (aimet: GenAILab/bench/prompts/calibration_prompts.yaml)"*

这套校准配方是高通为**通用聊天模型**设计的：

1. **WikiText 是"通用语言统计"的安全默认值**——长篇幅、连贯、词汇分布广的英文自然文本。AdaScale 要做的是估计每个权重的重要性和最优舍入方式，需要稳定、多样的激活统计；在你不知道模型部署到哪个域时，wikitext 是最不容易出错的通用代理分布。
2. **GeneratedDataset 解决的是另一个问题**——让模型自生成文本做校准（"output generated from the input"），缩小校准分布与模型**自身输出分布**的偏移，所以只放在最终校准那一半里。
3. **但对你的场景这个默认值是错的**：你的模型是窄域微调的车控意图提取器，线上激活分布（多语言短指令 + 严格 JSON 输出）和英文维基百科完全两个世界。AdaScale 按 wikitext 的激活去优化权重舍入方向，优化目标本身就偏了——这正是我们把 `get_weight_optimization_data` 里的 `WikiText` 改成 `GeneratedDataset`（车控语料）的原因。

一句话：**纯 WikiText 是通用模型的出厂默认，不是对你的模型正确。** 改成域内语料后，权重优化（int4 舍入）和激活定标（uint16/uint8 范围）就都对准车控分布了。

### 问题2：**模拟量化准确率提高了，但真实量化准确率很低**

真实量化准确率才5%，模拟量化达到90以上%

**原因1**：必须是**与线上一模一样的 ChatML 原文**，每条 = 完整一轮对话（system + user + assistant 的 JSON 回答），多条顺序拼接成一个大文本：

```

<|im_start|>system
You are a multilingual vehicle control assistant. Extract intent and slots from user commands in Vietnamese or Malay. Always respond with English JSON only. Do not think or explain.<|im_end|>
<|im_start|>user
close the sunroof<|im_end|>
<|im_start|>assistant
----多了下面的think
<think> 

</think>

{"intent":"CLOSE_SUNROOF","slot":{}}<|im_end|>
<|im_start|>system
You are a multilingual vehicle control assistant. ...<|im_end|>
<|im_start|>user
open the window<|im_end|>
...
```

**原因2**：`down_proj.weight`这个值是INT4，不是INT8。这个值的SQNR（db）值开始出现偏差。

| Layer 2 tap | SQNR (dB) |   cosine | max abs diff | 判断                                |
| ----------- | --------: | -------: | -----------: | ----------------------------------- |
| `o_proj`    |    56.447 | 0.999519 |      0.03494 | 正常                                |
| `gate_up`   |    53.742 | 0.987374 |      0.79241 | 基本正常，是 `down_proj` 的输入边界 |
| `down`      |    24.123 | 0.536302 |      2.63549 | 首个严重分歧点                      |
| `residual`  |    32.606 | 0.867635 |      2.63551 | 继承 `down` 误差                    |

字段含义：

SQNR = 云端 QNN 输出相对于本地 QuantSim 输出的误差
cosine = 云端 QNN 输出与 QuantSim 输出的方向相似度



源码明确把以下权重声明为 INT8 质量例外：

```python
INT8_PARAM_NAMES = ("model.model.layers.2.mlp.down_proj.weight",)
```

源码注释说明，该权重保持 INT4 会使 100-prompt grader 的绝对分数下降约 13%。但当前 v17 `model.encodings` 中该权重实际为 W4，而不是 W8。其 initializer shape 为 `[1024, 3072, 1, 1]`，当前 W4 encoding 为 `PER_CHANNEL`、1024 个 scale、对称 offset `-8`；编码数量与 Conv 输出通道轴 0 一致，所以不是 encoding 缺失或 scale 数量错误。

v17 Layer 2 同时存在极端 A16 激活范围：

| 边界       |        scale | offset |        encoding 隐含范围 |
| ---------- | -----------: | -----: | -----------------------: |
| `gate_up`  | 0.1132012668 | -29764 | 约 `[-3369.32, 4049.32]` |
| `down`     | 0.1254994601 |  -1492 |  约 `[-187.25, 8037.36]` |
| `residual` | 0.1255005695 |  -1500 |  约 `[-188.25, 8036.43]` |

同一 v17 内，Layer 1/3 的 `down` scale 分别为 `0.0001739506` 和 `0.0000950938`；Layer 2 分别放大约 721 倍和 1320 倍。工程结论是：v17 的首个主要错误点为 Layer 2 MLP `down_proj`，其 W8 质量例外在 checkpoint 中丢失，并伴随异常激活范围；QNN 在该 Conv 的定点累加/输出重定标处与 QuantSim 显著分歧。

修复脚本中漏洞后验证：执行命令

```
/root/autodl-tmp/conda-envs/qaihm060/bin/python \
  /root/autodl-tmp/tools/validate_qwen3_v17_v18.py \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v21 \
  --version v21
```

| Layer 2 tap | SQNR (dB) |   cosine | max abs diff | 判断                                   |
| ----------- | --------: | -------: | -----------: | -------------------------------------- |
| `o_proj`    |    56.447 | 0.999519 |      0.03494 | 正常                                   |
| `gate_up`   |    53.742 | 0.987374 |      0.79241 | 基本正常，max abs diff 略偏大          |
| `down`      |    35.616 | 0.943081 |      0.37064 | 基本正常，SQNR (dB) 稍微偏小           |
| `residual`  |    43.903 | 0.989657 |      0.49424 | 误差有所恢复，但仍继承 `down` 部分偏差 |



## 11. 部署前检查清单

- AI Hub 目标设备确认为 `SA8775P ADP` 或平台实际返回的 SA8775 ADP 名称。
- 源模型是完整 Qwen3-0.6B checkpoint，不是未合并的 LoRA adapter。
- 量化参数基于官方 AdaScale 配方，但将 AdaScale 样本数调整为 32；使用 2048 次迭代和 20 个基础样本。
- 量化上下文长度为 4096，校准序列长度为 2048。
- 量化 smoke test 输出正常、无乱码。
- `model_dynamic.onnx`、`model.data`、`model.encodings` 未分离。
- export 使用 `--target-runtime geniex_qairt`，而不是普通 `qnn_context_binary` 或 `qnn_dlc`。
- 设备侧 QAIRT/GenieX runtime 库版本与编译产物兼容。
- HTP 后端配置中的 SoC/DSP 架构与 SA8775 一致。

## 常见问题

**系统盘写满**：确认 `TMPDIR`、`HF_HOME`、`QAIHM_STORE_ROOT` 和输出目录都位于 `/root/autodl-tmp`。

**Hugging Face 下载失败或 xet 报 401**：设置 `HF_ENDPOINT=https://hf-mirror.com` 和 `HF_HUB_DISABLE_XET=1`。

**量化只使用 CPU**：检查 ONNX Runtime Provider，确保包含 `CUDAExecutionProvider`。

**AI Hub 找不到设备**：用 `qai-hub list-devices` 查实际设备名，不要自行猜测设备字符串。

**`geniex_qairt` 不在帮助列表中**：当前 ai-hub-models 版本不支持该模型/运行时组合，需要切换到包含 Qwen3-0.6B GenieX-QAIRT export 的匹配源码版本；不能用普通 QNN ONNX 转换流程替代 GenieX LLM 编译。

**本地 QNN SDK 已生成 context binary，但 GenieX 无法加载**：普通 `qnn-context-binary-generator` 产物不等同于 AI Hub 为 Genie/GenieX 生成的 LLM 产物，图 IO、KV Cache 和配置契约可能不兼容。