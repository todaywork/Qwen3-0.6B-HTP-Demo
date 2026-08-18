# Qwen3-0.6B QuantSim 与 QNN 端侧差异逐步排查指南

## 1. 目的和适用范围

本文用于排查以下现象：

- AIMET ONNX QuantSim 模拟量化测试 20 条语料，准确率约 90%；
- 同一模型经 QAIRT/QNN 编译为端侧模型后，准确率约 5%；
- 量化配置为 W4A16，序列长度 2048、上下文长度 4096；
- 当前问题 checkpoint 为 `/root/autodl-tmp/qwen3_car_w4a16_cl4096_v17`，修复候选版本固定为同路径规则下的 `v18`；
- 使用 `ai-hub-models 0.60.0` 的 `scripts/llm/sim_vs_device` 工具定位差异。

版本范围约束：后续诊断、修复和验收只允许使用当前 `v17` 或新生成的 `v18`。不得使用 v3 或其他历史 checkpoint 作为数值对照、替代输入或通过依据；不得把历史版本的 AI Hub job、`.bin`、encodings 或缓存模型混入 v17/v18 结论。

排查目标不是先假设“权重已经无关”，而是用证据回答三个问题：

1. QuantSim 和生产端侧是否确实使用同一 checkpoint、编码文件、Tokenizer、输入 Token 和生成参数？
2. 相同 golden 输入下，QNN 从哪个 Part、哪一层、哪个投影边界开始偏离 QuantSim？
3. 如果 Prefill 的每层计算均一致，问题是否位于 Decode/KV Cache、Genie bundle、I/O metadata、采样或准确率测试程序？

## 2. 工具能力边界

`sim_vs_device` 会从已量化 checkpoint 建立 QuantSim golden，拆出每个 Transformer 层，将 golden 边界输入分别注入本地和设备子图，然后比较对应中间张量。

它能够定位：

- Part 或 Transformer 层；
- Attention `o_proj`；
- MLP `gate_up`；
- MLP `down_proj`；
- 每层最终 `residual`；
- SQNR、余弦相似度和最大绝对误差。

它目前不能直接验证：

- 完整的自回归 Decode 循环；
- 多轮 KV Cache 更新和回读；
- Genie bundle 中的模型连接、metadata 和配置文件是否正确；
- Tokenizer/chat template 是否一致；
- 温度、Top-K、EOS 等采样设置；
- 20 条车控语料的最终文本准确率。

因此，“逐层检查全部正常”只代表 **Prefill 核心计算基本一致**，不能直接证明完整端侧生成链路正常。

## 3. 本模型的特殊情况

当前 0.60.0 源码中，Qwen3-0.6B 的结构参数是：

```text
NUM_LAYERS = 28
NUM_SPLITS = 2
NUM_LAYERS_PER_SPLIT = 28
```

对应关系为：

- `part1_of_2`：Embedding，无 Decoder 层；
- `part2_of_2`：全部 28 个 Transformer 层和 LM Head。

Phase A 默认跳过 Embedding Part，也会跳过带 LM Head 的最后一个 Part。因此对这个两 Part 模型，Phase A 很可能没有可测试的 Transformer Part，输出 `inconclusive` 是工具结构导致的，不代表端侧正常或异常。

本模型应先进行本地 tap 检查，然后直接执行：

```text
Phase B → part2_of_2 → 28 层逐层检查
```

## 4. 总体排查顺序

| 步骤 | 验证内容 | 通过条件 | 当前状态 |
| --- | --- | --- | --- |
| 0 | 固定环境与证据目录 | 后续日志、JSON、版本信息都有固定保存位置 | v17 已完成 |
| 1 | Python、源码和 AIMET 版本一致性 | 实际导入的是 0.60.0 源码，不混用未知版本 | v17 已完成 |
| 2 | Checkpoint 和 W4A16 编码确认 | 参数编码主要为 4 bit，激活编码为 16 bit | v17 已完成，发现 Layer 2 W8 例外丢失 |
| 3 | 20 条语料测试口径对齐 | 两端输入 Token 和生成参数完全相同 | v17 已完成：QuantSim 20/20、端侧 0/20 |
| 4 | 本地 tap 发现检查 | 28 层均发现预期 tap，无关键编码缺失 | v17 已完成：112 taps、0 dropped |
| 5 | 本地逐层拆图检查 | 28 个子图的输入、输出和 tap 完整 | v17 已完成 |
| 6 | Phase B 端侧逐层比较 | 获得 28 层 SQNR/余弦结果和首个异常点 | v17 已完成：首个异常为 Layer 2 `down` |
| 7 | 根据首个异常点缩小原因 | 定位到 Attention、MLP、Residual 或非 Prefill 链路 | v17 已完成：Layer 2 `down_proj` 量化/重定标路径 |
| 8 | 修复后回归 | 逐层对比改善，20 条语料恢复到目标准确率 | 等待 v18 |

执行原则：每一步保存证据；当前步骤未通过时，不进入后续大规模云端编译。

## 5. 步骤 0：固定目录和环境变量

以下命令在 AutoDL Linux 环境执行。先核对路径，再逐行执行：

```bash
set -o pipefail

export MODEL_VERSION=v17  # 只允许 v17 或 v18
export QAIHM_REPO=/root/autodl-tmp/ai-hub-models-0.60.0
export QAIHM_CKPT=/root/autodl-tmp/qwen3_car_w4a16_cl4096_${MODEL_VERSION}
export QAIHM_PY=/root/autodl-tmp/conda-envs/qaihm060/bin/python
export DEVICE_NAME='SA8775P ADP'
export DEBUG_ROOT=/root/autodl-tmp/logs/sim_vs_device_qwen3_0_6b_${MODEL_VERSION}

export TMPDIR=/root/autodl-tmp/tmp
export TMP=/root/autodl-tmp/tmp
export HF_HOME=/root/autodl-tmp/hf-cache
export QAIHM_STORE_ROOT=/root/autodl-tmp/qaihm-store
export HF_ENDPOINT=https://hf-mirror.com
export HF_HUB_DISABLE_XET=1
export PYTHONPATH="$QAIHM_REPO/src:$QAIHM_REPO/cli${PYTHONPATH:+:$PYTHONPATH}"

mkdir -p "$DEBUG_ROOT" "$TMPDIR" "$HF_HOME" "$QAIHM_STORE_ROOT"
```

说明：当前验证使用独立的 `qaihm060` 环境，并同时固定 0.60.0 的 `src` 和 `cli`。不得再使用 `qaihm059` Python 与 0.60.0 源码混跑。切换到 v18 时只修改 `MODEL_VERSION`，日志目录必须随版本切换，避免覆盖 v17 证据。

检查路径是否存在：

```bash
test -x "$QAIHM_PY" && echo 'Python: OK' || echo 'Python: MISSING'
test -d "$QAIHM_REPO" && echo 'Repo: OK' || echo 'Repo: MISSING'
test -d "$QAIHM_CKPT" && echo 'Checkpoint: OK' || echo 'Checkpoint: MISSING'
```

任一项显示 `MISSING` 时停止，先修正路径。

## 6. 步骤 1：确认实际运行的源码和版本

### 6.1 保存 Git 信息

```bash
cd "$QAIHM_REPO"
{
  git status --short
  git rev-parse HEAD
  git log -1 --oneline
  git remote -v
} | tee "$DEBUG_ROOT/01_git_info.txt"
```

### 6.2 查询 AIMET、QAI Hub 和 ai-hub-models 版本

```bash
"$QAIHM_PY" - <<'PY' | tee "$DEBUG_ROOT/01_python_versions.txt"
import importlib
import importlib.metadata as md
import sys

print('python:', sys.version)
for name in (
    'qai-hub-models',
    'qai-hub',
    'aimet-onnx',
    'aimet-torch',
    'aimet-common',
    'onnx',
    'onnxruntime',
):
    try:
        print(f'{name}: {md.version(name)}')
    except md.PackageNotFoundError:
        print(f'{name}: NOT INSTALLED AS A DISTRIBUTION')

for module_name in ('qai_hub_models', 'qai_hub', 'aimet_onnx', 'aimet_common'):
    try:
        module = importlib.import_module(module_name)
        print(f'{module_name}.__file__: {getattr(module, "__file__", None)}')
        print(f'{module_name}.__version__: {getattr(module, "__version__", None)}')
    except Exception as exc:
        print(f'{module_name}: IMPORT FAILED: {type(exc).__name__}: {exc}')
PY
```

通过条件：

- `qai_hub_models.__file__` 指向 `/root/autodl-tmp/ai-hub-models-0.60.0/src/...`；
- `aimet_onnx` 可以导入；
- 不存在另一个 0.59.0 源码目录排在 `PYTHONPATH` 前面；
- 本次记录的环境与生成 checkpoint 时的环境差异明确可追溯。

如果模块路径仍指向 `site-packages/qai_hub_models` 或 0.59.0 源码，停止执行 Phase B，先解决环境混用。

## 7. 步骤 2：确认 checkpoint、编码和 W4A16

### 7.1 保存文件清单和哈希

```bash
find "$QAIHM_CKPT" -maxdepth 3 -type f -printf '%P\t%s bytes\n' \
  | sort | tee "$DEBUG_ROOT/02_checkpoint_files.txt"

find "$QAIHM_CKPT" -maxdepth 3 -type f \
  \( -name '*.onnx' -o -name '*.data' -o -iname '*encoding*' \) \
  -print0 | sort -z | xargs -0 sha256sum \
  | tee "$DEBUG_ROOT/02_checkpoint_sha256.txt"
```

这些哈希以后必须与导出生产 `.bin` 所使用的 checkpoint 对齐。仅凭目录名相同不能证明文件相同。

### 7.2 统计编码位宽

```bash
"$QAIHM_PY" - <<'PY' | tee "$DEBUG_ROOT/02_encoding_summary.txt"
import collections
import json
import os
from pathlib import Path

root = Path(os.environ['QAIHM_CKPT'])
candidates = [
    p for p in root.rglob('*')
    if p.is_file() and ('encoding' in p.name.lower() or p.suffix == '.encodings')
]

def collect_widths(obj):
    widths = []
    if isinstance(obj, dict):
        for key in ('bitwidth', 'bw'):
            value = obj.get(key)
            if isinstance(value, (int, float)):
                widths.append(int(value))
        for value in obj.values():
            widths.extend(collect_widths(value))
    elif isinstance(obj, list):
        for value in obj:
            widths.extend(collect_widths(value))
    return widths

print('encoding candidates:', len(candidates))
for path in sorted(candidates):
    try:
        data = json.loads(path.read_text())
    except Exception as exc:
        print(path, 'JSON READ FAILED:', exc)
        continue
    param = collect_widths(data.get('param_encodings', {}))
    act = collect_widths(data.get('activation_encodings', {}))
    print('\nfile:', path)
    print('param bitwidth counts:', dict(collections.Counter(param)))
    print('activation bitwidth counts:', dict(collections.Counter(act)))
PY
```

预期：

- 量化权重的 `param_encodings` 主要为 4 bit；
- 激活 `activation_encodings` 为 16 bit；
- 如果出现多个 encoding 文件，必须确认实际 ONNX bundle 引用的是哪一个；
- 如果出现 8 bit 或未编码参数，先确认它是否是有意保留的算子、Bias 或非量化参数，不要只看总计数下结论。

### 7.3 正确认识 `model.data` 大小

QuantSim checkpoint 中的 `model.data` 可能仍以浮点 ONNX external data 形式保存“模拟量化后的反量化近似值”，并同时保存 scale、offset、bitwidth 等编码信息。因此 2.8 GB 大于原始 1.1 GB，不能单独证明 W4 失败。

真正 QNN 编译时会根据量化编码将权重转换或打包为 INT4。理想情况下，QuantSim 和 QNN 使用相同的 INT4 离散值及 scale/offset，结果应接近。但以下问题仍可能让权重路径产生差异：

- per-channel scale 使用了错误轴；
- signed/unsigned 或 zero-point 约定不同；
- 权重转置、融合后 scale 没有同步变换；
- 编译时读取了错误或旧的 encodings；
- 实际生产 `.bin` 不是从当前 checkpoint 编译的。

所以在逐层结果出来以前，不能把权重完全排除。

## 8. 步骤 3：先对齐 20 条语料的测试口径

90% 与 5% 的差异只有在两端测试完全同口径时才成立。为每条语料至少保存：

| 字段 | QuantSim | QNN/Genie | 是否一致 |
| --- | --- | --- | --- |
| 原始文本 |  |  |  |
| chat template 后文本 |  |  |  |
| `input_ids` |  |  |  |
| `attention_mask` |  |  |  |
| Tokenizer 文件哈希 |  |  |  |
| `max_new_tokens` |  |  |  |
| `temperature` |  |  |  |
| `top_k`/`top_p` |  |  |  |
| EOS/PAD/BOS ID |  |  |  |
| 首个生成 Token |  |  |  |
| 最终输出 |  |  |  |
| 判分结果 |  |  |  |

建议先固定为确定性生成：

- `do_sample=false`；
- `temperature=0` 或使用纯 argmax；
- 两端相同的 `max_new_tokens`；
- 相同 system prompt 和 chat template；
- 相同停止词、EOS 和后处理规则。

优先比较“首个生成 Token”，再比较完整文本：

- 如果输入 Token 已不同：根因在 Tokenizer/chat template/输入预处理，不应继续怀疑量化；
- 如果第一个 Token 就不同：重点查 Prefill logits、LM Head 或 Prefill 最后一层输出；
- 如果前几个 Token 相同，随后开始分叉：重点查 Decode、KV Cache 更新、position ID 和采样；
- 如果原始 Token 输出相同但准确率判定不同：重点查文本后处理或评分程序。

将两端原始日志分别保存到：

```text
$DEBUG_ROOT/03_quantsim_20_cases.log
$DEBUG_ROOT/03_device_20_cases.log
```

## 9. 步骤 4：本地检查 tap 是否完整

该步骤不提交 AI Hub 任务，不产生设备费用，应在 Phase B 前完成。

```bash
cd "$QAIHM_REPO/scripts/llm/sim_vs_device"

"$QAIHM_PY" inspect_taps.py \
  --model-id qwen3_0_6b \
  --checkpoint "$QAIHM_CKPT" \
  --precision w4a16 \
  --part part2_of_2 \
  --sequence-length 2048 \
  --context-length 4096 \
  2>&1 | tee "$DEBUG_ROOT/04_inspect_taps.log"
```

通过条件：

- 发现全局 Layer 0～27；
- 每层应尽量包含 `residual`、`o_proj`、`gate_up`、`down`；
- 没有 `No encoded projection taps found`；
- `dropped` 数量和原因可解释，不能缺失整列或连续多层。

如果某一层缺 tap，先检查对应输出是否拥有 INT activation encoding。工具为了使用 `--quantize_io`，会主动丢弃没有激活编码的候选 tensor。

## 10. 步骤 5：本地验证 28 个逐层子图

```bash
cd "$QAIHM_REPO/scripts/llm/sim_vs_device"

"$QAIHM_PY" inspect_taps.py \
  --model-id qwen3_0_6b \
  --checkpoint "$QAIHM_CKPT" \
  --precision w4a16 \
  --part part2_of_2 \
  --sequence-length 2048 \
  --context-length 4096 \
  --split-layers \
  2>&1 | tee "$DEBUG_ROOT/05_split_layers.log"
```

通过条件：

- 日志显示 `split into 28 per-layer sub-graphs`；
- 每层有上一层 golden residual 输入；
- attention mask、position cos/sin 和本层 KV 输入完整；
- 每层 taps 至少覆盖 residual、o_proj、gate_up、down；
- 本地拆图过程中没有 shape inference、encodings 或 external data 错误。

如果该步骤失败，不要提交 28 个云端编译任务。可针对异常层打印底层 ONNX 结构：

```bash
"$QAIHM_PY" inspect_taps.py \
  --model-id qwen3_0_6b \
  --checkpoint "$QAIHM_CKPT" \
  --precision w4a16 \
  --part part2_of_2 \
  --dump-layer 需要检查的层号 \
  --sequence-length 2048 \
  --context-length 4096 \
  2>&1 | tee "$DEBUG_ROOT/05_dump_layer_N.log"
```

## 11. 步骤 6：执行端侧逐层 Phase B

### 11.1 推荐命令

Qwen3-0.6B 的 Phase A 会跳过两个 Part，因此推荐显式跳过 Phase A，直接检查 `part2_of_2`：

```bash
cd "$QAIHM_REPO/scripts/llm/sim_vs_device"

"$QAIHM_PY" run.py \
  --model-id qwen3_0_6b \
  --checkpoint "$QAIHM_CKPT" \
  --device "$DEVICE_NAME" \
  --precision w4a16 \
  --sequence-length 2048 \
  --context-length 4096 \
  --num-windows 1 \
  --dataset wikitext \
  --skip-phase-a \
  --phase-b force \
  --phase-b-part part2_of_2 \
  --model-cache-mode disable \
  --report-json "$DEBUG_ROOT/06_phase_b.json" \
  2>&1 | tee "$DEBUG_ROOT/06_phase_b.log"
```

最终 Phase B JSON 的实际文件名为：

```text
$DEBUG_ROOT/06_phase_b_tap_per_layer.json
```

原命令中的 `--phase-b all` 对该模型也会检查这 28 层，但会先走一次没有实际 Part 可测的 Phase A。使用上面的命令意图更清晰。

### 11.2 数据集说明

当前工具的 `dataset.py` 只注册了 `wikitext`。它适合首先判断 QNN 与 QuantSim 是否存在结构性差异，但不是那 20 条车控语料。

如果 WikiText 逐层正常，而问题只在车控语料复现，应新增一个 `DatasetSource`，让工具直接输出与车控 prompt 相同的 `(input_ids, attention_mask)` 窗口，再重复 Phase B。不要直接传入未注册的 `--dataset car`，否则会报 `Unknown dataset source`。

### 11.3 预计耗时和任务数

- 28 个逐层 compile job，并行提交；
- 约 28 个逐层 inference job，并行提交；
- 通常约 1～2 小时；
- 队列空闲约 40～60 分钟；
- 队列拥堵可能 2～4 小时以上。

日志出现以下内容表示进入云端并行编译：

```text
[Phase B] compiling 28 layer sub-graphs (parallel) ...
```

首次运行保持 `--model-cache-mode disable`。工具的模型缓存按名称而不是内容识别；调试期间启用缓存可能错误复用旧模型。

## 12. 步骤 7：解释 Phase B 结果

输出表的顺序是：

```text
layer      o_proj     gate_up        down    residual
```

含义：

- `o_proj`：Attention 输出投影；
- `gate_up`：SwiGLU 门控 MLP 内部结果；
- `down`：MLP down projection 输出；
- `residual`：本层最终残差输出。

指标解释：

- SQNR 越高越接近；
- 40 dB 大致对应约 1% 相对误差；
- 20 dB 大致对应约 10% 相对误差；
- 余弦相似度越接近 1 越好；
- `*` 表示该单元格比整个表的健康中位数低至少 12 dB，并非简单的绝对阈值。

示例：

```text
 layer      o_proj     gate_up        down    residual
     0        42.1        41.8        40.9        40.5
     1        41.7        41.3        18.2*       17.6*
     2        42.0        40.8        16.9*       16.1*
```

这个结果表示 `o_proj` 和 `gate_up` 仍正常，差异从 `down_proj` 输出开始进入，并影响 residual。优先检查 down projection 权重编码、scale 轴、编译器融合、累加和重定标。

### 12.1 结果模式与优先检查项

| 最早异常位置 | 优先检查 |
| --- | --- |
| `o_proj` | Q/K/V、RoPE、mask、KV 读取、attention 累加、o_proj 权重或输出编码 |
| `gate_up` | gate/up 权重编码、SiLU、Mul、A16 激活范围和 clipping |
| `down` | down_proj INT4 权重打包、per-channel axis、累加精度、输出重定标 |
| 只有 `residual` | Add 前后 encoding、输入输出 scale、量化 I/O、融合 Add |
| 同一列从所有层都低 | 每个 Block 重复的共享算子或统一编码规则问题 |
| 只有个别层突然低 | 该层 encoding、权重 external data、特殊范围或编译变换 |
| 所有层均高且无 crater | Prefill 核心路径基本一致，转查 Decode/KV、LM Head、bundle、Tokenizer 或评测程序 |
| 某层编译或推理失败 | 问题已定位到该层子图，检查不支持算子、I/O encoding、shape 和编译日志 |

注意：Phase B 给每层注入 golden 上游输入，因此它会抑制上游误差累积。某层自身仍显著下降，说明差异更可能发生在该层的传递函数内部，而不是上一层误差传下来。

### 12.2 发现首个异常层后的本地检查

假设首个异常层是 3：

```bash
cd "$QAIHM_REPO/scripts/llm/sim_vs_device"

"$QAIHM_PY" inspect_taps.py \
  --model-id qwen3_0_6b \
  --checkpoint "$QAIHM_CKPT" \
  --precision w4a16 \
  --part part2_of_2 \
  --dump-layer 3 \
  --sequence-length 2048 \
  --context-length 4096 \
  2>&1 | tee "$DEBUG_ROOT/07_dump_layer_3.log"
```

重点核对：

- 异常投影对应的 initializer 名称和 shape；
- 该权重是否存在 param encoding；
- 编码数量是否匹配预期 per-channel 维度；
- scale 所对应的轴是否在 Conv/MatMul 转换或转置后发生变化；
- 输入和输出是否都有 activation encoding；
- QNN 编译日志是否对该算子做了融合、数据布局变换或 dtype 转换。

### 12.3 当前 v17 已确认结论

以下结论只来自当前 v17，不使用任何历史 checkpoint 对照：

| Layer 2 tap | SQNR (dB) | cosine | max abs diff | 判断 |
| --- | ---: | ---: | ---: | --- |
| `o_proj` | 56.447 | 0.999519 | 0.03494 | 正常 |
| `gate_up` | 53.742 | 0.987374 | 0.79241 | 基本正常，是 `down_proj` 的输入边界 |
| `down` | 24.123 | 0.536302 | 2.63549 | 首个严重分歧点 |
| `residual` | 32.606 | 0.867635 | 2.63551 | 继承 `down` 误差 |

源码明确把以下权重声明为 INT8 质量例外：

```python
INT8_PARAM_NAMES = ("model.model.layers.2.mlp.down_proj.weight",)
```

源码注释说明，该权重保持 INT4 会使 100-prompt grader 的绝对分数下降约 13%。但当前 v17 `model.encodings` 中该权重实际为 W4，而不是 W8。其 initializer shape 为 `[1024, 3072, 1, 1]`，当前 W4 encoding 为 `PER_CHANNEL`、1024 个 scale、对称 offset `-8`；编码数量与 Conv 输出通道轴 0 一致，所以不是 encoding 缺失或 scale 数量错误。

v17 Layer 2 同时存在极端 A16 激活范围：

| 边界 | scale | offset | encoding 隐含范围 |
| --- | ---: | ---: | ---: |
| `gate_up` | 0.1132012668 | -29764 | 约 `[-3369.32, 4049.32]` |
| `down` | 0.1254994601 | -1492 | 约 `[-187.25, 8037.36]` |
| `residual` | 0.1255005695 | -1500 | 约 `[-188.25, 8036.43]` |

同一 v17 内，Layer 1/3 的 `down` scale 分别为 `0.0001739506` 和 `0.0000950938`；Layer 2 分别放大约 721 倍和 1320 倍。工程结论是：v17 的首个主要错误点为 Layer 2 MLP `down_proj`，其 W8 质量例外在 checkpoint 中丢失，并伴随异常激活范围；QNN 在该 Conv 的定点累加/输出重定标处与 QuantSim 显著分歧。

### 12.4 v18 生成规则：正式版本不能只手改 encodings

修复候选必须生成到新目录，禁止覆盖 v17：

```bash
export V17_CKPT=/root/autodl-tmp/qwen3_car_w4a16_cl4096_v17
export V18_CKPT=/root/autodl-tmp/qwen3_car_w4a16_cl4096_v18
test ! -e "$V18_CKPT" || {
  echo "v18 目录已存在，先人工确认内容和来源，不允许直接覆盖" >&2
  return 1 2>/dev/null || exit 1
}
```

正式 v18 必须从量化配置重新生成并原子导出以下三件套：

```text
model_dynamic.onnx
model.data
model.encodings
```

要求：

- `model.model.layers.2.mlp.down_proj.weight` 在建立 QuantSim、计算 encoding 之前就设为 W8、symmetric、per-channel；
- 重新运行校准并导出，不能把 v17 的 W4 `model.data` 与手改成 W8 的 `model.encodings` 拼在一起作为正式模型；
- 只做“激活 encoding 假设验证”时，可以复制 v17 后只修改副本的 activation encodings，但该副本只能用于单层诊断，不能发布或编译生产 bundle；
- 不扩大 W8 Transformer 层范围。当前源码唯一显式 W8 Transformer 例外仍是 Layer 2 `down_proj.weight`；LM Head 按 W4A16 公共配置继续保持 W8，Embedding/Norm 继续保持原有 FP16/W16 策略；
- 固定校准语料、样本数、随机种子、sequence/context length、代码 commit 和 Python/AIMET 版本，并写入 manifest；
- v18 不得复用 v17 的模型缓存、compile/link/inference job 或 `.bin`。

### 12.5 v18 静态门禁

#### 12.5.1 推荐入口：Python 一键验证

仓库提供：

```text
scripts/validate_qwen3_v17_v18.py
```

脚本只接受目录名以 `_v17` 或 `_v18` 结尾的 checkpoint，其他版本直接拒绝。默认 `static` 阶段不连接 AI Hub；任何会提交云端任务的阶段都必须显式提供 `--confirm-cloud`。云端阶段仅允许 v18，防止重复提交已经完成定位的 v17。

部署到 AutoDL：

```bash
mkdir -p /root/autodl-tmp/tools
# 从本地工作区执行 scp；端口和主机按当前实例填写
scp -P 15669 scripts/validate_qwen3_v17_v18.py \
  root@connect.nmb1.seetacloud.com:/root/autodl-tmp/tools/
```

只做静态门禁，不产生设备任务：

```bash
"$QAIHM_PY" /root/autodl-tmp/tools/validate_qwen3_v17_v18.py \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v18 \
  --stage static
```

一键执行静态门禁、本地 Layer 2 图检查和 Layer 2 单层 Phase B：

```bash
"$QAIHM_PY" /root/autodl-tmp/tools/validate_qwen3_v17_v18.py \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v18 \
  --stage layer2 \
  --confirm-cloud
```

Layer 2 已通过后，基于已有单层报告执行完整 28 层 Phase B：

```bash
"$QAIHM_PY" /root/autodl-tmp/tools/validate_qwen3_v17_v18.py \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v18 \
  --stage full \
  --report /root/autodl-tmp/logs/sim_vs_device_qwen3_0_6b_v18/02_one_click_v18_layer2_phase_b_tap_per_layer.json \
  --confirm-cloud
```

从静态检查连续执行到完整 28 层：

```bash
"$QAIHM_PY" /root/autodl-tmp/tools/validate_qwen3_v17_v18.py \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v18 \
  --stage all \
  --confirm-cloud
```

`all` 会先提交 Layer 2 的 1 个 compile + 1 个 inference；单层通过后才继续提交28层任务。任何静态断言、SQNR、cosine 或 crater 门禁失败都会立即停止。`all` 最多会新产生29个 compile 和29个 inference job，执行前必须确认设备、网络和 AI Hub 配额。

只判定已有报告，不连接 AI Hub：

```bash
"$QAIHM_PY" /root/autodl-tmp/tools/validate_qwen3_v17_v18.py \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096_v17 \
  --stage evaluate \
  --scope full \
  --report /root/autodl-tmp/logs/sim_vs_device_qwen3_0_6b_v17/08_phase_b_results_tap_per_layer.json
```

脚本自动执行以下保护：

- checkpoint 版本白名单仅为 v17/v18；
- 对 ONNX、`model.data`、encodings 计算 SHA-256；
- 验证 Layer 2 `down_proj` 为 W8 symmetric per-channel、1024 scales、offset `-128`；
- 验证关键 A16 encodings 并报告 Layer 2/其他层 scale 比；
- 云端前清空所有 resume map，固定 `--model-cache-mode disable`；
- 单层过滤补丁不存在时拒绝提交，防止意外发起28层任务；
- 自动解析 Phase B JSON，执行40 dB、cosine 0.99和相对12 dB crater 判定；
- 将静态报告、日志、Phase B JSON和 verdict JSON写入对应版本的 `DEBUG_ROOT`。

#### 12.5.2 手工静态检查（用于审计或脚本故障排查）

先切换环境并记录 checkpoint 哈希：

```bash
export MODEL_VERSION=v18
export QAIHM_CKPT=/root/autodl-tmp/qwen3_car_w4a16_cl4096_v18
export DEBUG_ROOT=/root/autodl-tmp/logs/sim_vs_device_qwen3_0_6b_v18
mkdir -p "$DEBUG_ROOT"

sha256sum \
  "$QAIHM_CKPT/model_dynamic.onnx" \
  "$QAIHM_CKPT/model.data" \
  "$QAIHM_CKPT/model.encodings" \
  | tee "$DEBUG_ROOT/00_v18_checkpoint_sha256.txt"
```

验证 Layer 2 W8 encoding，并打印关键 A16 范围：

```bash
"$QAIHM_PY" - "$QAIHM_CKPT/model.encodings" <<'PY' | \
  tee "$DEBUG_ROOT/01_v18_layer2_encoding_gate.txt"
import json
import math
import statistics
import sys

path = sys.argv[1]
data = json.load(open(path, encoding="utf-8"))

def index(entries):
    if isinstance(entries, dict):
        return entries
    return {e["name"]: e for e in entries if isinstance(e, dict) and "name" in e}

params = index(data.get("param_encodings", []))
acts = index(data.get("activation_encodings", []))
target = "model.model.layers.2.mlp.down_proj.weight"
assert target in params, f"missing param encoding: {target}"
p = params[target]
scales = p.get("scale", [])
offsets = p.get("offset", [])
assert p.get("dtype") == "INT", p
assert int(p.get("bw")) == 8, f"expected W8, got W{p.get('bw')}"
assert p.get("enc_type") == "PER_CHANNEL", p
assert bool(p.get("is_sym")), p
assert len(scales) == 1024, f"expected 1024 channel scales, got {len(scales)}"
assert all(math.isfinite(float(x)) and float(x) > 0 for x in scales)
assert len(offsets) == 1024 and set(int(x) for x in offsets) == {-128}, \
    f"unexpected W8 offsets: {sorted(set(offsets))[:8]}"
print("PASS Layer2 down_proj: W8 symmetric PER_CHANNEL, 1024 scales, offset=-128")

tap_names = {
    "gate_up": "/model/model/layers.2/mlp/Mul_output_0",
    "down": "/model/model/layers.2/mlp/down_proj/Conv_output_0",
    "residual": "/model/model/layers.2/Add_1_output_0",
}
for label, name in tap_names.items():
    assert name in acts, f"missing activation encoding: {name}"
    e = acts[name]
    scale = float(e["scale"][0])
    offset = int(e["offset"][0])
    bw = int(e["bw"])
    lo = scale * offset
    hi = scale * ((2**bw - 1) + offset)
    assert bw == 16 and math.isfinite(scale) and scale > 0
    print(f"{label:8s} A{bw} scale={scale:.10g} offset={offset} range=[{lo:.6g}, {hi:.6g}]")

down_scales = []
for layer in range(28):
    name = f"/model/model/layers.{layer}/mlp/down_proj/Conv_output_0"
    if name in acts:
        down_scales.append((layer, float(acts[name]["scale"][0])))
others = [scale for layer, scale in down_scales if layer != 2]
if others:
    layer2 = dict(down_scales)[2]
    median = statistics.median(others)
    ratio = layer2 / median
    print(f"Layer2 down scale / other-layer median = {ratio:.3f}x")
    if ratio > 100:
        print("WARNING: Layer2 down activation scale remains >100x median; do not release before single-layer Phase B passes")
PY
```

静态门禁通过条件：

- checkpoint 三件套和哈希完整；
- Layer 2 `down_proj.weight` 明确为 W8、symmetric、`PER_CHANNEL`、1024 scales、offset 全为 `-128`；
- Layer 2 三个关键 A16 encoding 均存在、有限且 bitwidth 正确；
- 如果 Layer 2 `down` scale 仍高于其他层中位数 100 倍，只标记为高风险，不直接否决；最终以单层 QNN Phase B 是否恢复为准；
- 任何断言失败都停止，不提交 AI Hub 任务。

### 12.6 v18 Layer 2 单层 Phase B：第一道云端门禁

当前排查分支的 `tap_per_layer.py` 支持通过环境变量只选择一层。执行前清除所有 v17 resume map：

```bash
unset SIM_VS_DEVICE_PHASE_B_RESUME_COMPILE_JOBS
unset SIM_VS_DEVICE_PHASE_B_RESUME_INFERENCE_JOBS
export SIM_VS_DEVICE_PHASE_B_LAYERS=2

cd "$QAIHM_REPO/scripts/llm/sim_vs_device"

"$QAIHM_PY" run.py \
  --model-id qwen3_0_6b \
  --checkpoint "$QAIHM_CKPT" \
  --device "$DEVICE_NAME" \
  --precision w4a16 \
  --sequence-length 2048 \
  --context-length 4096 \
  --num-windows 1 \
  --dataset wikitext \
  --skip-phase-a \
  --phase-b force \
  --phase-b-part part2_of_2 \
  --model-cache-mode disable \
  --report-json "$DEBUG_ROOT/02_v18_layer2_phase_b.json" \
  2>&1 | tee "$DEBUG_ROOT/02_v18_layer2_phase_b.log"
```

提交前必须在日志看到：

```text
[Phase B] layer filter active: 2
[Phase B] compiling 1 layer sub-graphs (parallel) ...
```

如果显示编译 28 层，立即停止，说明单层过滤补丁没有生效。

Layer 2 通过条件：

- 只产生 1 个新 compile job 和 1 个新 inference job；
- `o_proj / gate_up / down / residual` 四个 tap 全部有结果；
- `down` SQNR 不低于 40 dB、cosine 不低于 0.99，且不再是相对健康中位数低 12 dB 以上的 crater；
- `residual` SQNR 不低于 40 dB、cosine 不低于 0.99；
- `o_proj` 和 `gate_up` 不得相对 v17 明显退化；
- 编译和推理均来自 v18 checkpoint 哈希，禁止通过 resume map 接入 v17 job。

任一条件失败：保留 job ID 和日志，回到 v18 量化/校准步骤；不得继续提交 28 层任务。

### 12.7 v18 完整 28 层 Phase B：第二道云端门禁

Layer 2 单层通过后才允许执行：

```bash
unset SIM_VS_DEVICE_PHASE_B_LAYERS
unset SIM_VS_DEVICE_PHASE_B_RESUME_COMPILE_JOBS
unset SIM_VS_DEVICE_PHASE_B_RESUME_INFERENCE_JOBS

cd "$QAIHM_REPO/scripts/llm/sim_vs_device"

"$QAIHM_PY" run.py \
  --model-id qwen3_0_6b \
  --checkpoint "$QAIHM_CKPT" \
  --device "$DEVICE_NAME" \
  --precision w4a16 \
  --sequence-length 2048 \
  --context-length 4096 \
  --num-windows 1 \
  --dataset wikitext \
  --skip-phase-a \
  --phase-b force \
  --phase-b-part part2_of_2 \
  --model-cache-mode disable \
  --report-json "$DEBUG_ROOT/03_v18_phase_b_28_layers.json" \
  2>&1 | tee "$DEBUG_ROOT/03_v18_phase_b_28_layers.log"
```

完整通过条件：

- 28 层、每层四个 tap 齐全，0 dropped；
- Layer 2 `down/residual` 满足单层门禁且不再出现 crater；
- 其他层没有因修复新出现的异常列或突然下降；
- 保存全部 compile/inference job ID，结果 JSON 与 checkpoint SHA-256 绑定归档。

### 12.8 v18 生产 bundle 和20条语料验收：最终门禁

只有完整 Phase B 通过后，才重新 compile/link/export v18 生产 bundle。要求：

- 使用唯一的 v18 模型名称，保持 `--model-cache-mode disable`；
- 记录 compile/link job ID、QAIRT 版本、编译选项、下载时间和两个 `.bin` 的 SHA-256；
- 新建 v18 Genie bundle 目录，不覆盖 v17 bundle，不混入任何历史 `.bin` 或 metadata；
- QuantSim 与端侧继续使用已经严格对齐的 Token、chat template、greedy 参数和20条固定语料；
- 先核对首 Token，再核对前 3～10 个 Decode Token/KV，最后统计20条 intent+slot；
- 最终端侧准确率应恢复至项目目标（当前基线目标至少约 90%），并与同口径 v18 QuantSim 结果一致或差距可解释；
- 任一阶段失败，保留 v17 生产 bundle，不用失败的 v18 覆盖设备上的可回滚版本。

禁止作为通过依据的结果：

- 任何 v3 或其他历史 checkpoint 的数值、job 或 bundle；
- 只修改 `model.encodings`、但没有重新导出权重三件套的正式模型；
- 启用模型缓存后无法证明内容哈希的编译结果；
- 输入 Token、Tokenizer、生成参数或20条语料不一致的准确率结果。

## 13. 如果逐层 Prefill 正常，应转查完整端侧链路

当 28 层均没有明显 crater 时，不应继续反复调整 W4 权重。按以下优先级排查。

### 13.1 生产 `.bin` 是否来自同一 checkpoint

记录并对齐：

- QuantSim checkpoint SHA-256；
- AI Hub compile/link job ID；
- 下载的 `.bin` SHA-256；
- precision、sequence length、context length；
- 编译选项和目标设备；
- 编译提交时间及代码 commit。

逐层工具会重新从当前 checkpoint 编译调试子图。它正常并不能证明旧的生产 `.bin` 也来自该 checkpoint。

### 13.2 Genie bundle 和 metadata

重点检查：

- `genie_config.json` 中的模型文件名、顺序和角色；
- `text-generator.json` 的 context、序列长度和生成设置；
- `htp_backend_ext_config.json`；
- 输入输出 tensor 名称是否与 compile/link 产物一致；
- Part 之间 hidden state、KV 输入输出是否接错；
- 手动恢复或组装 bundle 时是否混入旧 `.bin`、旧 metadata 或旧 supplementary files。

如果 export 在下载后组装失败，又根据 job ID 手动恢复 bundle，尤其要核对每个 `.bin` 的 job ID 和 metadata 唯一匹配关系。

### 13.3 Decode 与 KV Cache

逐 Token 对比，记录从第几个 Token 开始分叉：

- Prefill 最后一个 hidden/logits；
- 第 1 个 Decode Token 的输入和 logits；
- 第 1、2、3 个 Decode 步骤的 KV Cache；
- cache index/position ID；
- `position_ids_cos/sin`；
- KV 的 shape、layout、dtype、量化 scale 和 Part 间顺序；
- AR128/AR1/AR2048 等模型切换是否选对。

如果首 Token 正常，后续迅速崩坏，KV Cache 更新、回读或 Decode 模型连接是最高优先级。

### 13.4 LM Head、Tokenizer 和采样

检查：

- LM Head 输出是否按正确 dtype/scale 解码；
- logits tensor 是否取错输出或维度；
- argmax 轴是否正确；
- Tokenizer 词表和 special token ID 是否与 checkpoint 一致；
- system prompt 和 chat template 是否完全相同；
- `temperature`、`top_k`、`top_p`、重复惩罚和 EOS 是否一致；
- 输出文本清洗和准确率评分代码是否一致。

## 14. 激活值是否可能造成巨大差异

可能。W4A16 表示权重按 4 bit 量化、激活按 16 bit 量化或由目标后端按对应精度执行。即使 INT4 权重离散值完全一致，以下激活差异仍会改变后续结果：

- activation scale/offset 不一致；
- clip 范围不同；
- Add、Mul、SiLU、Softmax 前后发生不同的重定标；
- accumulator 精度或舍入规则不同；
- `--quantize_io` 边界的输入编码不匹配；
- KV Cache 写入和读取时使用了不同编码；
- 编译器融合使中间激活精度与 QuantSim 假设不同。

语言模型的生成是递归过程。单步很小的 logits 或 KV 差异可能改变一个 Token，后续输入随之变化，最终文本差异会被放大。因此端侧文本准确率从 90% 降到 5%，不代表每一层都存在同等巨大的数值误差。

## 15. 修复后的回归顺序

每次只修改一个因素，然后按以下顺序回归：

1. 通过 v18 checkpoint 三件套和 Layer 2 W8 静态门禁；
2. 只重跑 Layer 2 Phase B，确认 `down/residual` 达到 40 dB、cosine 0.99 门槛；
3. Layer 2 通过后重跑完整 28 层 Phase B，确认没有新 crater；
4. 使用相同 prompt 对比首个 Token；
5. 对比前 3～10 个 Decode Token 和 KV；
6. 重跑固定的 20 条车控语料；
7. 保存新 `.bin` 哈希、job ID、配置和日志；
8. 只有结果稳定后，才允许启用模型缓存或扩大语料规模。

建议验收条件：

- 本地 tap 和逐层拆图完整；
- Phase B 没有无法解释的深坑；
- 相同输入下首 Token 与 QuantSim 一致或差异可接受；
- 多步 Decode 不出现持续扩大的 KV/输出偏差；
- 20 条固定语料恢复至预期准确率；
- 完整结果可以由 checkpoint 哈希、代码 commit、job ID 和 bundle 哈希复现。

## 16. 需要归档的文件

排查完成后至少保留：

```text
01_git_info.txt
01_python_versions.txt
02_checkpoint_files.txt
02_checkpoint_sha256.txt
02_encoding_summary.txt
03_quantsim_20_cases.log
03_device_20_cases.log
04_inspect_taps.log
05_split_layers.log
06_phase_b.log
06_phase_b_tap_per_layer.json
07_dump_layer_N.log
00_v18_checkpoint_sha256.txt
01_v18_layer2_encoding_gate.txt
02_v18_layer2_phase_b.log
02_v18_layer2_phase_b.json
03_v18_phase_b_28_layers.log
03_v18_phase_b_28_layers.json
v18 量化/校准 manifest
validate_qwen3_v17_v18.py 的版本或 SHA-256
00_one_click_static_report.json
02_one_click_v18_layer2_phase_b_verdict.json
03_one_click_v18_phase_b_28_layers_verdict.json
生产 compile/link/inference job ID
生产 .bin 和 Genie bundle SHA-256
```

## 17. 当前 v17 → v18 落地执行顺序

v17 已完成定位，不再重复提交 v17 的 28 层任务。下一轮严格按以下门禁执行：

```text
生成 v18 三件套
  → v18 W8/activation encoding 静态检查
  → v18 Layer 2 单层 Phase B（1 compile + 1 inference）
  → v18 完整 28 层 Phase B
  → v18 production compile/link/export
  → 首 Token 和前 3～10 个 Decode/KV
  → 固定20条端侧回归
```

停止条件：

- v18 Layer 2 权重不是 W8：停止；
- checkpoint 三件套来源不一致：停止；
- 单层日志没有显示 `layer filter active: 2`：停止；
- Layer 2 `down/residual` 未通过 SQNR/余弦门槛：停止，不提交 28 层；
- 28 层出现新 crater：停止，不生成生产 bundle；
- job、checkpoint 或 `.bin` 哈希无法闭环：结果无效；
- 任何步骤引用 v3 或其他历史 checkpoint/job/bundle：结果无效。

当前 v17 基线证据保留在：

```text
outputs/v17_diagnostic/remote_evidence/08_phase_b_results_tap_per_layer.json
outputs/v17_diagnostic/remote_evidence/09_dump_layer_2.log
outputs/v17_diagnostic/remote_evidence/10_down_proj_encoding_comparison.json
outputs/v17_diagnostic/15_v17_root_cause_conclusion.md
```
