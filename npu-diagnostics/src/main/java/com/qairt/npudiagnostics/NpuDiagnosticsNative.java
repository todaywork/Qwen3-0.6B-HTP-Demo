package com.qairt.npudiagnostics;

public final class NpuDiagnosticsNative {
    static { System.loadLibrary("npu_diagnostics"); }
    private NpuDiagnosticsNative() {}
    public static native String probeQnn(String nativeLibraryDir, String dspLibraryDir);
}
