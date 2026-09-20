# -*- coding: utf-8 -*-
"""
一键生成量化校准语料（generated_calibration.txt）

原理：
  训练/推理时模型看到的文本由 chat_template.jinja 渲染而成。
  本脚本用模型目录里自带的 chat_template.jinja 把 train jsonl
  （{"messages":[{role,content},...]} 格式）逐条渲染为 ChatML 原文并顺序拼接，
  保证语料与线上格式逐字节一致（含空 <think>\\n\\n</think> 块）。
  手写拼接字符串容易在换行/think 块上出错，不推荐。

用法（默认参数即为当前 v5 工程路径，直接运行即可）：
  python build_calibration_corpus.py
  python build_calibration_corpus.py --jsonl 其他数据.jsonl --out 输出.txt

依赖：jinja2（可选 transformers，用于打印 token 统计）
"""
import argparse
import json
import re
import sys
from pathlib import Path

import jinja2

# chat_template 为最后一轮 assistant 自动注入的空 think 块：
#   <|im_start|>assistant\n<think>\n\n</think>\n\n答案<|im_end|>
# 按部署要求（不带 think 标签）默认剥掉空 think 块，渲染结果变为：
#   <|im_start|>assistant\n答案<|im_end|>
EMPTY_THINK_RE = re.compile(r"<think>\s*</think>\n\n?")

DEFAULT_MODEL_DIR = r"E:\QualComm\models\Qwen3-0.6B_car_trained_v5"


def main() -> int:
    ap = argparse.ArgumentParser(description="生成量化校准语料（ChatML 渲染）")
    ap.add_argument("--model-dir", default=DEFAULT_MODEL_DIR,
                    help="模型目录（用于读取 chat_template.jinja 和 tokenizer）")
    ap.add_argument("--jsonl", default=None,
                    help="训练数据 jsonl，默认 <model-dir>/dataset/train_v5.jsonl")
    ap.add_argument("--out", default=None,
                    help="输出文件，默认 <jsonl 同目录>/generated_calibration.txt")
    ap.add_argument("--max-samples", type=int, default=0,
                    help="只取前 N 条（0=全部）")
    ap.add_argument("--keep-think", action="store_true",
                    help="保留模板自动注入的空 think 块（默认剥除，语料不带 think 标签）")
    args = ap.parse_args()

    model_dir = Path(args.model_dir)
    jsonl_path = Path(args.jsonl) if args.jsonl else model_dir / "dataset" / "train_v5.jsonl"
    out_path = Path(args.out) if args.out else jsonl_path.parent / "generated_calibration.txt"
    template_path = model_dir / "chat_template.jinja"

    for p, name in [(jsonl_path, "jsonl"), (template_path, "chat_template.jinja")]:
        if not p.is_file():
            print(f"[ERROR] 找不到 {name}: {p}")
            return 1

    template = jinja2.Environment().from_string(template_path.read_text(encoding="utf-8"))

    n_ok = n_bad = 0
    with open(jsonl_path, encoding="utf-8") as fin, open(out_path, "w", encoding="utf-8", newline="\n") as fout:
        for i, line in enumerate(fin, 1):
            if args.max_samples and n_ok >= args.max_samples:
                break
            line = line.strip()
            if not line:
                continue
            try:
                msgs = json.loads(line)["messages"]
                text = template.render(messages=msgs, add_generation_prompt=False)
                if not args.keep_think:
                    text = EMPTY_THINK_RE.sub("", text)
                fout.write(text)
                n_ok += 1
            except Exception as e:  # noqa: BLE001
                n_bad += 1
                print(f"[skip] line {i}: {e}")

    size = out_path.stat().st_size
    print(f"[OK] 样本 {n_ok} 条（跳过 {n_bad}） -> {out_path} ({size:,} 字节)")

    # 可选：token 统计（装了 transformers 才打印）
    try:
        from transformers import AutoTokenizer
        tok = AutoTokenizer.from_pretrained(str(model_dir))
        n_tok = len(tok(out_path.read_text(encoding="utf-8"), add_special_tokens=False)["input_ids"])
        print(f"[OK] 总 token 数 {n_tok:,} | 4096 token/batch ≈ {n_tok / 4096:.1f} 个 batch")
    except Exception:
        print("[INFO] 未安装 transformers，跳过 token 统计（不影响语料生成）")

    print()
    print("下一步（AutoDL 上替换生效语料，文件名必须一字不差）：")
    print("  V1=/root/autodl-tmp/qaihm-store/.qaihm/qai-hub-models/datasets/generated_calibration_qwen3_0_6b/v1")
    print('  cp "$V1/generated_calibration.txt" "$V1/generated_calibration.txt.bak_$(date +%Y%m%d)"   # 备份')
    print(f"  cp {out_path.name} \"$V1/generated_calibration.txt\"                              # 覆盖")
    return 0


if __name__ == "__main__":
    sys.exit(main())
