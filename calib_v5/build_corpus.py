# -*- coding: utf-8 -*-
"""Render train_v5.jsonl into a ChatML calibration corpus using the model's own
chat_template.jinja, so the corpus byte-format matches training/inference exactly."""
import json
from pathlib import Path

import jinja2

root = Path(__file__).parent
template_src = (root / "chat_template.jinja").read_text(encoding="utf-8")
env = jinja2.Environment(trim_blocks=False, lstrip_blocks=False)
# Qwen templates use `messages[::-1]` etc.; standard jinja2 sandbox handles it.
template = env.from_string(template_src)

out_path = root / "generated_calibration_v5.txt"
n_ok, n_bad = 0, 0
with open(root / "train_v5.jsonl", encoding="utf-8") as fin, open(
    out_path, "w", encoding="utf-8", newline="\n"
) as fout:
    for i, line in enumerate(fin, 1):
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
            msgs = obj["messages"]
            text = template.render(messages=msgs, add_generation_prompt=False)
            fout.write(text)
            n_ok += 1
        except Exception as e:  # noqa: BLE001
            n_bad += 1
            print(f"[skip] line {i}: {e}")

print(f"ok={n_ok} bad={n_bad} out={out_path} size={out_path.stat().st_size}")

# sanity check: print first rendered sample
with open(out_path, encoding="utf-8") as f:
    head = f.read(800)
print("----- HEAD -----")
print(head)
