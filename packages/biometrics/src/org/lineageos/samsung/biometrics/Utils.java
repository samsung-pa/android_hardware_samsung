/*
 * Copyright (C) 2025 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.samsung.biometrics;

import android.util.Slog;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class Utils {
    private static final String TAG = "BiometricUtils";

    public static byte[] readFile(File file) {
        if (!file.exists()) {
            Slog.i(TAG, "File does not exist: " + file);
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int bytesRead = fis.read(data);
            if (bytesRead == -1) {
                Slog.w(TAG, "File was empty: " + file);
                return null;
            }
            return data;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to read file: " + file, e);
            return null;
        }
    }

    public static void writeFile(File file, byte[] data) {
        if (data == null) {
            return;
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write file: " + file, e);
        }
    }
}