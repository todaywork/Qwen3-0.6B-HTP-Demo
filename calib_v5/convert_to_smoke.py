# -*- coding: utf-8 -*-
"""
将 v5 数据集（jsonl）转换为 smoke20 评测格式（json）。

目标格式（与 /root/autodl-tmp/smoke20/smoke20_samples.json 一致）：
  [{"id": 0, "user": "...", "expected": {"intent": "CMD", "slot": {"k": "v"}}}, ...]

转换规则：
  - user     <- jsonl 的 input 字段（若只有 messages 则取 user 消息内容）
  - expected <- expected 字符串按 shlex 解析：第一个 token 为 intent，
                其余按 key value 两两配对为 slot（支持引号包裹的带空格值）
"""
import argparse
import json
import shlex
import sys
from pathlib import Path

DEFAULT_DIR = r"E:\QualComm\models\Qwen3-0.6B_car_trained_v5\dataset"


def parse_expected(ans: str):
    parts = shlex.split(ans)
    if not parts:
        raise ValueError("empty expected")
    intent = parts[0]
    rest = parts[1:]
    if len(rest) % 2 != 0:
        raise ValueError(f"奇数个槽位 token: {ans!r}")
    slot = dict(zip(rest[0::2], rest[1::2]))
    return {"intent": intent, "slot": slot}


def extract_user(obj: dict) -> str:
    if "input" in obj:
        return obj["input"]
    for m in obj["messages"]:
        if m["role"] == "user":
            return m["content"]
    raise ValueError("no user content")


def extract_expected_str(obj: dict) -> str:
    if "expected" in obj:
        return obj["expected"]
    return obj["messages"][-1]["content"]


def convert(jsonl_path: Path, out_path: Path) -> tuple[int, int]:
    results, bad = [], 0
    with open(jsonl_path, encoding="utf-8") as f:
        for i, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
                results.append({
                    "id": len(results),
                    "user": extract_user(obj),
                    "expected": parse_expected(extract_expected_str(obj)),
                })
            except Exception as e:  # noqa: BLE001
                bad += 1
                print(f"[skip] {jsonl_path.name} line {i}: {e}")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    return len(results), bad


def main() -> int:
    ap = argparse.ArgumentParser(description="v5 数据集 -> smoke20 评测 JSON")
    ap.add_argument("--dir", default=DEFAULT_DIR, help="数据集目录")
    ap.add_argument("--out-dir", default=None, help="输出目录（默认本脚本所在目录）")
    args = ap.parse_args()

    src = Path(args.dir)
    out_dir = Path(args.out_dir) if args.out_dir else Path(__file__).parent
    out_dir.mkdir(parents=True, exist_ok=True)

    for name in ["train_v5", "testset_v5_base", "testset_v5_complex"]:
        jp = src / f"{name}.jsonl"
        if not jp.is_file():
            print(f"[warn] 缺少 {jp}")
            continue
        op = out_dir / f"{name}_smoke.json"
        n, bad = convert(jp, op)
        print(f"[OK] {name}: {n} 条（跳过 {bad}） -> {op}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
