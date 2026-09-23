package com.qairt.qwen3htp;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;

/** Extract only this channel's DSP runtime, refreshing bytes after APK updates. */
final class DspRuntime {
    private static String preparedPath;

    static synchronized String prepare(Context context) {
        if (preparedPath != null) return preparedPath;
        String assetRoot = "qnn/v" + Architecture.HTP_ARCH + "/dsp";
        File directory = new File(context.getFilesDir(), assetRoot);
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create " + directory);
            }
            String[] names = context.getAssets().list(assetRoot);
            if (names == null || names.length == 0) throw new IOException("Missing " + assetRoot);
            for (String name : names) {
                try (InputStream input = context.getAssets().open(assetRoot + "/" + name);
                     FileOutputStream output = new FileOutputStream(new File(directory, name))) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
            }
            preparedPath = directory.getAbsolutePath();
            return preparedPath;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare channel DSP runtime", e);
        }
    }

    private DspRuntime() {}
}
