# -*- coding: utf-8 -*-
"""协议冒烟测试：打印输入 Token 和 Android 对齐参数后批量推理。

模型只加载一次，速度与 batch_demo.py 对齐。
用法: python run_smoke20.py [checkpoint_dir] [tag]
"""
import json
import re
import sys
import time
from datetime import datetime
from pathlib import Path

import torch
from transformers import GenerationConfig, set_seed

try:
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
    from openpyxl.worksheet.table import Table
except ImportError as exc:
    raise SystemExit(
        "缺少 Excel 导出依赖 openpyxl。请执行："
        "python -m pip install openpyxl==3.1.5"
    ) from exc

from qai_hub_models.models._shared.llm.generator_factory import make_generator
from qai_hub_models.models._shared.llm.model import get_tokenizer
from qai_hub_models.models._shared.qwen3.model import END_TOKENS
from qai_hub_models.models.qwen3_0_6b.model import (
    FPSplitModelWrapper,
    HF_REPO_NAME,
    QuantizedSplitModelWrapper,
    Qwen3_0_6B_PreSplit,
    Qwen3_0_6B_QuantizablePreSplit,
)
from qai_hub_models.utils.checkpoint import CheckpointType

CKPT = sys.argv[1] if len(sys.argv) > 1 else '/root/autodl-tmp/qwen3_car_w4a16_cl4096_v21'
TAG = sys.argv[2] if len(sys.argv) > 2 else 'v21'
SCRIPT_DIR = Path(__file__).resolve().parent
WORKDIR = SCRIPT_DIR
BATCH_RESULT_DIR = SCRIPT_DIR / "batch_result"
BATCH_RESULT_DIR.mkdir(parents=True, exist_ok=True)
RUN_TIMESTAMP = datetime.now().strftime("%Y%m%d_%H%M%S")
EXCEL_RESULT = BATCH_RESULT_DIR / (
    f"smoke20_result_Qwen3-0.6B-QuantSim-{TAG}_{RUN_TIMESTAMP}.xlsx"
)
JSON_RESULT = BATCH_RESULT_DIR / f"smoke20_results_{TAG}.json"

# 与《Genie_CLI与Android推理参数统一说明.md》一致的推理参数。
ANDROID_CONTEXT_SIZE = 512
ANDROID_MAX_ALL_TOKENS = 256
ANDROID_MAX_OUTPUT_TOKENS = 48
ANDROID_BOS_TOKEN_COUNT = 1
ANDROID_SEED = 42
ANDROID_TEMPERATURE = 0.0
ANDROID_TOP_K = 40
ANDROID_TOP_P = 0.95
ANDROID_GREEDY = True
ANDROID_PENALIZE_LAST_N = 128
ANDROID_REPETITION_PENALTY = 1.0
ANDROID_PRESENCE_PENALTY = 0.0
ANDROID_FREQUENCY_PENALTY = 0.0

# make_generator 实际使用的 Python 侧模型参数。
CONTEXT_LENGTH = ANDROID_CONTEXT_SIZE
SEQUENCE_LENGTH = [1, 128]

# 下列参数属于 Genie/QNN/HTP 配置，当前 Python/AIMET 冒烟脚本不能控制。
GENIE_QNN_REFERENCE_PARAMS = {
    "engine_threads": 4,
    "htp_use_mmap": True,
    "htp_spill_fill_buffer": 0,
    "htp_mmap_budget": 0,
    "htp_poll": False,
    "cpu_mask": "0xe0",
    "kv_dimension": 128,
    "async_init": False,
    "position_id_dimension": 64,
    "rope_theta": 1000000,
}

SAMPLES_FILE = sys.argv[3] if len(sys.argv) > 3 else "testset_v5_base_smoke.json"
SAMPLES_LIMIT = int(sys.argv[4]) if len(sys.argv) > 4 else 0
samples = json.load((WORKDIR / SAMPLES_FILE).open(encoding="utf-8"))
if SAMPLES_LIMIT:
    samples = samples[:SAMPLES_LIMIT]
ECHO_PREFIX = re.compile(r'^\s{0,6}\+\s?', re.M)


def clean_echo(text):
    """剥掉 demo token 回显的 '    + ' 行前缀。"""
    return ECHO_PREFIX.sub('', text)


def parse_v5_output(text):
    """解析 v5 分类器输出：'COMMAND k1 v1 k2 v2'（取首行，shlex 解析，兼容引号值）。"""
    import shlex

    lines = clean_echo(text).strip().splitlines()
    if not lines:
        return None
    first = lines[0].strip()
    try:
        parts = shlex.split(first)
    except ValueError:
        return None
    if not parts:
        return None
    rest = parts[1:]
    slot = dict(zip(rest[0::2], rest[1::2]))
    return {"intent": parts[0], "slot": slot, "raw": first}


def extract_json(text):
    best = None
    text = clean_echo(text)
    for m in re.finditer(r'\{[^{}]*"intent"[^{}]*(?:\{[^{}]*\}[^{}]*)*\}', text, re.S):
        try:
            obj = json.loads(m.group(0))
            if isinstance(obj, dict) and 'intent' in obj:
                best = obj
        except Exception:
            pass
    return best


def make_generation_config(max_new_tokens, end_token_ids, pad_token_id):
    """生成单条语料的 Android 对齐 GenerationConfig。"""
    return GenerationConfig(
        max_new_tokens=max_new_tokens,
        eos_token_id=end_token_ids or None,
        pad_token_id=pad_token_id,
        do_sample=not ANDROID_GREEDY,
        top_k=ANDROID_TOP_K,
        top_p=ANDROID_TOP_P,
        temperature=ANDROID_TEMPERATURE,
        repetition_penalty=ANDROID_REPETITION_PENALTY,
    )


def print_runtime_parameters(host_device, checkpoint_type, end_token_ids, pad_token_id):
    """打印实际生效参数、文档约束，以及 Genie/QNN 侧参考参数。"""
    checks = {
        "context_length": (
            CONTEXT_LENGTH,
            "必须为 512，且不小于 max_all_token",
            CONTEXT_LENGTH == 512 and CONTEXT_LENGTH >= ANDROID_MAX_ALL_TOKENS,
        ),
        "max_all_token": (
            ANDROID_MAX_ALL_TOKENS,
            "必须为 256，范围 1..context_length",
            1 <= ANDROID_MAX_ALL_TOKENS <= CONTEXT_LENGTH,
        ),
        "max_output_tokens": (
            ANDROID_MAX_OUTPUT_TOKENS,
            "必须为 48，实际值还受 256 - 输入Token数限制",
            ANDROID_MAX_OUTPUT_TOKENS == 48,
        ),
        "bos_token_count": (
            ANDROID_BOS_TOKEN_COUNT,
            "当前 Qwen3 实测必须为 1",
            ANDROID_BOS_TOKEN_COUNT == 1,
        ),
        "seed": (ANDROID_SEED, "必须为 42", ANDROID_SEED == 42),
        "temperature": (
            ANDROID_TEMPERATURE,
            "必须为 0，且不小于 0",
            ANDROID_TEMPERATURE == 0.0,
        ),
        "top_k": (ANDROID_TOP_K, "必须为 40，且不小于 0", ANDROID_TOP_K == 40),
        "top_p": (
            ANDROID_TOP_P,
            "必须为 0.95，范围 (0, 1]",
            ANDROID_TOP_P == 0.95 and 0 < ANDROID_TOP_P <= 1,
        ),
        "greedy": (ANDROID_GREEDY, "必须为 true", ANDROID_GREEDY is True),
        "repetition_penalty": (
            ANDROID_REPETITION_PENALTY,
            "必须为 1.0，且大于 0",
            ANDROID_REPETITION_PENALTY == 1.0,
        ),
    }

    print("\n==== 推理参数与范围检查 ====", flush=True)
    print(f"checkpoint            : {CKPT}", flush=True)
    print(f"checkpoint_type       : {checkpoint_type}", flush=True)
    print(f"host_device           : {host_device}", flush=True)
    print(f"sequence_length       : {SEQUENCE_LENGTH}", flush=True)
    print(f"eos/stop token ids    : {end_token_ids}", flush=True)
    print(f"pad_token_id          : {pad_token_id}", flush=True)
    for name, (value, expected, passed) in checks.items():
        status = "PASS" if passed else "FAIL"
        print(f"[{status}] {name:<22}: {value!r}；{expected}", flush=True)

    failed = [name for name, (_, _, passed) in checks.items() if not passed]
    if failed:
        raise ValueError(f"推理参数范围检查失败: {', '.join(failed)}")

    print("\nGenie/QNN/HTP 参考参数（本 Python/AIMET 脚本不实际控制）:", flush=True)
    print(json.dumps(GENIE_QNN_REFERENCE_PARAMS, ensure_ascii=False, indent=2), flush=True)
    print(
        "penalize_last_n/presence_penalty/frequency_penalty: "
        f"{ANDROID_PENALIZE_LAST_N}/{ANDROID_PRESENCE_PENALTY}/"
        f"{ANDROID_FREQUENCY_PENALTY}（Genie 参数，当前 HF generator 无对应控制项）",
        flush=True,
    )


def print_case_tokens(sample, prompt, input_ids, token_pieces, genie_input_len, max_new_tokens):
    """打印单条语料的完整输入 Token 及动态 Token 预算。"""
    print(f"\n---- case id={sample['id']} user={sample['user']} ----", flush=True)
    print(f"Raw prompt             : {prompt!r}", flush=True)
    print(f"Full raw prompt tokens : {len(input_ids)}", flush=True)
    print(f"Genie prompt tokens    : {genie_input_len} (= raw + {ANDROID_BOS_TOKEN_COUNT} BOS)", flush=True)
    print(f"Input token IDs        : {input_ids}", flush=True)
    print(f"Input token pieces     : {token_pieces}", flush=True)
    print(f"Dynamic max_new_tokens : {max_new_tokens}", flush=True)
    print(
        f"Total token budget     : {genie_input_len + max_new_tokens}/"
        f"{ANDROID_MAX_ALL_TOKENS} (context={CONTEXT_LENGTH})",
        flush=True,
    )


def json_cell(value):
    """将 JSON 对象压缩成适合 Excel 单元格展示的字符串。"""
    if value is None:
        return ""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def write_excel_report(results, output_path):
    """按 QNN 批量结果参考表格式生成 smoke20 Excel 报告。

    当前 Python generator 只提供端到端耗时，不能可靠拆分 Prefill 和 Decode
    耗时。因此对应四个分段性能单元格明确写入 N/A，避免产生伪造指标。
    """
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "批量推理结果"
    sheet.sheet_view.showGridLines = False
    sheet.freeze_panes = "A2"

    headers = [
        "输入提示词",
        "预期结果",
        "输入TokenIDs",
        "推理结果",
        "比对结果",
        "Prefill(s)",
        "Decode(s)",
        "Total(s)",
        "Prefill(tok/s)",
        "Decode(tok/s)",
        "PromptTokens",
        "DecodeTokens",
    ]
    sheet.append(headers)

    for result in results:
        if result["exact_ok"]:
            comparison = "一致"
        elif result["intent_ok"]:
            comparison = "意图一致/槽位不一致"
        else:
            comparison = "不一致"

        inference_result = (
            json_cell(result["got"])
            if result["got"] is not None
            else result["raw_output"]
        )
        sheet.append(
            [
                result["user"],
                json_cell(result["expected"]),
                json_cell(result["input_token_ids"]),
                inference_result,
                comparison,
                "N/A",
                "N/A",
                result["secs"],
                "N/A",
                "N/A",
                result["genie_input_token_count"],
                result["decode_token_count"],
            ]
        )

    purple = "5B36B7"
    white = "FFFFFF"
    black = "000000"
    thin = Side(style="thin", color=black)
    cell_border = Border(left=thin, right=thin, top=thin, bottom=thin)

    for cell in sheet[1]:
        cell.fill = PatternFill("solid", fgColor=purple)
        cell.font = Font(name="Calibri", size=11, bold=True, color=white)
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        cell.border = cell_border
    sheet.row_dimensions[1].height = 26

    for row in sheet.iter_rows(min_row=2, max_row=sheet.max_row, min_col=1, max_col=12):
        for cell in row:
            cell.font = Font(name="Calibri", size=11)
            cell.alignment = Alignment(vertical="top", wrap_text=True)
            cell.border = cell_border
        row[4].alignment = Alignment(horizontal="center", vertical="top", wrap_text=True)
        for cell in row[5:12]:
            cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)

    visible_chars_per_line = [28, 28, 58, 34, 16, 11, 11, 11, 14, 14, 13, 13]
    for row_index in range(2, sheet.max_row + 1):
        estimated_lines = 1
        for column_index, chars_per_line in enumerate(visible_chars_per_line, start=1):
            text = str(sheet.cell(row_index, column_index).value or "")
            wrapped_lines = sum(
                max(1, (len(line) + chars_per_line - 1) // chars_per_line)
                for line in text.splitlines() or [""]
            )
            estimated_lines = max(estimated_lines, wrapped_lines)
        sheet.row_dimensions[row_index].height = min(240, max(42, estimated_lines * 15))
        sheet.cell(row_index, 8).number_format = "0.000000"
        sheet.cell(row_index, 11).number_format = "0"
        sheet.cell(row_index, 12).number_format = "0"

    column_widths = {
        "A": 30,
        "B": 30,
        "C": 60,
        "D": 36,
        "E": 18,
        "F": 13,
        "G": 13,
        "H": 13,
        "I": 16,
        "J": 16,
        "K": 15,
        "L": 15,
    }
    for column, width in column_widths.items():
        sheet.column_dimensions[column].width = width

    if results:
        table = Table(displayName="Smoke20Results", ref=f"A1:L{sheet.max_row}")
        sheet.add_table(table)
    sheet.auto_filter.ref = f"A1:L{sheet.max_row}"

    temp_path = output_path.with_name(f".{output_path.name}.tmp.xlsx")
    workbook.save(temp_path)
    temp_path.replace(output_path)


# ── 1. 加载模型（仅一次） ──────────────────────────────────────────
set_seed(ANDROID_SEED)
host_device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

checkpoint = CKPT
checkpoint_type = CheckpointType.from_checkpoint(checkpoint)

tokenizer = get_tokenizer(checkpoint)

quant_model_cls = QuantizedSplitModelWrapper
fp_model_cls = Qwen3_0_6B_PreSplit

extra = {}
if checkpoint_type.is_aimet_onnx():
    final_model_cls = quant_model_cls
    extra["fp_model"] = fp_model_cls.from_pretrained()
else:
    final_model_cls = fp_model_cls

model_params = {"host_device": host_device, **extra, "checkpoint": checkpoint}
print(f"Loading model from {checkpoint} ...", flush=True)
model = final_model_cls.from_pretrained(**model_params).to(host_device)

generator = make_generator(
    model,
    sequence_length=SEQUENCE_LENGTH,
    context_length=CONTEXT_LENGTH,
    model_cls=fp_model_cls,
)

end_token_ids = []
for token in END_TOKENS:
    token_ids = tokenizer.encode(token, add_special_tokens=False)
    if len(token_ids) == 1:
        end_token_ids.append(token_ids[0])
if tokenizer.eos_token_id is not None:
    end_token_ids.append(tokenizer.eos_token_id)

generator.generation_config = make_generation_config(
    ANDROID_MAX_OUTPUT_TOKENS,
    end_token_ids,
    tokenizer.pad_token_id,
)

print_runtime_parameters(
    host_device,
    checkpoint_type,
    end_token_ids,
    tokenizer.pad_token_id,
)


def make_raw_prompt(user_prompt):
    """生成与 Genie CLI/Android PromptBuilder 完全一致的 raw prompt。"""
    user_prompt = user_prompt.rstrip("\r\n")
    return (
        "<|im_start|>system\n"
        "English request classifier.\n"
        "Unsafe/illegal/harmful -> REJECT\n"
        "Supported vehicle control -> exact command\n"
        "Otherwise -> UNKNOWN\n"
        "Priority: REJECT > vehicle command > UNKNOWN.\n"
        "Output only the result.\n"
        "<|im_end|>\n"
        "<|im_start|>user\n"
        f"{user_prompt}\n"
        "<|im_end|>\n"
        "<|im_start|>assistant\n"
    )


# ── 2. 批量推理（复用同一模型） ────────────────────────────────────
print(f'==== checkpoint={CKPT} tag={TAG} ====', flush=True)
print(f'Excel result: {EXCEL_RESULT}', flush=True)
print(f'JSON result : {JSON_RESULT}', flush=True)
results = []
t_all = time.time()

for s in samples:
    t0 = time.time()
    input_ids_list = []
    genie_input_len = 0
    max_new_tokens = 0
    decode_token_count = 0
    try:
        # 与 Genie CLI/Android 一致：运行时根据 user 构造完整 prompt。
        prompt_processed = make_raw_prompt(s['user'])
        input_tokens = tokenizer(
            prompt_processed,
            return_tensors="pt",
            add_special_tokens=False,
        ).to(host_device)
        input_len = input_tokens["input_ids"].shape[1]
        input_ids_list = input_tokens["input_ids"][0].detach().cpu().tolist()
        token_pieces = tokenizer.convert_ids_to_tokens(input_ids_list)
        genie_input_len = input_len + ANDROID_BOS_TOKEN_COUNT
        if genie_input_len >= ANDROID_MAX_ALL_TOKENS:
            raise ValueError(
                f"Genie 输入 Token 数 {genie_input_len} 必须小于 "
                f"max_all_token={ANDROID_MAX_ALL_TOKENS}"
            )

        max_new_tokens = min(
            ANDROID_MAX_OUTPUT_TOKENS,
            ANDROID_MAX_ALL_TOKENS - genie_input_len,
        )
        print_case_tokens(
            s,
            prompt_processed,
            input_ids_list,
            token_pieces,
            genie_input_len,
            max_new_tokens,
        )

        # Android/Genie 每条语料都使用相同 seed；本脚本复用模型时显式重置。
        set_seed(ANDROID_SEED)
        case_generation_config = make_generation_config(
            max_new_tokens,
            end_token_ids,
            tokenizer.pad_token_id,
        )
        output_ids = generator.generate(
            inputs=input_tokens["input_ids"],
            attention_mask=input_tokens["attention_mask"],
            generation_config=case_generation_config,
        )
        new_tokens = output_ids[0, input_len:]
        decode_token_count = int(new_tokens.numel())
        out = tokenizer.decode(new_tokens, skip_special_tokens=True)
    except Exception as e:
        out = f'ERROR: {e}'

    elapsed = time.time() - t0
    got = parse_v5_output(out)
    exp = s['expected']
    ok = got is not None and got.get('intent') == exp.get('intent') and got.get('slot', {}) == exp.get('slot', {})
    intent_ok = got is not None and got.get('intent') == exp.get('intent')
    results.append({
        'id': s['id'], 'user': s['user'], 'expected': exp, 'got': got,
        'intent_ok': intent_ok, 'exact_ok': ok, 'secs': round(elapsed, 6),
        'input_token_count': len(input_ids_list),
        'genie_input_token_count': genie_input_len,
        'input_token_ids': input_ids_list,
        'max_new_tokens': max_new_tokens,
        'decode_token_count': decode_token_count,
        'raw_output': out,
        'raw_tail': out[-1200:],
    })
    status = 'PASS' if ok else ('INTENT-OK/SLOT-DIFF' if intent_ok else 'FAIL')
    print(f"[{s['id']:>2}] {status:<20} {s['user'][:40]}  ({elapsed:.1f}s)", flush=True)
    json.dump(results, JSON_RESULT.open('w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    write_excel_report(results, EXCEL_RESULT)

n_pass = sum(r['exact_ok'] for r in results)
n_intent = sum(r['intent_ok'] for r in results)
print(f"\n==== DONE tag={TAG} exact={n_pass}/20 intent={n_intent}/20 total={round(time.time()-t_all,1)}s ====")
print(f"Excel result: {EXCEL_RESULT}")
print(f"JSON result : {JSON_RESULT}")
generator.model.cleanup()
