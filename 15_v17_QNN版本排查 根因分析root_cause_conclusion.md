# v17 根因结论

日期：2026-08-14（Asia/Shanghai）

## 明确结论

v17 的首个、主要错误点位于 **Transformer Layer 2 的 MLP `down_proj`**。

该层是源码明确要求保留 INT8 权重的敏感层：

```text
src/qai_hub_models/models/qwen3_0_6b/model.py:100
INT8_PARAM_NAMES = ("model.model.layers.2.mlp.down_proj.weight",)
```

但 v17 checkpoint 的 `model.encodings` 实际把
`model.model.layers.2.mlp.down_proj.weight` 编成了 W4，而不是要求的 W8。
同时，该层 MLP 的 A16 激活编码出现极端离群范围。这个错误组合导致 QNN
在 Layer 2 `down_proj Conv` 的定点累加/输出重定标处与 QuantSim 明显分歧，
并由 residual 传播到后续层和最终生成结果。

因此，v17 当前问题属于 **Layer 2 `down_proj` 量化配置错误及其触发的 QNN
数值问题**，不是 Tokenizer、prompt、bundle 文件不一致、Attention/KV 路径或
per-channel 编码缺失问题。

## v17 实测证据

Phase B 为每一层注入 golden 上游输入，因此可以排除上一层误差累积：

| Layer 2 tap | SQNR (dB) | cosine | max abs diff | 判断 |
| --- | ---: | ---: | ---: | --- |
| `o_proj` | 56.447 | 0.999519 | 0.03494 | 正常 |
| `gate_up` | 53.742 | 0.987374 | 0.79241 | 基本正常，`down_proj` 输入边界 |
| `down` | 24.123 | 0.536302 | 2.63549 | 首个严重分歧点 |
| `residual` | 32.606 | 0.867635 | 2.63551 | 继承 `down` 误差 |

Layer 2 `down_proj` 权重和激活编码：

- 权重 shape：`[1024, 3072, 1, 1]`；
- 当前实际编码：W4、`PER_CHANNEL`、1024 个 scale、offset 全为 `-8`；
- 1024 个 scale 与 Conv 输出通道轴 0 数量一致，没有缺失、零值或数量错位；
- `gate_up` A16：scale `0.1132012668`，offset `-29764`，隐含范围约
  `[-3369.32, 4049.32]`；
- `down` A16：scale `0.1254994601`，offset `-1492`，隐含范围约
  `[-187.25, 8037.36]`；
- `residual` A16：scale `0.1255005695`，offset `-1500`。

作为同一 v17 内部的相邻层参照，Layer 1/3 的 `down` scale 分别仅为
`0.0001739506` 和 `0.0000950938`；Layer 2 分别放大约 721 倍和 1320 倍。

## 修复判据

应重新生成 v17 checkpoint，至少满足：

1. `model.model.layers.2.mlp.down_proj.weight` 恢复为 W8，与
   `INT8_PARAM_NAMES` 一致；
2. 重新校准 Layer 2 `gate_up/down/residual` 的 A16 encodings，避免当前离群
   范围直接沿 residual 传播；
3. 只重跑 Layer 2 Phase B：`gate_up`、`down`、`residual` 均不再出现 crater，
   其中 `down` 应恢复到与其他层相当的高 SQNR；
4. 再重新编译完整 v17 bundle，并执行原有 20 条端侧质量回归。

## 证据文件

- `remote_evidence/08_phase_b_results_tap_per_layer.json`
- `remote_evidence/08_phase_b_results.log`
- `remote_evidence/09_dump_layer_2.log`
- `remote_evidence/10_down_proj_encoding_comparison.json`
- `remote_evidence/10_down_proj_encoding_comparison.log`
