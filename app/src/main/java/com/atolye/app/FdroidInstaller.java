package com.atolye.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * F-Droid'in resmi deposundan (f-droid.org/repo) APK indirir ve Android'in
 * PackageInstaller'i ile kurar. Sira (queue) burada, arka planda calisir; uygulama
 * kapansa da InstallService on plan servisi sayesinde devam eder. Android yeni
 * kurulumlarda sistemin "Yukle" onayini ister; uygulama arka plandaysa onay
 * bildirim olarak gelir, uygulamaya donunce de kendiliginden acilir.
 *
 * Olaylar (Listener.onEvent):
 *   {type:"status", pkg, state:"resolving|downloading|installing|done|error|notfound|cancelled", msg}
 *   {type:"progress", pkg, done, total, speed}
 */
class FdroidInstaller {
    interface Listener {
        void onEvent(JSONObject event);
        /** Onay ekranini ac. Acilamadiysa (uygulama arka planda) false doner. */
        boolean launchUserAction(Intent confirm);
    }

    /** Servis tarafi: bildirim guncelle, onay bildirimi goster, sira bitince dur. */
    interface Host {
        void onProgress(String name, String text, int pct);
        void onNeedsConfirm(String pkg, Intent confirm);
        void onConfirmHandled();
        void onQueueFinished();
    }

    private static FdroidInstaller instance;

    static synchronized FdroidInstaller get(Context ctx) {
        if (instance == null) instance = new FdroidInstaller(ctx);
        return instance;
    }

    private static final String API = "https://f-droid.org/api/v1/packages/";
    private static final String REPO = "https://f-droid.org/repo/";
    private static final String ACTION = "com.atolye.app.INSTALL_STATUS";
    private static final String RELEASES = "https://api.github.com/repos/MustafaCanDere/-Atolye-Android/releases/latest";

    private static final long CONFIRM_TIMEOUT_MS = 15 * 60 * 1000L;

    private final Context ctx;
    private volatile Listener listener;
    private volatile Host host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver receiver;
    private final Object lock = new Object();

    private volatile String current;
    private volatile boolean cancelled;
    private volatile int sessionId = -1;
    private volatile Intent pendingConfirm;
    private volatile boolean running;
    private String finalState; // lock ile korunur
    private final List<JSONObject> backlog = new ArrayList<>();

    private FdroidInstaller(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                onInstallStatus(intent);
            }
        };
        ContextCompat.registerReceiver(this.ctx, receiver, new IntentFilter(ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    void setListener(Listener l) {
        listener = l;
        if (l == null) return;
        List<JSONObject> copy;
        synchronized (backlog) { copy = new ArrayList<>(backlog); backlog.clear(); }
        for (JSONObject o : copy) l.onEvent(o);
    }

    void setHost(Host h) { host = h; }

    boolean isRunning() { return running; }

    /** Uygulama one gelince bekleyen onay ekranini tekrar ac. */
    synchronized void resumePendingConfirm() {
        // Tek seferlik: ayni onay ekrani iki kez acilirsa ikincisi kurulumu bozar (Failed).
        Intent c = pendingConfirm;
        Listener l = listener;
        if (c != null && l != null && l.launchUserAction(c)) {
            pendingConfirm = null;
            Host h = host;
            if (h != null) h.onConfirmHandled();
        }
    }

    /** Paketleri sirayla indirip kurar. Her paket icin olaylar gelir, sonunda {type:"queue",state:"finished"}. */
    void installAll(final List<String> pkgs, final List<String> names) {
        cancelled = false;
        running = true;
        synchronized (backlog) { backlog.clear(); }
        worker.execute(() -> {
            for (int i = 0; i < pkgs.size(); i++) {
                if (cancelled) { status(pkgs.get(i), "cancelled", "Durduruldu"); continue; }
                String pkg = pkgs.get(i);
                String name = i < names.size() ? names.get(i) : pkg;
                current = pkg;
                Host h = host;
                if (h != null) h.onProgress(name, (i + 1) + "/" + pkgs.size() + " · hazırlanıyor", -1);
                try {
                    run(pkg, name, i + 1, pkgs.size());
                } catch (Exception e) {
                    status(pkg, cancelled ? "cancelled" : "error", e.getMessage());
                }
            }
            current = null;
            running = false;
            pendingConfirm = null;
            JSONObject o = new JSONObject();
            try { o.put("type", "queue"); o.put("state", "finished"); } catch (JSONException ignored) { }
            emit(o);
            Host h = host;
            if (h != null) h.onQueueFinished();
        });
    }

    /** Atolye'nin kendisini gunceller: APK'yi indirip ayni oturum akisiyla kurar. */
    void installSelf(final String url) {
        final String pkg = ctx.getPackageName();
        worker.execute(() -> {
            current = pkg;
            curName = "Atölye"; curIdx = 1; curTotal = 1;
            try {
                File dir = new File(ctx.getCacheDir(), "apk");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Önbellek klasörü açılamadı");
                File apk = new File(dir, "self.apk");
                status(pkg, "downloading", null);
                download(url, apk, pkg);
                status(pkg, "installing", null);
                synchronized (lock) { finalState = null; }
                commit(pkg, apk);
                awaitResult(pkg);
            } catch (Exception e) {
                status(pkg, "error", e.getMessage());
            }
            current = null;
        });
    }

    /** GitHub'daki son surumu kontrol eder: {type:"update", version, code, url} ya da {type:"update", none:true}. */
    void checkUpdate(final long myCode) {
        worker.execute(() -> {
            JSONObject o = new JSONObject();
            try {
                o.put("type", "update");
                HttpURLConnection c = open(RELEASES);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                JSONObject r = new JSONObject(readAll(c.getInputStream()));
                String tag = r.optString("tag_name", "");
                long code = -1;
                int dot = tag.lastIndexOf('.');
                if (dot >= 0) try { code = Long.parseLong(tag.substring(dot + 1)); } catch (NumberFormatException ignored) { }
                String url = null;
                JSONArray as = r.optJSONArray("assets");
                if (as != null) for (int i = 0; i < as.length(); i++) {
                    String u = as.getJSONObject(i).optString("browser_download_url");
                    if (u.endsWith(".apk")) { url = u; break; }
                }
                if (code > myCode && url != null) {
                    o.put("version", tag.startsWith("v") ? tag.substring(1) : tag);
                    o.put("code", code);
                    o.put("url", url);
                } else o.put("none", true);
            } catch (Exception e) {
                try { o.put("none", true); o.put("msg", e.getMessage()); } catch (JSONException ignored) { }
            }
            Listener l = listener;
            if (l != null) l.onEvent(o);
        });
    }

    void cancel() {
        cancelled = true;
        int id = sessionId;
        if (id != -1) {
            try { ctx.getPackageManager().getPackageInstaller().abandonSession(id); } catch (Exception ignored) { }
            sessionId = -1;
        }
        finishWait("cancelled");
    }


    private void run(String pkg, String name, int idx, int total) throws IOException, JSONException {
        this.curName = name; this.curIdx = idx; this.curTotal = total;
        status(pkg, "resolving", null);
        List<Long> codes = resolveVersions(pkg);
        if (codes == null) {
            status(pkg, "notfound", "F-Droid'de bulunamadı");
            return;
        }

        File dir = new File(ctx.getCacheDir(), "apk");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Önbellek klasörü açılamadı");
        clean(dir);

        Set<String> abis = new HashSet<>(Arrays.asList(Build.SUPPORTED_ABIS));
        String lastProblem = "Uygun APK bulunamadı";
        for (long code : codes) {
            if (cancelled) { status(pkg, "cancelled", null); return; }
            File apk = new File(dir, pkg + "_" + code + ".apk");
            status(pkg, "downloading", null);
            try {
                download(REPO + pkg + "_" + code + ".apk", apk, pkg);
            } catch (IOException e) {
                lastProblem = "İndirme hatası: " + e.getMessage();
                apk.delete();
                continue;
            }
            if (cancelled) { apk.delete(); status(pkg, "cancelled", null); return; }
            if (!abiCompatible(apk, abis)) {
                // ABI'ye gore bolunmus surumlerde (arm64, armv7, x86) dogru olani bul.
                lastProblem = "Bu işlemciye uygun sürüm yok";
                apk.delete();
                continue;
            }
            status(pkg, "installing", null);
            Host h = host;
            if (h != null) h.onProgress(name, idx + "/" + total + " · kuruluyor", -1);
            if (ShizukuHelper.ready()) {
                // Shizuku: onay sormadan kur
                String err = ShizukuHelper.install(apk, ctx.getPackageName());
                if (err == null) { apk.delete(); status(pkg, "done", "Onaysız kuruldu (Shizuku)"); return; }
                lastProblem = err; // olmadiysa normal onayli kuruluma dus
            }
            synchronized (lock) { finalState = null; }
            commit(pkg, apk);
            awaitResult(pkg);
            return;
        }
        status(pkg, "error", lastProblem);
    }

    private volatile String curName;
    private volatile int curIdx, curTotal;

    /** Kurulum sonucu (ya da iptal) gelene kadar bekler; boylece sira bir sonrakine gecmez. */
    private void awaitResult(String pkg) {
        long end = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        synchronized (lock) {
            while (finalState == null) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try { lock.wait(left); } catch (InterruptedException e) { break; }
            }
            if (finalState == null) {
                int id = sessionId;
                if (id != -1) {
                    try { ctx.getPackageManager().getPackageInstaller().abandonSession(id); } catch (Exception ignored) { }
                    sessionId = -1;
                }
                pendingConfirm = null;
                status(pkg, "cancelled", "Onay verilmedi (zaman aşımı)");
            } else if ("cancelled".equals(finalState) && cancelled) {
                status(pkg, "cancelled", "Durduruldu");
            }
        }
    }

    private void finishWait(String state) {
        synchronized (lock) {
            finalState = state;
            lock.notifyAll();
        }
    }

    /** Onerilen surum ve ayni surum adindaki diger (ABI) kodlari, buyukten kucuge. */
    private List<Long> resolveVersions(String pkg) throws IOException, JSONException {
        HttpURLConnection c = open(API + pkg);
        int code = c.getResponseCode();
        if (code == 404) return null;
        if (code != 200) throw new IOException("F-Droid yanıtı " + code);
        JSONObject o = new JSONObject(readAll(c.getInputStream()));
        long suggested = o.optLong("suggestedVersionCode", -1);
        JSONArray arr = o.optJSONArray("packages");
        String suggestedName = null;
        List<Long> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                if (p.optLong("versionCode", -1) == suggested) suggestedName = p.optString("versionName");
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                long vc = p.optLong("versionCode", -1);
                if (vc <= 0 || out.contains(vc)) continue;
                if (suggestedName == null || suggestedName.equals(p.optString("versionName"))) out.add(vc);
            }
        }
        Collections.sort(out, Collections.reverseOrder());
        if (suggested > 0) { out.remove(Long.valueOf(suggested)); out.add(0, suggested); }
        if (out.isEmpty()) throw new IOException("Sürüm bilgisi yok");
        return out;
    }

    private void download(String url, File dest, String pkg) throws IOException {
        HttpURLConnection c = open(url);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        long total = c.getContentLengthLong();
        long done = 0, lastEmit = 0, t0 = System.currentTimeMillis();
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled) throw new IOException("İptal edildi");
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastEmit > 200 || done == total) {
                    lastEmit = now;
                    progress(pkg, done, total, done * 1000.0 / Math.max(1, now - t0));
                    Host h = host;
                    if (h != null) h.onProgress(curName, curIdx + "/" + curTotal + " · indiriliyor",
                            total > 0 ? (int) (done * 100 / total) : -1);
                }
            }
        }
        progress(pkg, done, total > 0 ? total : done, 0);
    }

    private static boolean abiCompatible(File apk, Set<String> abis) {
        Set<String> found = new HashSet<>();
        try (ZipFile z = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.startsWith("lib/")) {
                    int s = n.indexOf('/', 4);
                    if (s > 4) found.add(n.substring(4, s));
                }
            }
        } catch (IOException e) {
            return false; // bozuk dosya
        }
        if (found.isEmpty()) return true; // yerel kod yok, her cihazda calisir
        for (String a : found) if (abis.contains(a)) return true;
        return false;
    }

    private void commit(String pkg, File apk) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        params.setSize(apk.length());
        // Android 12+: daha once Atolye'nin kurdugu uygulamalarin guncellemeleri onaysiz kurulur.
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER);
        }
        int id = pi.createSession(params);
        sessionId = id;
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(ACTION).setPackage(ctx.getPackageName()).putExtra("pkg", pkg);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent p = PendingIntent.getBroadcast(ctx, id, i, flags);
            s.commit(p.getIntentSender());
        } catch (IOException | RuntimeException e) {
            try { pi.abandonSession(id); } catch (Exception ignored) { }
            sessionId = -1;
            throw e;
        } finally {
            apk.delete();
        }
    }

    private void onInstallStatus(Intent intent) {
        String pkg = intent.getStringExtra("pkg");
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm;
            if (Build.VERSION.SDK_INT >= 33) confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            else confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            Listener l = listener;
            boolean shown = l != null && l.launchUserAction(confirm);
            // Gosterildiyse saklama; yoksa geri donunce ya da bildirime dokununca bir kez acilir.
            pendingConfirm = shown ? null : confirm;
            Host h = host;
            if (h != null) {
                if (shown) h.onConfirmHandled();
                else h.onNeedsConfirm(pkg, confirm);
            }
            return;
        }
        sessionId = -1;
        pendingConfirm = null;
        Host h = host;
        if (h != null) h.onConfirmHandled();
        if (cancelled) { finishWait("cancelled"); return; }
        if (st == PackageInstaller.STATUS_SUCCESS) status(pkg, "done", null);
        else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) status(pkg, "cancelled", "Kurulum onaylanmadı");
        else status(pkg, "error", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        finishWait("final");
    }

    // ---- yardimcilar ----
    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Atolye-Android");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in; ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void clean(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private void status(String pkg, String state, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "status");
            o.put("pkg", pkg);
            o.put("state", state);
            if (msg != null) o.put("msg", msg);
        } catch (JSONException ignored) { }
        emit(o);
    }

    private void progress(String pkg, long done, long total, double speed) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "progress");
            o.put("pkg", pkg);
            o.put("done", done);
            o.put("total", total);
            o.put("speed", speed);
        } catch (JSONException ignored) { }
        Listener l = listener;
        if (l != null) l.onEvent(o); // ilerleme olaylari biriktirilmez
    }

    /** Arayuz yoksa (uygulama kapali) sonuc olaylarini sakla, geri gelince ilet. */
    private void emit(JSONObject o) {
        Listener l = listener;
        if (l != null) { l.onEvent(o); return; }
        synchronized (backlog) { backlog.add(o); }
    }
}
