package com.atolye.app;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku varsa APK'lar "pm install" ile (ADB/shell yetkisi) onay sormadan kurulur.
 * Shizuku yoksa ya da izin verilmediyse normal PackageInstaller akisina donulur.
 */
final class ShizukuHelper {
    static final String PKG = "moe.shizuku.privileged.api";
    private static final int REQ = 4242;

    private ShizukuHelper() { }

    /** "ok" | "denied" | "notrunning" | "old" */
    static String state() {
        try {
            if (!Shizuku.pingBinder()) return "notrunning";
            if (Shizuku.isPreV11()) return "old";
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? "ok" : "denied";
        } catch (Throwable t) {
            return "notrunning";
        }
    }

    static boolean ready() { return "ok".equals(state()); }

    static void request() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQ);
            else if (Shizuku.pingBinder()) Shizuku.requestPermission(REQ);
        } catch (Throwable ignored) { }
    }

    /** Basariliysa null, degilse hata metni. */
    static String install(File apk, String installer) {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            String[] cmd = {"pm", "install", "-r", "-i", installer, "-S", String.valueOf(apk.length())};
            Process p = (Process) m.invoke(null, cmd, null, null);
            try (InputStream in = new FileInputStream(apk); OutputStream out = p.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
            String outText = read(p.getInputStream()) + read(p.getErrorStream());
            int code = p.waitFor();
            if (code == 0 && outText.contains("Success")) return null;
            String msg = outText.trim();
            return msg.isEmpty() ? "pm install kodu " + code : msg;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "Shizuku: " + c.getClass().getSimpleName() + (c.getMessage() != null ? " " + c.getMessage() : "");
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream i = in; ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }
}
