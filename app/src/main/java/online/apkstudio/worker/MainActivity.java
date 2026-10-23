package online.apkstudio.worker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_ZIP = 63;
    private static final int NOTIFICATION_REQUEST = 64;
    private static final int NAVY = Color.rgb(10, 25, 44);
    private static final int PANEL = Color.rgb(20, 42, 63);
    private static final int MINT = Color.rgb(102, 231, 195);
    private static final int MUTED = Color.rgb(161, 184, 198);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private EditText pairingInput;
    private TextView connectionText;
    private TextView projectText;
    private TextView toolchainText;
    private TextView activityText;
    private Runnable ticker;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        createScreen();
        refresh();
        ticker = new Runnable() {
            @Override public void run() { refresh(); handler.postDelayed(this, 2000); }
        };
        handler.postDelayed(ticker, 2000);
    }

    private void createScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(NAVY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(21), dp(31), dp(21), dp(35));
        scroll.addView(root);

        TextView eyebrow = text("PRIVATE ANDROID BUILD ENGINE", 10, MINT, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.17f);
        root.addView(eyebrow);
        TextView title = text("APK Studio", 34, Color.WHITE, Typeface.BOLD);
        title.setPadding(0, dp(12), 0, dp(1));
        root.addView(title);
        root.addView(text("Worker · собственный клиент", 18, MINT, Typeface.NORMAL));
        TextView intro = text("Прямое подключение к твоей панели. Без рекламы, аналитики и сторонних аккаунтов.", 13, MUTED, Typeface.NORMAL);
        intro.setPadding(0, dp(14), 0, dp(13));
        root.addView(intro);

        LinearLayout statusCard = card();
        statusCard.addView(label("СТАТУС ПОДКЛЮЧЕНИЯ"));
        connectionText = text("Не подключено", 16, Color.WHITE, Typeface.BOLD);
        connectionText.setPadding(0, dp(9), 0, dp(10));
        statusCard.addView(connectionText);
        activityText = text("Ожидаем запуска", 12, MUTED, Typeface.NORMAL);
        statusCard.addView(activityText);
        root.addView(statusCard);

        LinearLayout pairingCard = card();
        pairingCard.addView(label("1 · ЛИЧНОЕ СОПРЯЖЕНИЕ"));
        pairingCard.addView(description("Создай код в APK Studio → AI Builder и вставь его сюда. Код останется зашифрованным в Android Keystore."));
        pairingInput = new EditText(this);
        pairingInput.setTextColor(Color.WHITE);
        pairingInput.setHintTextColor(Color.rgb(136, 157, 169));
        pairingInput.setHint("Вставь код сопряжения");
        pairingInput.setTextSize(13);
        pairingInput.setSingleLine(true);
        pairingInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pairingInput.setBackground(background(Color.rgb(32, 56, 78), 12));
        pairingInput.setPadding(dp(13), dp(12), dp(13), dp(12));
        LinearLayout.LayoutParams inputLayout = new LinearLayout.LayoutParams(-1, -2);
        inputLayout.topMargin = dp(8);
        pairingCard.addView(pairingInput, inputLayout);
        pairingCard.addView(action("Сохранить сопряжение", MINT, NAVY, v -> pair()));
        pairingCard.addView(action("Отозвать ключ на телефоне", Color.rgb(45, 67, 86), Color.WHITE, v -> disconnect()));
        root.addView(pairingCard);

        LinearLayout projectCard = card();
        projectCard.addView(label("2 · ИСХОДНИКИ"));
        projectText = description("Проект не импортирован");
        projectCard.addView(projectText);
        projectCard.addView(action("Выбрать ZIP проекта", MINT, NAVY, v -> pickZip()));
        root.addView(projectCard);

        LinearLayout engineCard = card();
        engineCard.addView(label("3 · ДВИЖОК СБОРКИ"));
        toolchainText = description("Проверяем инструменты Android…");
        engineCard.addView(toolchainText);
        engineCard.addView(description("Локальная проверка не загружает исходники на сервер. Для Kotlin и Compose требуется отдельная компиляция на телефоне."));
        engineCard.addView(action("Проверить сборочный движок", MINT, NAVY, v -> inspectBuildEnvironment()));
        engineCard.addView(action("Проверить ресурсы проекта (AAPT2)", Color.rgb(45, 67, 86), Color.WHITE,
            v -> checkProjectResources()));
        engineCard.addView(action("Проверить пакет компиляторов", Color.rgb(45, 67, 86), Color.WHITE,
            v -> inspectCompilerPack()));
        root.addView(engineCard);

        LinearLayout controlCard = card();
        controlCard.addView(label("4 · ПОЛУЧЕНИЕ ЗАДАНИЙ"));
        controlCard.addView(description("Запускается видимый процесс с постоянным уведомлением. Android может ограничивать время работы."));
        controlCard.addView(action("▶  Включить связь", MINT, NAVY, v -> startWorker()));
        controlCard.addView(action("■  Остановить", Color.rgb(45, 67, 86), Color.WHITE, v -> stopWorker()));
        controlCard.addView(action("Открыть веб-панель APK Studio", Color.rgb(33, 86, 96), Color.WHITE,
            v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Credentials.SERVER)))));
        root.addView(controlCard);

        TextView foot = text("Данные проекта хранятся в закрытой папке приложения. Сетевой адрес сборщика фиксирован. Экспорт APK появится после подключения сборочного движка.", 11, MUTED, Typeface.NORMAL);
        foot.setPadding(0, dp(20), 0, 0);
        root.addView(foot);
        setContentView(scroll);
    }

    private void pair() {
        String value = pairingInput.getText().toString();
        if (value.length() > 8000) { alert("Слишком длинный код сопряжения."); return; }
        try {
            Credentials.save(this, Credentials.fromPairingCode(value));
            pairingInput.setText("");
            refresh();
            alert("Телефон привязан. Теперь можно включить связь с APK Studio.");
        } catch (Exception failure) { alert("Не удалось подключить: " + failure.getMessage()); }
    }

    private void inspectBuildEnvironment() {
        toolchainText.setText("Проверяем проект и инструменты на телефоне…");
        jobs.execute(() -> {
            String report;
            try {
                File project = ProjectStore.activeRoot(this);
                BuildProfile profile = project == null ? null : BuildProfile.inspect(project);
                report = (profile == null ? "Проект ещё не импортирован.\n\n"
                    : profile.describe() + "\n\n") + LocalToolchain.readiness(this, profile)
                    + "\n\n" + ToolPackCatalog.status(this);
                if (LocalToolchain.nativeAapt2(this) != null) {
                    try { report += "\n\n" + LocalToolchain.aapt2SelfTest(this); }
                    catch (Exception error) { report += "\n\nОшибка теста AAPT2: " + error.getMessage(); }
                }
            } catch (Exception error) {
                report = "Не удалось проверить сборку: " + error.getMessage();
            }
            String completedReport = report;
            runOnUiThread(() -> { refresh(); alert(completedReport); });
        });
    }

    private void inspectCompilerPack() {
        jobs.execute(() -> {
            String result;
            try {
                File installed = ToolPackCatalog.installBundled(this);
                result = "Проверенный пакет инструментов установлен в закрытую папку телефона: "
                    + installed.getName() + "\n\n" + ToolPackCatalog.status(this);
            } catch (Exception failure) {
                result = failure.getMessage() + "\n\n" + ToolPackCatalog.status(this);
            }
            String message = result;
            runOnUiThread(() -> { refresh(); alert(message); });
        });
    }

    private void checkProjectResources() {
        toolchainText.setText("Проверяем ресурсы Android-проекта…");
        jobs.execute(() -> {
            String report;
            try {
                File project = ProjectStore.activeRoot(this);
                File aapt2 = LocalToolchain.nativeAapt2(this);
                if (project == null) throw new IllegalStateException("Сначала импортируй ZIP проекта TradingHelperAI.");
                BuildProfile profile = BuildProfile.inspect(project);
                File platform = LocalToolchain.resourcePlatform(this, profile);
                if (aapt2 == null || platform == null) {
                    throw new IllegalStateException("Компиляция ресурсов станет доступна после " +
                        "включения AAPT2 и совместимой Android-платформы.\n\n" +
                        LocalToolchain.readiness(this, profile));
                }
                File work = new File(getCacheDir(), "resources-" + java.util.UUID.randomUUID());
                ResourceBuildStage.Result result = ResourceBuildStage.compileAndLink(
                    project, aapt2, platform, work, new BoundedProcessRunner(aapt2));
                report = result.summary() + "\n\nФайлы проверки: " + result.resourceApk.getName() +
                    "\nЛог: " + result.log.substring(0, Math.min(result.log.length(), 5000));
            } catch (Exception error) { report = "Проверка ресурсов: " + error.getMessage(); }
            String message = report;
            runOnUiThread(() -> { refresh(); alert(message); });
        });
    }

    private void disconnect() {
        new AlertDialog.Builder(this).setTitle("Удалить доступ?")
            .setMessage("Ключ исчезнет с телефона. Для повторного подключения создай новый код в APK Studio.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить", (dialog, which) -> {
                stopWorker();
                Credentials.clear(this);
                refresh();
            }).show();
    }

    private void pickZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, PICK_ZIP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ZIP || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri document = data.getData();
        projectText.setText("Импортируем исходники…");
        jobs.execute(() -> {
            try {
                File file = ProjectStore.importZip(this, document);
                runOnUiThread(() -> { refresh(); alert("Проект импортирован: " + file.getName()); });
            } catch (Exception failure) {
                runOnUiThread(() -> { refresh(); alert("Ошибка импорта: " + failure.getMessage()); });
            }
        });
    }

    private void startWorker() {
        try {
            if (Credentials.load(this) == null) { alert("Сначала вставь код сопряжения."); return; }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
                return;
            }
            startForegroundService(new Intent(this, WorkerService.class));
            activityText.setText("Запускаем подключение…");
        } catch (Exception failure) { alert("Ошибка запуска: " + failure.getMessage()); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFICATION_REQUEST) startWorker();
    }

    private void stopWorker() {
        stopService(new Intent(this, WorkerService.class));
        getSharedPreferences("runtime", Context.MODE_PRIVATE).edit()
            .putString("status", "Остановлено пользователем")
            .putLong("heartbeat", 0).apply();
        refresh();
    }

    private void refresh() {
        try {
            connectionText.setText(Credentials.load(this) == null ? "○ Не подключено" : "● Ключ сохранён");
        } catch (Exception e) { connectionText.setText("Ошибка ключа. Создай сопряжение заново."); }
        File project = ProjectStore.activeRoot(this);
        projectText.setText(project == null ? "Нет импортированного проекта" : "TradingHelperAI · " + project.getName());
        toolchainText.setText(ProjectStore.toolchainStatus(this));
        String runtime = getSharedPreferences("runtime", Context.MODE_PRIVATE).getString("status", "Ожидаем запуска");
        activityText.setText(runtime);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(20), dp(20), dp(20));
        layout.setBackground(background(PANEL, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(17);
        return attachLayout(layout, params);
    }

    private LinearLayout attachLayout(LinearLayout layout, LinearLayout.LayoutParams params) {
        // Card is attached immediately below the root when returned via root.addView(card).
        layout.setLayoutParams(params);
        return layout;
    }

    private TextView label(String label) { return text(label, 11, MINT, Typeface.BOLD); }
    private TextView description(String value) {
        TextView view = text(value, 12, MUTED, Typeface.NORMAL);
        view.setPadding(0, dp(11), 0, dp(7));
        return view;
    }

    private TextView text(String content, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private Button action(String title, int bg, int fg, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(fg);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(background(bg, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(49));
        params.topMargin = dp(12);
        button.setLayoutParams(params);
        button.setOnClickListener(click);
        return button;
    }

    private GradientDrawable background(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void alert(String message) { new AlertDialog.Builder(this).setMessage(message).setPositiveButton("Понятно", null).show(); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        jobs.shutdown();
        super.onDestroy();
    }
}
