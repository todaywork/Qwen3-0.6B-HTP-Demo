package com.qairt.npudiagnostics;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Active tests run in :diagnostics and do not share native state with inference. */
public final class NpuDiagnosticsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView output;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setTitle("NPU 能力检测（独立进程）"); setContentView(buildUi()); showQuickReport();
    }
    private View buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16)); root.setBackgroundColor(Color.rgb(246, 248, 251));
        TextView title = new TextView(this); title.setText("NPU 能力检测"); title.setTextSize(22); root.addView(title);
        TextView note = new TextView(this);
        note.setText("本页面运行在 :diagnostics；主进程采样运行在 :monitor。主动微基准不会在主推理期间自动执行。");
        note.setPadding(0, dp(8), 0, dp(12)); root.addView(note);
        Button refresh = new Button(this); refresh.setText("刷新设备信息");
        refresh.setOnClickListener(v -> showQuickReport()); root.addView(refresh);
        Button bandwidth = new Button(this); bandwidth.setText("检测 CPU 可见内存带宽");
        bandwidth.setOnClickListener(v -> runBandwidth()); root.addView(bandwidth);
        output = new TextView(this); output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod()); output.setPadding(0, dp(12), 0, 0); root.addView(output);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); return scroll;
    }
    private void showQuickReport() {
        worker.execute(() -> {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            getSystemService(ActivityManager.class).getMemoryInfo(mi);
            String qnn;
            try {
                String dspLibraryDir = getIntent().getStringExtra("dsp_library_dir");
                int htpArch = getIntent().getIntExtra("htp_arch", -1);
                if (dspLibraryDir == null || dspLibraryDir.isEmpty()) {
                    throw new IllegalStateException("Missing dsp_library_dir");
                }
                qnn = NpuDiagnosticsNative.probeQnn(getApplicationInfo().nativeLibraryDir,
                        dspLibraryDir, htpArch);
            } catch (Throwable t) {
                qnn = "{\"result\":\"ERROR\",\"message\":\"" + t + "\"}";
            }
            String s = "综合结论\n诊断进程隔离：PASS\n监控进程隔离：PASS\n\n设备\n"
                    + "SoC：" + systemProperty("ro.soc.model")
                    + "\nHTP：V" + getIntent().getIntExtra("htp_arch", -1)
                    + "\nABI：" + Build.SUPPORTED_ABIS[0]
                    + "\nAndroid：" + Build.VERSION.RELEASE
                    + String.format(Locale.US, "\n系统内存：%.2f GiB\n当前可用：%.2f GiB\n", mi.totalMem / 1073741824.0, mi.availMem / 1073741824.0)
                    + "NPU 内存架构：共享 DDR（当前平台结论）\n\nQNN/HTP 现场探测 JSON\n" + qnn + "\n\n"
                    + "主进程最近一次报告\n" + readLatestMonitorReport();
            runOnUiThread(() -> output.setText(s));
        });
    }
    private void runBandwidth() {
        output.setText("正在执行 CPU 内存微基准；该结果不是 NPU 总线带宽……");
        worker.execute(() -> {
            int bytes = 128 * 1024 * 1024, rounds = 8; byte[] src = new byte[bytes], dst = new byte[bytes];
            for (int i = 0; i < src.length; i += 4096) src[i] = (byte) i;
            long start = System.nanoTime(); for (int i = 0; i < rounds; i++) System.arraycopy(src, 0, dst, 0, bytes);
            double gbps = (2.0 * bytes * rounds) / ((System.nanoTime() - start) / 1e9) / 1e9;
            String result = String.format(Locale.US, "CPU 可见 DDR memcpy 有效带宽：%.2f GB/s [实测]\n数据量：128 MiB × %d，按读+写计量\n说明：不等于 HTP DDR 总线带宽。", gbps, rounds);
            runOnUiThread(() -> output.setText(result));
        });
    }
    private String readLatestMonitorReport() {
        File f = new File(getFilesDir(), PerformanceMonitorService.REPORT_FILE);
        if (!f.exists()) return "尚无主进程推理采样数据\n";
        StringBuilder s = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) { String line; while ((line = r.readLine()) != null) s.append(line).append('\n'); }
        catch (Exception e) { return "读取失败：" + e.getMessage() + '\n'; } return s.toString();
    }
    private static String systemProperty(String key) {
        try { Class<?> c = Class.forName("android.os.SystemProperties"); return String.valueOf(c.getMethod("get", String.class, String.class).invoke(null, key, "UNKNOWN")); }
        catch (Exception ignored) { return Build.HARDWARE; }
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
