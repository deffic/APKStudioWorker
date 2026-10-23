package online.apkstudio.worker;

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
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class WorkerService extends Service {
    static final String ACTION_STOP = "online.apkstudio.worker.STOP";
    private static final String CHANNEL = "apk_studio_worker";
    private static final int NOTIFICATION_ID = 7;
    private volatile boolean running;
    private Thread thread;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Сборка APK",
            NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = notification("Ожидаем задания APK Studio");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else { startForeground(NOTIFICATION_ID, notification); }
        if (running) return START_NOT_STICKY;
        running = true;
        thread = new Thread(this::workLoop, "apk-studio-poll");
        thread.start();
        return START_NOT_STICKY;
    }

    private Notification notification(String message) {
        Intent stop = new Intent(this, WorkerService.class).setAction(ACTION_STOP);
        PendingIntent cancel = PendingIntent.getService(this, 2, stop,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 1,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("APK Studio Worker")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить", cancel).build())
            .build();
    }

    private void update(String message) {
        getSharedPreferences("runtime", Context.MODE_PRIVATE).edit()
            .putString("status", message).putLong("heartbeat", System.currentTimeMillis()).apply();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
    }

    private void workLoop() {
        long start = SystemClock.elapsedRealtime();
        try {
            Credentials credentials = Credentials.load(this);
            if (credentials == null) throw new IllegalStateException("Сначала выполни сопряжение.");
            WorkerApi api = new WorkerApi(credentials);
            update("Телефон подключается к APK Studio…");
            // Android 15+ imposes a 6-hour/24-hour dataSync foreground service quota.
            // Leave a safety margin and require a user-initiated restart.
            while (running && SystemClock.elapsedRealtime() - start < 5L * 60 * 60 * 1000) {
                try {
                    JSONObject payload = api.poll();
                    JSONObject job = payload.optJSONObject("job");
                    if (job == null) {
                        update("Телефон в сети · ожидаем сборку");
                    } else {
                        handleJob(api, job);
                    }
                    Thread.sleep(job == null ? 12_000 : 2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception failure) {
                    update("Связь: " + shorten(failure.getMessage(), 110));
                    try { Thread.sleep(18_000); } catch (InterruptedException ignored) { break; }
                }
            }
            if (running) update("Лимит фоновой работы: перезапусти сборщик вручную.");
        } catch (Exception failure) {
            update("Ошибка подключения: " + shorten(failure.getMessage(), 110));
        } finally {
            running = false;
            stopSelf();
        }
    }

    private void handleJob(WorkerApi api, JSONObject job) {
        String id = job.optString("id", "");
        try {
            if (!id.matches("[a-f0-9-]{36}") || !"assembleDebug".equals(job.optString("task")) ||
                !"TradingHelperAI".equals(job.optString("project"))) {
                throw new IllegalArgumentException("Неизвестное задание.");
            }
            File project = ProjectStore.activeRoot(this);
            if (project == null) throw new IllegalStateException("Импортируй ZIP с исходниками TradingHelperAI.");
            if (!ProjectStore.hasToolchain(this)) {
                throw new IllegalStateException("Движок Gradle на Android пока не установлен. APK Studio Worker v0.1 поддерживает сопряжение и диагностику, но ещё не компиляцию.");
            }
            update("Сборка " + id.substring(0, 8) + "…");
            JSONArray edits = job.optJSONArray("edits");
            String applied = ProjectStore.applyEdits(this, project, id, edits == null ? new JSONArray() : edits);
            String output = gradleBuild(project);
            api.log(id, applied + "\n" + shorten(output, 12_000));
            File apk = findApk(project);
            if (apk == null) throw new IOException("Gradle не создал APK в outputs/apk/debug.");
            api.upload(id, apk);
            update("APK готов · " + id.substring(0, 8));
        } catch (Exception error) {
            update("Сборка не завершена: " + shorten(error.getMessage(), 110));
            if (id.matches("[a-f0-9-]{36}")) {
                try { api.fail(id, shorten(error.toString(), 12_000)); }
                catch (Exception ignored) { update("Не удалось передать ошибку сборки."); }
            }
        }
    }

    private String gradleBuild(File project) throws Exception {
        File tools = new File(getFilesDir(), "toolchain");
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", new File(project, "gradlew").getAbsolutePath(),
            "assembleDebug", "--no-daemon");
        builder.directory(project);
        builder.redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", new File(tools, "jdk").getAbsolutePath());
        builder.environment().put("ANDROID_HOME", new File(tools, "sdk").getAbsolutePath());
        builder.environment().put("ANDROID_SDK_ROOT", new File(tools, "sdk").getAbsolutePath());
        builder.environment().put("PATH", new File(tools, "jdk/bin").getAbsolutePath() +
            ":/system/bin:/system/xbin");
        Process process = builder.start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] block = new byte[4096];
                int n;
                while ((n = stream.read(block)) != -1) {
                    synchronized (log) {
                        if (log.size() > 120_000) log.reset();
                        log.write(block, 0, n);
                    }
                }
            } catch (IOException ignored) { }
        }, "gradle-output");
        reader.start();
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("Gradle превысил лимит 30 минут.");
        }
        reader.join(10_000);
        String output;
        synchronized (log) { output = new String(log.toByteArray(), StandardCharsets.UTF_8); }
        if (process.exitValue() != 0) throw new IOException("Gradle: код " + process.exitValue() + "\n" + shorten(output, 10_000));
        return output;
    }

    private File findApk(File project) {
        File modules[] = project.listFiles(File::isDirectory);
        if (modules == null) return null;
        File latest = null;
        for (File module : modules) {
            File out = new File(module, "build/outputs/apk/debug");
            File[] files = out.listFiles((dir, name) -> name.endsWith(".apk"));
            if (files == null) continue;
            for (File file : files) {
                if (latest == null || file.lastModified() > latest.lastModified()) latest = file;
            }
        }
        return latest;
    }

    private static String shorten(String value, int max) {
        if (value == null) return "Неизвестная ошибка";
        return value.length() > max ? value.substring(value.length() - max) : value;
    }

    @Override public void onDestroy() {
        running = false;
        if (thread != null) thread.interrupt();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
