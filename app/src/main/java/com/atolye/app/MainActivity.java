package com.atolye.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Arayuz assets/index.html icinde (WebView). Kurulum motoru Java tarafinda:
 * F-Droid'den APK indirip PackageInstaller ile kurar, Play Store sayfasini acar,
 * hangi paketlerin kurulu oldugunu soyler. Olaylar JS'e window.__native(...) ile gider.
 */
public class MainActivity extends Activity implements FdroidInstaller.Listener {
    static final String ACTION_CONFIRM = "com.atolye.app.CONFIRM";
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private FdroidInstaller installer;
    private volatile boolean visible;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        installer = FdroidInstaller.get(this);
        installer.setListener(this);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.addJavascriptInterface(new Bridge(), "Atolye");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @SuppressWarnings("deprecation")
    private long myVersionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Onay bildirimine dokunuldu: onResume bekleyen onayi bir kez acacak.
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private boolean openExternal(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0));
            else pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** JS'in cagirdigi yontemler. Hepsi kisa surer; uzun isler FdroidInstaller'da arka planda. */
    private class Bridge {
        @JavascriptInterface
        public String installed(String pkgsJson) {
            JSONArray out = new JSONArray();
            try {
                JSONArray in = new JSONArray(pkgsJson);
                for (int i = 0; i < in.length(); i++) {
                    String p = in.optString(i);
                    if (isInstalled(p)) out.put(p);
                }
            } catch (JSONException ignored) { }
            return out.toString();
        }

        @JavascriptInterface
        public boolean openStore(String pkg) {
            if (openExternal(Uri.parse("market://details?id=" + pkg))) return true;
            return openExternal(Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
        }

        @JavascriptInterface
        public boolean openUrl(String url) {
            return openExternal(Uri.parse(url));
        }

        @JavascriptInterface
        public boolean openApp(String pkg) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            startActivity(i);
            return true;
        }

        @JavascriptInterface
        public boolean canInstall() {
            return getPackageManager().canRequestPackageInstalls();
        }

        @JavascriptInterface
        public void requestInstallPermission() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException ignored) { }
            });
        }

        /** F-Droid paketlerini arka plan servisine sirayla kurdurur: [{pkg,name},...] */
        @JavascriptInterface
        public void installFdroidAll(String json) {
            ArrayList<String> pkgs = new ArrayList<>(), names = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    pkgs.add(o.getString("pkg"));
                    names.add(o.optString("name", o.getString("pkg")));
                }
            } catch (JSONException ignored) { }
            if (pkgs.isEmpty()) return;
            runOnUiThread(() -> {
                askNotificationPermission();
                InstallService.start(MainActivity.this, pkgs, names);
            });
        }

        @JavascriptInterface
        public void checkUpdate() {
            installer.checkUpdate(myVersionCode());
        }

        @JavascriptInterface
        public void installUpdate(String url) {
            if (url != null && url.startsWith("https://github.com/MustafaCanDere/-Atolye-Android/releases/")) {
                installer.installSelf(url);
            }
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean busy() {
            return installer.isRunning();
        }

        @JavascriptInterface
        public void cancel() {
            installer.cancel();
        }

        @JavascriptInterface
        public String deviceInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("release", Build.VERSION.RELEASE);
                o.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                o.put("abis", new JSONArray(Build.SUPPORTED_ABIS));
            } catch (JSONException ignored) { }
            return o.toString();
        }
    }

    // ---- FdroidInstaller.Listener: olaylari JS'e ilet ----
    @Override
    public void onEvent(JSONObject event) {
        final String js = "window.__native && window.__native(" + event.toString() + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public boolean launchUserAction(Intent confirm) {
        // Arka plandan Activity acmak Android 10+'da engellenir; o durumda bildirim gosterilir
        // ve uygulamaya donuldugunde onResume bu ekrani tekrar acar.
        if (!visible) return false;
        runOnUiThread(() -> {
            try {
                startActivity(new Intent(confirm));
            } catch (ActivityNotFoundException ignored) { }
        });
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (web != null) web.evaluateJavascript("window.__resume && window.__resume()", null);
        installer.resumePendingConfirm();
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.__back && window.__back()) ? 1 : 0", value -> {
            if (!"1".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        // Kurucu surec boyunca yasar; servis calisiyorsa kurulum devam eder.
        installer.setListener(null);
        super.onDestroy();
    }
}
