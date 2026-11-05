package com.atolye.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Uygulama ici guncelleme.
 * GitHub'daki son surumu (Releases / latest) kontrol eder; daha yeni bir surum varsa
 * kullaniciya sorar, APK'yi indirir ve Android'in PackageInstaller'i ile kurar.
 * Ayrica APK indirip elle kurmaya gerek kalmaz.
 */
public final class Updater {
    private static final String REPO = "MustafaCanDere/-Atolye-Android";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String ACTION = "com.atolye.app.UPDATE_STATUS";

    private static boolean checkedThisRun;

    private Updater() { }

    /** Uygulama acilisinda bir kez sessizce kontrol eder. */
    public static void checkOnStart(Activity a) {
        if (checkedThisRun) return;
        checkedThisRun = true;
        check(a, false);
    }

    /** userAsked=true ise "guncelsiniz" / hata mesajlarini da gosterir. */
    public static void check(final Activity a, final boolean userAsked) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(new String(get(LATEST), "UTF-8"));
                final String tag = rel.optString("tag_name", "").replaceFirst("^[vV]", "");
                final String notes = rel.optString("body", "").trim();
                String apk = null;
                long size = -1;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (as.optString("name").toLowerCase().endsWith(".apk")) {
                        apk = as.optString("browser_download_url");
                        size = as.optLong("size", -1);
                        break;
                    }
                }
                final String current = installedVersion(a);
                final boolean newer = apk != null && compare(tag, current) > 0;
                final String url = apk;
                final long bytes = size;
                main.post(() -> {
                    if (a.isFinishing()) return;
                    if (newer) offer(a, tag, current, notes, url, bytes);
                    else if (userAsked) toast(a, "En güncel sürümü kullanıyorsunuz (" + current + ").");
                });
            } catch (Exception e) {
                if (userAsked) main.post(() -> toast(a, "Güncelleme kontrol edilemedi."));
            }
        }).start();
    }

    private static void offer(final Activity a, String tag, String current, String notes,
                              final String url, final long size) {
        if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
        String msg = "Yüklü sürüm: " + current + "\nYeni sürüm: " + tag
                + (notes.isEmpty() ? "" : "\n\n" + notes)
                + "\n\nŞimdi güncellensin mi? Verileriniz silinmez.";
        new AlertDialog.Builder(a)
                .setTitle("Yeni sürüm var")
                .setMessage(msg)
                .setPositiveButton("Güncelle", (d, w) -> download(a, url, size))
                .setNegativeButton("Sonra", null)
                .show();
    }

    private static boolean canInstall(Activity a) {
        return Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls();
    }

    private static void download(final Activity a, final String url, final long size) {
        if (!canInstall(a)) {
            new AlertDialog.Builder(a)
                    .setTitle("Bir kerelik izin gerekli")
                    .setMessage("Açılacak ekranda \"Bu kaynaktan izin ver\" seçeneğini açın, "
                            + "sonra geri dönüp tekrar Güncelle'ye dokunun.")
                    .setPositiveButton("Ayarları aç", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                        }
                    })
                    .setNegativeButton("Vazgeç", null)
                    .show();
            return;
        }

        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad / 2);
        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(size <= 0);
        final TextView label = new TextView(a);
        label.setText("İndiriliyor…");
        box.addView(bar);
        box.addView(label);
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Güncelleme indiriliyor")
                .setView(box)
                .setCancelable(false)
                .show();

        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(a.getPackageName());
                if (size > 0) params.setSize(size);
                int id = pi.createSession(params);
                session = pi.openSession(id);

                HttpURLConnection c = open(url);
                long total = c.getContentLength();
                if (total <= 0) total = size;
                try (InputStream in = c.getInputStream();
                     OutputStream out = session.openWrite("base.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100 / total);
                            final long d = done, t = total;
                            if (pct != last) {
                                last = pct;
                                main.post(() -> {
                                    bar.setIndeterminate(false);
                                    bar.setProgress(pct);
                                    label.setText(mb(d) + " / " + mb(t) + " MB");
                                });
                            }
                        }
                    }
                    session.fsync(out);
                } finally {
                    c.disconnect();
                }

                Intent status = new Intent(a, Receiver.class).setAction(ACTION);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pend = PendingIntent.getBroadcast(a, id, status, flags);
                session.commit(pend.getIntentSender());
                session.close();
                main.post(dlg::dismiss);
            } catch (final Exception e) {
                if (session != null) session.abandon();
                main.post(() -> {
                    dlg.dismiss();
                    toast(a, "Güncelleme indirilemedi: " + e.getMessage());
                });
            }
        }).start();
    }

    /** PackageInstaller'in sonucunu alir: onay ekranini acar ya da hatayi gosterir. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        ctx.startActivity(confirm);
                    } catch (Exception e) {
                        toast(ctx, "Kurulum ekranı açılamadı.");
                    }
                }
            } else if (st == PackageInstaller.STATUS_FAILURE_CONFLICT
                    || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                toast(ctx, "Yeni sürüm farklı bir anahtarla imzalanmış. Uygulamayı bir kez "
                        + "kaldırıp en son sürümü kurun; sonraki güncellemeler otomatik olur.");
            } else if (st != PackageInstaller.STATUS_SUCCESS
                    && st != PackageInstaller.STATUS_FAILURE_ABORTED) {
                String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                toast(ctx, "Güncelleme kurulamadı" + (m != null ? ": " + m : "."));
            }
        }
    }

    // ---- yardimcilar ----

    private static String installedVersion(Context c) throws Exception {
        PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        return p.versionName != null ? p.versionName : "0";
    }

    /** "1.3.10" ile "1.3.9" gibi surumleri sayisal karsilastirir. */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            long p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q ? 1 : -1;
        }
        return 0;
    }

    private static long num(String s) {
        try {
            return s.isEmpty() ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "com.atolye.app-updater");
        c.setRequestProperty("Accept", url.contains("api.github.com")
                ? "application/vnd.github+json" : "application/octet-stream");
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        return c;
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static String mb(long b) {
        return String.format(java.util.Locale.US, "%.1f", b / 1048576.0);
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_LONG).show();
    }
}
