# -*- coding: utf-8 -*-
"""生成 Qwen3 HTP Demo 的 so 加载链路架构图 -> docs/so-loading-chain.png"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(sys.executable).parent.parent.parent))
from daimon_runtime import setup_plot  # noqa: E402
setup_plot()  # noqa: E402

import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch  # noqa: E402

# 配色
C_APP = "#dbeafe"      # 应用目录（蓝）
C_APP_E = "#3b82f6"
C_SYS = "#e5e7eb"      # 系统目录（灰）
C_SYS_E = "#9ca3af"
C_EXT = "#dcfce7"      # 外部推送目录（绿）
C_EXT_E = "#22c55e"
C_PROC = "#fef3c7"     # 进程/代码（黄）
C_PROC_E = "#f59e0b"

fig, ax = plt.subplots(figsize=(16, 9.5))
ax.set_xlim(0, 16)
ax.set_ylim(0, 10)
ax.axis("off")
fig.suptitle("Qwen3 HTP Demo —— so 加载链路总览", fontsize=17, fontweight="bold", y=0.98)


def box(x, y, w, h, text, fc, ec, fs=10, dashed=False):
    p = FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.06",
                       fc=fc, ec=ec, lw=1.4, linestyle="--" if dashed else "-")
    ax.add_patch(p)
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=fs)


def arrow(x1, y1, x2, y2, label="", color="#374151", ls="-", lx=0.15, ly=0.0):
    a = FancyArrowPatch((x1, y1), (x2, y2), arrowstyle="-|>", mutation_scale=16,
                        color=color, lw=1.6, linestyle=ls)
    ax.add_patch(a)
    if label:
        ax.text((x1 + x2) / 2 + lx, (y1 + y2) / 2 + ly, label, fontsize=9,
                color=color, ha="left", va="center")


# ===== 第 1 行：APK 内部 =====
box(0.4, 8.3, 4.8, 1.3,
    "APK: jniLibs/arm64-v8a/\nlibQnnSystem / V68Stub / libQnnHtp\nlibGenie / NetRunExtensions + libqwen3genie(编译)",
    C_APP, C_APP_E, 9.5)
box(10.8, 8.3, 4.8, 1.3,
    "APK: assets/qnn/v68/dsp/\nlibQnnHtpV68Skel.so 等 DSP 库",
    C_APP, C_APP_E, 9.5)

# ===== 第 2 行：加载动作 =====
box(0.4, 6.6, 4.8, 1.2,
    "Java: GenieNative.loadLibraries()\nSystem.loadLibrary ①~⑤（顺序敏感：\nStub 必须先于 libQnnHtp）",
    C_PROC, C_PROC_E, 9.5)
box(10.8, 6.6, 4.8, 1.2,
    "DspRuntime.prepare()\nassets 提取 → filesDir",
    C_PROC, C_PROC_E, 9.5)

# ===== 第 3 行：宿主 so 实际加载位置 =====
box(0.4, 4.7, 4.8, 1.3,
    "应用 nativeLibraryDir（应用目录）\n/data/app/.../lib/arm64/\n（useLegacyPackaging=false，直接映射 APK）",
    C_APP, C_APP_E, 9.5)
box(6.3, 4.7, 3.5, 1.3,
    "系统基础库（系统目录）\n/system/lib64/\nlibc liblog libandroid libdl",
    C_SYS, C_SYS_E, 9.5)
box(10.8, 4.7, 4.8, 1.3,
    "DSP Skel 加载位置（应用私有目录）\n/data/data/<pkg>/files/qnn/v68/dsp/\nlibQnnHtpV68Skel.so",
    C_APP, C_APP_E, 9.5)

# ===== 第 4 行：模型 + vendor 兜底 =====
box(0.4, 2.5, 4.8, 1.2,
    "模型外部推送目录（不属于 APK）\n/data/local/tmp/genie_qwen3_quality/\npart1/part2.bin + tokenizer + htp配置",
    C_EXT, C_EXT_E, 9.5)
box(6.3, 2.5, 3.5, 1.2,
    "系统 vendor 兜底（系统目录）\n/vendor/lib/rfsa/adsp\nCDSP_LIBRARY_PATH 分号后段",
    C_SYS, C_SYS_E, 9.5, dashed=True)

# ===== DSP 芯片 =====
box(11.6, 0.8, 4.0, 0.9, "CDSP / HTP V68 NPU\n（Hexagon DSP 侧执行）", "#ede9fe", "#8b5cf6", 9.5)

# ===== 箭头 =====
arrow(2.8, 8.3, 2.8, 7.85, "打包进 APK")
arrow(13.2, 8.3, 13.2, 7.85, "打包进 APK")
arrow(2.8, 6.6, 2.8, 6.05, "System.loadLibrary 按序加载")
arrow(13.2, 6.6, 13.2, 6.05, "提取落地")
arrow(5.2, 5.35, 6.3, 5.35, "依赖解析", color=C_SYS_E)
arrow(2.8, 4.7, 2.8, 3.75, "Genie 读模型配置", color=C_EXT_E)
arrow(13.2, 4.7, 13.6, 1.75, "fastrpc 推入 DSP 加载", color="#8b5cf6", lx=-3.6)
arrow(9.8, 3.1, 12.0, 4.7, "仅兜底", color=C_SYS_E, ls="--", lx=0.1, ly=-0.35)
# 环境变量虚线（JNI 设置 → DSP 侧）
a = FancyArrowPatch((5.2, 7.2), (10.8, 7.2), arrowstyle="-|>", mutation_scale=14,
                    color="#8b5cf6", lw=1.4, linestyle=":")
ax.add_patch(a)
ax.text(8.0, 7.35, "JNI: setenv CDSP_LIBRARY_PATH = filesDir;/vendor/lib/rfsa/adsp",
        fontsize=9, color="#8b5cf6", ha="center")

# ===== 图例（底部左侧横排） =====
box(0.4, 0.8, 0.5, 0.45, "", C_APP, C_APP_E)
ax.text(1.0, 1.02, "应用目录（本项目 so 全部来源）", fontsize=9.5, va="center")
box(4.6, 0.8, 0.5, 0.45, "", C_SYS, C_SYS_E)
ax.text(5.2, 1.02, "系统目录（仅基础库/兜底）", fontsize=9.5, va="center")
box(8.4, 0.8, 0.5, 0.45, "", C_EXT, C_EXT_E)
ax.text(9.0, 1.02, "外部推送目录（模型）", fontsize=9.5, va="center")

out = Path(__file__).parent.parent / "docs" / "so-loading-chain.png"
fig.savefig(out, dpi=150, bbox_inches="tight")
print("saved:", out)
