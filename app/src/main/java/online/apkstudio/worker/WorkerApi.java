package online.apkstudio.worker;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class WorkerApi {
    private final Credentials credentials;

    WorkerApi(Credentials credentials) { this.credentials = credentials; }

    JSONObject poll() throws Exception {
        return read(open("/api/local-builder/worker", "GET", null));
    }

    void log(String id, String value) throws Exception { event(id, "log", value); }
    void fail(String id, String value) throws Exception { event(id, "fail", value); }

    private void event(String id, String action, String value) throws Exception {
        JSONObject payload = new JSONObject().put("log", value);
        HttpURLConnection connection = open("/api/local-builder/worker?job=" + id + "&action=" + action,
            "POST", "application/json");
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream stream = connection.getOutputStream()) { stream.write(bytes); }
        read(connection);
    }

    void upload(String id, File apk) throws Exception {
        if (!id.matches("[a-f0-9-]{36}") || !apk.isFile() || apk.length() < 100 ||
            apk.length() > 100_000_000L) throw new IllegalArgumentException("Недопустимый APK.");
        HttpURLConnection connection = open("/api/local-builder/worker?job=" + id + "&action=artifact",
            "POST", "application/vnd.android.package-archive");
        connection.setFixedLengthStreamingMode(apk.length());
        try (InputStream file = new FileInputStream(apk);
             OutputStream stream = connection.getOutputStream()) {
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = file.read(chunk)) != -1) stream.write(chunk, 0, read);
        }
        read(connection);
    }

    private HttpURLConnection open(String path, String method, String contentType) throws Exception {
        URL url = new URL(Credentials.SERVER + path);
        if (!"https".equals(url.getProtocol()) || !"apk-studio-igor.sassyibis7.chatgpt.site".equals(url.getHost())) {
            throw new SecurityException("Запрещён адрес вне личного APK Studio.");
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(65000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + credentials.token);
        conn.setRequestProperty("OAI-Sites-Authorization", "Bearer " + credentials.service);
        conn.setRequestProperty("Accept", "application/json");
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
        if ("POST".equals(method)) conn.setDoOutput(true);
        return conn;
    }

    private JSONObject read(HttpURLConnection conn) throws Exception {
        try {
            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = limitedString(stream, 350_000);
            if (status < 200 || status >= 300) {
                throw new IOException("APK Studio HTTP " + status + ": " + body.substring(0, Math.min(170, body.length())));
            }
            return new JSONObject(body);
        } finally { conn.disconnect(); }
    }

    static String limitedString(InputStream stream, int limit) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (output.size() + size > limit) throw new IOException("Ответ сервера слишком большой.");
                output.write(buffer, 0, size);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
