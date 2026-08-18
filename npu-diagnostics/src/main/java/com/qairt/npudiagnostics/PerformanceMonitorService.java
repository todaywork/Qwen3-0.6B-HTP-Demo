package com.qairt.npudiagnostics;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Samples the inference PID without loading QNN or Genie in this process. */
public final class PerformanceMonitorService extends Service {
    public static final String ACTION_START = "com.qairt.npudiagnostics.START_MONITOR";
    public static final String ACTION_STOP = "com.qairt.npudiagnostics.STOP_MONITOR";
    public static final String EXTRA_TARGET_PID = "target_pid";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String REPORT_FILE = "npu_diagnostics/latest-main-process.json";
    private static final String TAG = "NpuPerfMonitor";
    private ScheduledExecutorService sampler;
    private int targetPid;
    private String sessionId;
    private long startedMs, previousProcessTicks = -1, previousSystemTicks = -1;
    private double cpuSum, cpuPeak;
    private int cpuSamples, peakThreads;
    private long peakRssKb, peakPssKb;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_START.equals(intent.getAction())) {
            startSampling(intent.getIntExtra(EXTRA_TARGET_PID, -1),
                    intent.getStringExtra(EXTRA_SESSION_ID));
        } else if (ACTION_STOP.equals(intent.getAction())) stopSampling(true);
        return START_NOT_STICKY;
    }

    private synchronized void startSampling(int pid, String id) {
        stopSampling(false);
        if (pid <= 0) { stopSelf(); return; }
        targetPid = pid;
        sessionId = id == null ? String.valueOf(System.currentTimeMillis()) : id;
        startedMs = SystemClock.elapsedRealtime();
        previousProcessTicks = previousSystemTicks = -1;
        cpuSum = cpuPeak = 0; cpuSamples = peakThreads = 0; peakRssKb = peakPssKb = 0;
        sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(this::sampleSafely, 0, 200, TimeUnit.MILLISECONDS);
    }

    private void sampleSafely() {
        try {
            long processTicks = readProcessTicks(targetPid), systemTicks = readSystemTicks();
            if (previousProcessTicks >= 0 && systemTicks > previousSystemTicks) {
                double cpu = 100.0 * Runtime.getRuntime().availableProcessors()
                        * (processTicks - previousProcessTicks) / (systemTicks - previousSystemTicks);
                cpu = Math.max(0, cpu); cpuPeak = Math.max(cpuPeak, cpu); cpuSum += cpu; cpuSamples++;
            }
            previousProcessTicks = processTicks; previousSystemTicks = systemTicks;
            peakRssKb = Math.max(peakRssKb, readStatusValue(targetPid, "VmRSS:"));
            peakThreads = Math.max(peakThreads, (int) readStatusValue(targetPid, "Threads:"));
            if ((SystemClock.elapsedRealtime() - startedMs) % 1000 < 220)
                peakPssKb = Math.max(peakPssKb, readSmapsPssKb(targetPid));
        } catch (Exception e) { Log.w(TAG, "sample: " + e.getMessage()); }
    }

    private synchronized void stopSampling(boolean save) {
        if (sampler != null) { sampler.shutdownNow(); sampler = null; }
        if (save && targetPid > 0) writeReport();
        if (save) stopSelf();
    }

    private void writeReport() {
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", 1).put("sessionId", sessionId).put("targetPid", targetPid)
                    .put("sampleIntervalMs", 200).put("durationMs", SystemClock.elapsedRealtime() - startedMs);
            JSONObject cpu = new JSONObject();
            cpu.put("logicalCoreCount", Runtime.getRuntime().availableProcessors())
                    .put("processPeakPercent", round(cpuPeak))
                    .put("processAveragePercent", round(cpuSamples == 0 ? 0 : cpuSum / cpuSamples))
                    .put("sampleCount", cpuSamples);
            JSONObject memory = new JSONObject();
            memory.put("peakRssKb", peakRssKb)
                    .put("peakPssKb", peakPssKb == 0 ? JSONObject.NULL : peakPssKb)
                    .put("peakThreadCount", peakThreads);
            root.put("cpu", cpu).put("memory", memory);
            File output = new File(getFilesDir(), REPORT_FILE);
            if (output.getParentFile() != null) output.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(output, false)) { writer.write(root.toString(2)); }
        } catch (Exception e) { Log.e(TAG, "write report", e); }
    }

    private static double round(double v) { return Math.round(v * 10.0) / 10.0; }
    private static long readProcessTicks(int pid) throws Exception {
        String line = readFirstLine("/proc/" + pid + "/stat");
        String[] f = line.substring(line.lastIndexOf(')') + 2).split("\\s+");
        return Long.parseLong(f[11]) + Long.parseLong(f[12]);
    }
    private static long readSystemTicks() throws Exception {
        String[] f = readFirstLine("/proc/stat").trim().split("\\s+"); long sum = 0;
        for (int i = 1; i < f.length; i++) sum += Long.parseLong(f[i]); return sum;
    }
    private static long readStatusValue(int pid, String key) throws Exception {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + pid + "/status"))) {
            String line; while ((line = r.readLine()) != null) if (line.startsWith(key)) {
                String digits = line.substring(key.length()).replaceAll("[^0-9]", "");
                return digits.isEmpty() ? 0 : Long.parseLong(digits);
            }
        } return 0;
    }
    private static long readSmapsPssKb(int pid) throws Exception {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + pid + "/smaps_rollup"))) {
            String line; while ((line = r.readLine()) != null) if (line.startsWith("Pss:"))
                return Long.parseLong(line.replaceAll("[^0-9]", ""));
        } return 0;
    }
    private static String readFirstLine(String path) throws Exception {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) { return r.readLine(); }
    }
    @Override public void onDestroy() { stopSampling(false); super.onDestroy(); }
}
