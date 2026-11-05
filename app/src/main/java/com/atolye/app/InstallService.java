package com.atolye.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;

/**
 * Kurulum sirasi surerken uygulamayi canli tutan on plan servisi. Kullanici
 * uygulamadan cikinca indirme/kurulum durmaz; ilerleme bildirimde gorunur.
 * Android'in "Yukle" onayi gerekirse ve uygulama arka plandaysa, dokununca
 * onay ekranini acan bir bildirim gosterilir.
 */
public class InstallService extends Service implements FdroidInstaller.Host {
    static final String EXTRA_PKGS = "pkgs";
    static final String EXTRA_NAMES = "names";
    static final String ACTION_CANCEL = "com.atolye.app.CANCEL";

    private static final String CH_PROGRESS = "install";
    private static final String CH_CONFIRM = "confirm";
    private static final int ID_PROGRESS = 1;
    private static final int ID_CONFIRM = 2;

    private NotificationManager nm;
    private FdroidInstaller installer;

    static void start(Context ctx, ArrayList<String> pkgs, ArrayList<String> names) {
        Intent i = new Intent(ctx, InstallService.class)
                .putStringArrayListExtra(EXTRA_PKGS, pkgs)
                .putStringArrayListExtra(EXTRA_NAMES, names);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel p = new NotificationChannel(CH_PROGRESS, "Kurulum ilerlemesi",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationChannel c = new NotificationChannel(CH_CONFIRM, "Kurulum onayı",
                    NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("Uygulama arka plandayken Android'in Yükle onayını açar");
            nm.createNotificationChannel(p);
            nm.createNotificationChannel(c);
        }
        installer = FdroidInstaller.get(this);
        installer.setHost(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(progress("Kurulum hazırlanıyor", null, -1));
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            installer.cancel();
            return START_NOT_STICKY;
        }
        ArrayList<String> pkgs = intent != null ? intent.getStringArrayListExtra(EXTRA_PKGS) : null;
        ArrayList<String> names = intent != null ? intent.getStringArrayListExtra(EXTRA_NAMES) : null;
        if (pkgs == null || pkgs.isEmpty()) {
            if (!installer.isRunning()) stopSelf();
            return START_NOT_STICKY;
        }
        installer.installAll(pkgs, names != null ? names : new ArrayList<>());
        return START_NOT_STICKY;
    }

    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_PROGRESS, n);
        }
    }

    private Notification.Builder builder(String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification progress(String title, String text, int pct) {
        Intent cancel = new Intent(this, InstallService.class).setAction(ACTION_CANCEL);
        PendingIntent pc = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = builder(CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_wrench)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .addAction(new Notification.Action.Builder(null, "Durdur", pc).build());
        if (pct >= 0) b.setProgress(100, pct, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    // ---- FdroidInstaller.Host ----
    @Override
    public void onProgress(String name, String text, int pct) {
        nm.notify(ID_PROGRESS, progress(name, text, pct));
    }

    @Override
    public void onNeedsConfirm(String pkg, Intent confirm) {
        // Bildirim once Atolye'yi acar; onay ekranini oradan (on planda) bir kez acariz.
        Intent c = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_CONFIRM)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 2, c,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = builder(CH_CONFIRM)
                .setSmallIcon(R.drawable.ic_wrench)
                .setContentTitle("Kurulum onayı bekleniyor")
                .setContentText("Dokun ve Yükle'ye bas: " + pkg)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(ID_CONFIRM, n);
    }

    @Override
    public void onConfirmHandled() {
        nm.cancel(ID_CONFIRM);
    }

    @Override
    public void onQueueFinished() {
        nm.cancel(ID_CONFIRM);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        installer.setHost(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
