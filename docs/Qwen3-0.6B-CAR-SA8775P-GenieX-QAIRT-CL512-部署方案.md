# Qwen3-0.6B CAR → SA8775P GenieX_QAIRT（CL512）部署方案

> 当前有效方案；不包含旧版本量化记录和过期参数。  
> 基准：`ai-hub-models 0.59.0`；目标：`SA8775P ADP / Android 14 / HTP V73`。

## 当前执行状态

启动时间：2026-08-05 16:26（Asia/Shanghai）

```text
Stage=C / W4A16 + AdaScale quantization
PID=18553
Input=/root/autodl-tmp/work/source_checkpoint
Output=/root/autodl-tmp/qwen3_car_w4a16_cl4096
Log=/root/autodl-tmp/logs/quantize_w4a16_cl4096.log
Status=COMPLETED / SMOKETEST PASSED
```

启动参数已核对为 `w4a16 / context 4096 / calibration sequence 2048 / 20 samples / AdaScale 32 samples × 2048 iterations`。启动前已删除旧量化输出、旧量化日志以及 TMPDIR、QAIHM、HF、pip 缓存；源码、conda 环境和源 checkpoint 保留。清理后数据盘可用约 82 GiB。权重已加载 310/310，当前正在生成动态 ONNX。

### 运行告警（2026-08-05 17:00）

```text
PID=18553 / RUNNING
Elapsed=33 min 25 sec
CPU≈158%
RSS≈31.3 GB
AdaScale bars started=6
Current bar=6th, 1621/2048（约 79%）
Output≈2.9 GiB
TMPDIR≈59 GiB
Data disk available≈20 GiB / 135 GiB（86% used）
```

已完成前 5 条 AdaScale 进度，当前第 6 条约 79%，未发现 traceback、OOM、进程被杀或磁盘写入错误。但可用空间已低于 25 GiB 安全阈值，判定为磁盘容量告警。依据任务约束，不清理活跃 TMPDIR、不删除或覆盖数据、不停止或重启量化进程，也不启动 smoketest/export；等待用户决定扩容或接受继续运行的磁盘风险。

### 磁盘告警处置（2026-08-05 17:06）

经用户授权，已删除与当前 CL4096 量化无关的旧量化 checkpoint、旧 merged 模型、旧 Genie bundle、旧模型压缩包和回收站内容。未触碰当前活跃 TMPDIR、新输出、源 checkpoint、0.59.0 源码或 conda 环境。

```text
Data disk available: 20 GiB → 33 GiB
Current TMPDIR≈59 GiB
Current output≈2.9 GiB
Source checkpoint≈1.2 GiB
PID=18553 / RUNNING
Completed AdaScale bars=7
Current bar=8th, 1149/2048（约 56%）
```

删除后所有目标均确认不存在，量化进程继续正常运行，未发现致命错误。磁盘已回到 25 GiB 安全阈值以上，但余量仍有限，继续监控。

### 自动监控快照（2026-08-05 17:30）

```text
PID=18553 / RUNNING
Elapsed=1 h 03 min 27 sec
CPU≈146%
RSS≈40.2 GB
Completed AdaScale bars=14
Latest bar=2048/2048（约 2 min 55 sec）
Output≈2.9 GiB
TMPDIR≈59 GiB
Data disk available≈58 GiB / 135 GiB
```

AdaScale 预计共 28 条，当前已完成约一半。最终 `model.encodings` 和 `args.json` 尚未生成。进程、TMPDIR 和磁盘状态稳定，未发现致命错误；继续运行，不执行 smoketest/export。

### 实时进度快照（2026-08-05 17:59）

```text
PID=18553 / RUNNING
Elapsed=1 h 32 min 30 sec
CPU≈140%
RSS≈49.1 GB
Completed AdaScale bars=21
Current bar=22nd, 601/2048（约 29%）
Output≈2.9 GiB
TMPDIR≈59 GiB
Data disk available≈58 GiB / 135 GiB
```

按预计 28 条计算，AdaScale 条数进度约为 76%。最终 `model.encodings` 与 `args.json` 尚未生成。未发现致命错误，磁盘占用保持稳定；预计剩余 AdaScale 加最终校准/写盘还需约 30～55 分钟。

### 阶段 C 完成与 CL4096 smoketest（2026-08-05 18:28—18:33）

量化进程正常退出，日志明确记录 `Quantization completed successfully.`。28 条 AdaScale 全部完成，随后完成 20 samples / 640 batches 的 QuantSim calibration 并写出最终 checkpoint。

```text
Checkpoint=/root/autodl-tmp/qwen3_car_w4a16_cl4096
Checkpoint size≈2.9 GiB
model.encodings≈32 MiB
args.json=850 bytes
TMPDIR=0
Data disk available≈116 GiB / 135 GiB
```

`args.json` 参数门禁：

| 参数 | 实际值 | 结果 |
|---|---:|---|
| precision | `w4a16` | PASS |
| context_length | `4096` | PASS |
| calibration_sequence_length | `2048` | PASS |
| num_samples | `20` | PASS |
| use_ada_scale | `true` | PASS |
| ada_scale_num_samples | `32` | PASS |
| ada_scale_num_iterations | `2048` | PASS |

CL4096 smoketest 命令：

```bash
/root/autodl-tmp/conda-envs/qaihm059/bin/python \
  -m qai_hub_models.models.qwen3_0_6b.demo \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096 \
  --context-length 4096 \
  --sequence-length 1 128 \
  --max-output-tokens 64 \
  --no-thinking \
  --seed 42 \
  --prompt SMOKE_TEST_OK
```

运行结果：

```text
Exit code=0
Variant=QUANTIZED (AIMET-ONNX)
QuantSim providers=['CUDAExecutionProvider', 'CPUExecutionProvider']（两个 session）
Generated non-empty response=yes
Wall time≈123 sec
Response=Hello! I'm ready for SMOKE test. What's your plan for the day?
```

结论：**阶段 C 技术验收通过。** 正式 CL4096 W4A16 + AdaScale checkpoint 参数正确，可加载、可建立 CUDA QuantSim 会话，并可完成 AR1/128 decode。输出未精确复述 `SMOKE_TEST_OK`，因此该单样本不证明 exact-match 指令遵循精度，但不阻塞后续 export。测试日志：`/root/autodl-tmp/logs/smoketest_w4a16_cl4096.log`。按任务边界停在阶段 C，不自动进入 AI Hub export。

## 1. 固定参数

| 项目 | 值 |
|---|---|
| 本地模型包 | `E:\LLMProject\models\source_model\qwen3-06b-car-merged-v5.rar` |
| 本地源码 | `E:\QualComm\ai-hub-models-0.59.0` |
| AutoDL | `root@connect.nmb1.seetacloud.com:39194` |
| 数据盘 | `/root/autodl-tmp` |
| 模型入口 | `qwen3_0_6b` |
| 量化精度 | `w4a16` |
| 量化 context | `4096` |
| 校准 sequence | `2048` |
| 校准 samples | `20` |
| AdaScale | `32 samples / 2048 iterations` |
| export runtime | `geniex_qairt` |
| export context | `512` |
| sequence lengths | `128,1` |
| 目标设备 | `SA8775P ADP` |

参数边界：

```text
quantize        --context-length 4096
quantize        --calibration-sequence-length 2048
demo/smoketest  --context-length 4096
export          --context-lengths 512
```

只有 `qai-hub-models export qwen3_0_6b` 使用 `--context-lengths 512`。SSH 密码和 AI Hub Token 仅交互输入，不写入文档、脚本、日志或 Git。

## 2. 路线

```text
CAR HF checkpoint
  → AutoDL W4A16 + AdaScale（CL4096）
  → CL4096 smoketest
  → AI Hub Models geniex_qairt export（CL512）
  → SA8775P 编译/链接产物
  → GenieX_QAIRT App
```

不使用普通 `qnn-onnx-converter → qnn-context-binary-generator` 替代 Qwen LLM 专用 export。

## 3. 阶段 A/B：输入、环境和资源预检

本地校验：

```powershell
$ModelArchive = 'E:\LLMProject\models\source_model\qwen3-06b-car-merged-v5.rar'
Get-Item -LiteralPath $ModelArchive
Get-FileHash -LiteralPath $ModelArchive -Algorithm SHA256
Get-Item -LiteralPath 'E:\QualComm\ai-hub-models-0.59.0'
```

远程资源：

```bash
nvidia-smi
python3 --version
free -h
df -h /root/autodl-tmp
```

统一使用数据盘目录：

```bash
mkdir -p /root/autodl-tmp/{work,logs,tmp,hf-cache,qaihm-store,pip-cache}
export TMPDIR=/root/autodl-tmp/tmp
export HF_HOME=/root/autodl-tmp/hf-cache
export QAIHM_STORE_ROOT=/root/autodl-tmp/qaihm-store
export HF_ENDPOINT=https://hf-mirror.com
export HF_HUB_DISABLE_XET=1
export TOKENIZERS_PARALLELISM=false
```

固定路径：

```text
Source repo=/root/autodl-tmp/ai-hub-models-0.59.0
Conda env=/root/autodl-tmp/conda-envs/qaihm059
Checkpoint=/root/autodl-tmp/work/source_checkpoint
```

验证源码与 CUDA provider：

```bash
cd /root/autodl-tmp/ai-hub-models-0.59.0
/root/autodl-tmp/conda-envs/qaihm059/bin/python - <<'PY'
import inspect
import onnxruntime as ort
import qai_hub_models.models.qwen3_0_6b.model as model
print(inspect.getfile(model))
print(ort.get_available_providers())
PY
```

必须加载 0.59.0 源码并包含 `CUDAExecutionProvider`。

## 4. 阶段 C：正式量化

```bash
cd /root/autodl-tmp/ai-hub-models-0.59.0

nohup env \
  TMPDIR=/root/autodl-tmp/tmp \
  HF_HOME=/root/autodl-tmp/hf-cache \
  QAIHM_STORE_ROOT=/root/autodl-tmp/qaihm-store \
  HF_ENDPOINT=https://hf-mirror.com \
  HF_HUB_DISABLE_XET=1 \
  TOKENIZERS_PARALLELISM=false \
  /root/autodl-tmp/conda-envs/qaihm059/bin/python \
  -m qai_hub_models.models.qwen3_0_6b.quantize \
  --checkpoint /root/autodl-tmp/work/source_checkpoint \
  --precision w4a16 \
  --context-length 4096 \
  --calibration-sequence-length 2048 \
  --num-samples 20 \
  --use-ada-scale \
  --ada-scale-num-samples 32 \
  --ada-scale-num-iterations 2048 \
  --output-dir /root/autodl-tmp/qwen3_car_w4a16_cl4096 \
  > /root/autodl-tmp/logs/quantize_w4a16_cl4096.log 2>&1 < /dev/null &
```

进度检查：

```bash
ps -p 18553 -o pid,etime,stat,%cpu,%mem,rss,cmd
tail -f /root/autodl-tmp/logs/quantize_w4a16_cl4096.log
df -h /root/autodl-tmp
du -sh /root/autodl-tmp/tmp /root/autodl-tmp/qwen3_car_w4a16_cl4096
```

完成检查：

```bash
grep -F 'Quantization completed successfully.' /root/autodl-tmp/logs/quantize_w4a16_cl4096.log
ls -lh /root/autodl-tmp/qwen3_car_w4a16_cl4096/model.encodings \
       /root/autodl-tmp/qwen3_car_w4a16_cl4096/args.json
```

必须复核 `args.json` 参数与本方案一致。进程退出但最终文件不完整时，禁止 export。

### 4.1 CL4096 smoketest

```bash
/root/autodl-tmp/conda-envs/qaihm059/bin/python \
  -m qai_hub_models.models.qwen3_0_6b.demo \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096 \
  --context-length 4096 \
  --sequence-length 1 128 \
  --max-output-tokens 64 \
  --no-thinking \
  --seed 42 \
  --prompt 'Reply with exactly: SMOKE_TEST_OK' \
  2>&1 | tee /root/autodl-tmp/logs/smoketest_w4a16_cl4096.log
```

通过条件：checkpoint/tokenizer 可加载；AIMET-ONNX QuantSim 使用 CUDA；CL4096 + AR1/128 可执行；生成非空 token；退出码 0；无乱码、NaN、OOM 或 traceback。

## 5. 阶段 D：AI Hub export（仅此处 CL512）

### 当前执行状态（2026-08-05 19:41）

统一入口 `qai-hub-models export qwen3_0_6b` 在 0.59.0 中存在 dispatcher 缺陷：模型 parser 设置 `omit_precision=True`，但统一 dispatcher 绕过模型 `main()`，导致 `export_model() missing 1 required positional argument: 'precision'`。该次调用在本地参数调度阶段失败，没有提交 AI Hub job、没有产生费用或配额占用。

源码 `qai_hub_models.models.qwen3_0_6b.export.main()` 会从自定义 checkpoint 正确解析 `w4a16`，因此当前使用模型专用模块入口执行同一 export：

```text
PID=29607
Status=RUNNING
Checkpoint=/root/autodl-tmp/qwen3_car_w4a16_cl4096
Runtime=geniex_qairt
Chipset=qualcomm-sa8775p
Device OS=14
Context lengths=512
Sequence lengths=128,1
Output=/root/autodl-tmp/output/export_sa8775p_cl512
Log=/root/autodl-tmp/logs/export_sa8775p_cl512.log
```

为避免 SSH 对设备名空格的拆分，实际提交使用 `--chipset qualcomm-sa8775p --device-os 14`，与选择 SA8775P ADP 目标等价。当前正在本地初始化，尚未出现 AI Hub job ID 或错误。

### Export 进度（2026-08-05 19:48）

```text
PID=29607 / RUNNING
Elapsed≈7 min
Part 1 AIMET archive≈309 MiB / upload complete
Part 2 AIMET archive≈1.83 GiB / upload complete
TMPDIR≈7.9 GiB
Data disk available≈108 GiB
Output bundle=not generated yet
AI Hub job ID=not printed yet
```

本地 QuantSim/拆图已经生成两个 W4A16 AIMET 上传包，两部分均已上传完成。进程处于等待状态，日志尚未打印 compile/link job ID 或错误，说明正在等待 AI Hub 接口响应或创建云端作业。不得重复提交；继续监控当前进程。

### AI Hub 作业与下载进度（2026-08-05 19:52）

已成功提交 4 个 compile job：

```text
jpeyyek15
j5w44296g
jp166y825
j57990dlg
```

已成功提交 2 个 link job：

```text
Part 1: jp433kwv5
Part 2: jpxxxn11p
```

日志确认首个 compile job 与 Part 1 link job 均为 `SUCCESS`。Part 1 context binary（`part1_of_2.bin`，约 297 MiB）已下载至 100%。进程仍在运行，预计将继续等待 Part 2 link 并下载 `part2_of_2.bin`，之后组装 bundle。当前无错误，数据盘可用约 108 GiB。

### Part 2 下载完成（2026-08-05 20:02）

Part 2 link job 已完成等待阶段，`part2_of_2.bin`（约 380 MiB）已下载至 100%。两个 context binary 均已取得。export 主进程仍在运行，本地目标目录尚未出现最终文件，说明正在执行 bundle 配置生成、资产复制或 zip 组装。TMPDIR 约 8.6 GiB，数据盘可用约 107 GiB，未发现错误。

### 阶段 D 失败停点（2026-08-05 20:07）

export 主进程 PID 29607 已退出。AI Hub 侧的 4 个 compile job 和 2 个 link job 已提交，日志确认两个 link 均完成，`part1_of_2.bin`（约 297 MiB）和 `part2_of_2.bin`（约 380 MiB）均成功下载到临时目录：

```text
/root/autodl-tmp/tmp/tmpwenz5656/qwen3_0_6b-geniex_qairt-w4a16-qualcomm_sa8775p/
```

失败发生在 `download_multi_graph_collection_model_bundle()` 的补充文件阶段。`model.write_supplementary_files()` 调用 `AutoTokenizer.from_pretrained('Qwen/Qwen3-0.6B')` 获取 tokenizer/config，Hugging Face 请求失败，并出现已关闭 HTTP client 的重试异常：

```text
RuntimeError: Cannot send a request, as the client has been closed.
OSError: Can't load the configuration of 'Qwen/Qwen3-0.6B'.
```

因此最终目录 `/root/autodl-tmp/output/export_sa8775p_cl512` 为空，bundle/zip 未生成。当前 TMPDIR 约 2.2 GiB，数据盘可用约 114 GiB。该失败发生在云端编译、链接和 binary 下载之后，不表示 context binary 编译失败；它表示本地 bundle 组装不完整。依据停点规则，不自动重提 job、不移动临时 binary、不修改源码、不进入设备部署。恢复前应先决定采用本地 checkpoint tokenizer/config 完成离线 bundle 组装，或修复 Hugging Face 访问后仅重跑下载/组装流程，必须避免重复提交已成功的 AI Hub 作业。

先检查真实 CLI：

```bash
qai-hub-models export qwen3_0_6b --help
```

必须确认当前版本支持 `geniex_qairt`、`--context-lengths`、`--sequence-lengths` 和 `SA8775P ADP`。Token 交互配置：

```bash
qai-hub configure --api_token '<交互输入>'
```

正式命令：

```bash
qai-hub-models export qwen3_0_6b \
  --checkpoint /root/autodl-tmp/qwen3_car_w4a16_cl4096 \
  --runtime geniex_qairt \
  --device 'SA8775P ADP' \
  --device-os 14 \
  --context-lengths 512 \
  --sequence-lengths 128,1 \
  --zip-assets \
  --skip-profiling \
  --output-dir /root/autodl-tmp/output/export_sa8775p_cl512 \
  2>&1 | tee /root/autodl-tmp/logs/export_sa8775p_cl512.log
```

如果本机 `--help` 参数名不同，先修正文档再提交，不盲目重试。预期包含 2 Parts 的 AR128/AR1、CL512 组合。所有 compile/link job、目标设备和 bundle 文件必须验收通过。

## 6. 阶段 E/F：下载与 App 集成

远程生成校验清单并打包：

```bash
cd /root/autodl-tmp/output/export_sa8775p_cl512
find . -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
sha256sum -c SHA256SUMS
cd /root/autodl-tmp/output
tar -czf qwen3_car_sa8775p_geniex_qairt_cl512.tar.gz export_sa8775p_cl512
sha256sum qwen3_car_sa8775p_geniex_qairt_cl512.tar.gz
```

下载：

```powershell
scp -P 39194 root@connect.nmb1.seetacloud.com:/root/autodl-tmp/output/qwen3_car_sa8775p_geniex_qairt_cl512.tar.gz `
  'D:\QAIRT-Workspace\android-apps\Qwen3-0.6B-HTP-Demo\artifacts\'
```

App 集成必须包含匹配的 GenieX/QNN runtime 库、SA8775P 模型文件、tokenizer、模型配置及 Genie/HTP 配置。配置引用的模型文件名必须与 bundle 实际文件一致。

设备验收至少覆盖：初始化、context binary 加载、prefill/decode、中文/英文/车控 JSON、无乱码、连续运行稳定性、首 token 延迟、decode tokens/s、峰值内存和温度。

## 7. 停点与交付清单

- [ ] A/B：输入 SHA-256、资源、Python 3.10、源码和 CUDA provider 通过
- [ ] C：CL4096 W4A16 + AdaScale 完成
- [ ] C：`args.json` 参数复核通过
- [ ] C：CL4096 smoketest 通过
- [ ] D：仅 export 使用 `--context-lengths 512`
- [ ] D：SA8775P `geniex_qairt` compile/link 全部成功
- [ ] E：bundle 下载并通过 SHA-256
- [ ] F：SA8775P 真机验收通过

任何删除、覆盖、重新量化、AI Hub 付费/配额作业和设备部署，都必须在对应停点检查通过后执行。

## 阶段 D：复用既有 binary 离线重新组装 bundle（2026-08-05 20:22 +08:00）

未重新提交任何 AI Hub compile/link job。本次直接复用已经成功的 link job：

```text
part1_of_2.bin: jp433kwv5
part2_of_2.bin: jpxxxn11p
tool version 来源 compile job: jpeyyek15
```

离线组装使用本地 checkpoint：

```text
/root/autodl-tmp/qwen3_car_w4a16_cl4096
```

首次离线组装发现 checkpoint 将 Qwen chat template 单独保存为
`chat_template.jinja`，而 GenieX supplementary-file 生成逻辑要求
`tokenizer_config.json` 内含 `chat_template`。因此组装脚本在临时 bundle 中复制本地
`tokenizer.json`、`tokenizer_config.json`、`config.json`，并把
`chat_template.jinja` 内容嵌入 `tokenizer_config.json`；没有修改 checkpoint 或
qai-hub-models 源码。

执行脚本：

```bash
/root/autodl-tmp/conda-envs/qaihm059/bin/python \
  /root/autodl-tmp/work/assemble_existing_sa8775p_bundle.py
```

最终产物：

```text
/root/autodl-tmp/output/export_sa8775p_cl512/qwen3_0_6b-geniex_qairt-w4a16-qualcomm_sa8775p.zip
size: 629689636 bytes
SHA-256: e003a64def2ac9b106b96e7d9b4b8a20ce24ec5558f8adfc5e31c41868ada87b
```

验收结论：

- ZIP CRC/完整性检查通过，`zip_bad_file=null`，必需文件无缺失。
- 两个 context binary 大小分别为 311226368 和 398827520 bytes。
- metadata：`runtime=geniex_qairt`、`precision=w4a16`、目标 `qualcomm-sa8775p`。
- Genie context size 为 512，ctx-bins 为 `part1_of_2.bin`、`part2_of_2.bin`。
- HTP 配置为 `soc_model=52`、`dsp_arch=v73`、shared buffer、weight sharing enabled。
- `tokenizer_config.json` 已内嵌 4168 字符的 chat template。
- bundle 离线重新组装成功，未重复消耗 AI Hub 编译/链接配额；尚未进入设备部署。
