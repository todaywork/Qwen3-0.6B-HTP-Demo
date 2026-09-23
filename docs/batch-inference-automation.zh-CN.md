# Excel 批量推理自动化脚本

脚本 [`scripts/语料批跑自动化/run_batch_inference.py`](../scripts/%E8%AF%AD%E6%96%99%E6%89%B9%E8%B7%91%E8%87%AA%E5%8A%A8%E5%8C%96/run_batch_inference.py) 完成以下流程：

1. 校验本地 `.xlsx` 文件和 ADB 设备。
2. 将 Excel 推送到应用批跑目录 `/data/local/tmp/nlutest/nlutest.xlsx`。
3. 重启并打开应用，但不自动开始推理。
4. 应用默认选中“推送目录中的 Excel”，等待用户点击“开始批跑推送目录”。
5. 通过 Logcat 监控开始、完成和失败事件。
6. 完成后从日志取得包内私有结果 Excel 的实际路径，通过 `run-as` 导出到电脑当前目录下的 `batch_results`。

## 前提

- Python 3；双击入口优先使用 Windows Python Launcher `py -3`，不可用时回退到 `python`。
- `adb` 已加入 `PATH`，设备状态为 `device`。
- 设备已安装由当前源码构建的 Debug 版 `com.qairt.qwen3htp` 应用。脚本使用 `run-as` 读取包内私有结果，非 debuggable 的 Release 包默认不支持该操作。
- 模型文件已经按项目要求部署到 `/data/local/tmp/genie_qwen3_quality`。
- 输入文件必须是非空 `.xlsx`。第一张工作表的第一行必须是表头；从第二行开始，A 列为输入提示词，B 列为可选的预期结果。

## 使用

### 双击运行

将以下三个文件放在同一个目录：

- `双击运行批量推理.cmd`
- `run_batch_inference.py`
- 要测试的 `.xlsx` 文件

该目录中只能保留一个 `.xlsx` 文件。直接双击 `双击运行批量推理.cmd`，脚本会自动选择同目录的 Excel；执行成功或失败后窗口都会停留，按任意键才关闭。

双击入口会在上传新任务前，删除设备端以下目录中的历史 `.xlsx` 推理结果：

```text
/data/user/0/com.qairt.qwen3htp/files/batch_results
```

删除范围仅限该目录第一层的 `.xlsx` 文件，不删除其他文件或子目录。电脑端已经导出的历史结果会保留。

新结果默认保存到这三个文件所在目录下的 `batch_results`。脚本打开 Android 应用后，仍需用户在设备上点击“开始批跑推送目录”。

### 命令行运行

在项目根目录执行：

```powershell
py -3 .\scripts\语料批跑自动化\run_batch_inference.py C:\data\nlutest.xlsx
```

命令行模式默认不清理设备历史结果；需要清理时增加 `--clean-device-history`：

```powershell
py -3 .\scripts\语料批跑自动化\run_batch_inference.py `
  C:\data\nlutest.xlsx `
  --clean-device-history
```

脚本打开应用后，在 Android 设备上保持“推送目录中的 Excel”选中并点击
“开始批跑推送目录”。脚本检测到点击事件后会继续等待，推理完成时通过
`run-as` 从应用私有目录导出结果。

指定设备和本地导出目录：

```powershell
py -3 .\scripts\语料批跑自动化\run_batch_inference.py `
  C:\data\nlutest.xlsx `
  --output-directory C:\data\qwen-results `
  --serial 192.168.43.35:5555
```

指定模型参数和超时：

```powershell
py -3 .\scripts\语料批跑自动化\run_batch_inference.py `
  C:\data\nlutest.xlsx `
  --max-all-token 256 `
  --context-size 512 `
  --click-timeout-seconds 900 `
  --inference-timeout-seconds 7200
```

`--click-timeout-seconds` 和 `--inference-timeout-seconds` 默认均为 `0`，表示不限制等待【开始】点击的时间和批量推理耗时。等待期间可使用 `Ctrl+C` 停止电脑端脚本；这不会自动停止设备上的推理。

## 自动化事件

应用使用 Logcat 标签 `Qwen3HtpDemo` 输出稳定的 ASCII 事件：

```text
BATCH_AUTOMATION EVENT=STARTED total=10 input=/data/local/tmp/nlutest
BATCH_AUTOMATION EVENT=COMPLETED status=success processed=10 failed=0 total=10 output=/data/user/0/com.qairt.qwen3htp/files/batch_results/batch_Qwen3-0.6B-HTP_20260807_190000.xlsx
```

完成状态可能为：

- `success`：全部条目推理成功。
- `partial_failure`：批次已结束并导出，但至少一条失败。
- `stopped`：用户点击停止，已导出当前结果。
- `no_results`：没有可导出的结果，脚本会报错退出。

配置错误输出 `EVENT=REJECTED`，运行时致命错误输出 `EVENT=FAILED`；脚本检测到后立即报错，不会无限等待。

## 注意

- 脚本会先强制停止再启动应用；应用默认选中推送目录来源，因此会读取刚推送的 Excel，并避免读取到旧进程日志。
- 应用会按文件名顺序合并读取 `/data/local/tmp/nlutest/` 第一层的全部 `.xlsx` 文件；使用脚本前请确认目录内没有需要排除的其他 Excel。
- 脚本不会代替用户点击“开始批跑推送目录”。
- 如果本地导出目录已有同名文件，脚本会为新文件增加 `_pulled_时间戳`，不会覆盖原文件。
- 双击入口会清理设备历史结果；直接运行 Python 时只有传入 `--clean-device-history` 才会清理。
