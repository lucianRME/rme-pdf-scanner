package org.synapseworks.m35.workerprobe;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Descriptor flags without a non-SDK API on Android26–29. Older devices must
 * permit the kernel's bounded self-fdinfo query; uncertainty refuses the file.
 * No document bytes, provider names or paths are read by this helper.
 */
public final class OfficeFd {
    private OfficeFd() {}
    public static int flags(ParcelFileDescriptor descriptor) throws Exception {
        if (Build.VERSION.SDK_INT >= 30) return Os.fcntlInt(descriptor.getFileDescriptor(), OsConstants.F_GETFL, 0);
        int fd = descriptor.getFd(); if (fd < 0) throw new IOException();
        byte[] data = new byte[4097]; int size = 0;
        try (FileInputStream input = new FileInputStream("/proc/self/fdinfo/" + fd)) {
            while (size < data.length) {
                int count = input.read(data, size, data.length - size);
                if (count < 0) break; if (count == 0) throw new IOException(); size += count;
            }
        }
        if (size == data.length) throw new IOException();
        Matcher value = Pattern.compile("(?m)^flags:\\s+([0-7]{1,16})$").matcher(new String(data, 0, size, StandardCharsets.US_ASCII));
        if (!value.find()) throw new IOException();
        long flags = Long.parseLong(value.group(1), 8);
        if (value.find() || flags > Integer.MAX_VALUE) throw new IOException();
        return (int)flags;
    }
}
