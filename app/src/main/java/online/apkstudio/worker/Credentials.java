package online.apkstudio.worker;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Credentials {
    static final String SERVER = "https://apk-studio-igor.sassyibis7.chatgpt.site";
    private static final String ALIAS = "apk-studio-worker-pair-v1";
    private static final String PREF = "private_connection";

    final String token;
    final String service;

    private Credentials(String token, String service) {
        this.token = token;
        this.service = service;
    }

    static Credentials fromPairingCode(String raw) throws Exception {
        String code = raw == null ? "" : raw.replaceAll("\\s", "");
        byte[] plain = Base64.decode(code, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        JSONObject data = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        String token = data.optString("token", "");
        String service = data.optString("service", "");
        if (!token.matches("[a-f0-9]{64}") || service.isBlank() || service.length() > 4096) {
            throw new IllegalArgumentException("Неверный код. Создай новое сопряжение в APK Studio.");
        }
        return new Credentials(token, service);
    }

    static void save(Context context, Credentials value) throws Exception {
        SecretKey key = getKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // AndroidKeyStore GCM keys require a provider-generated IV for encryption.
        // Supplying our own IV throws "Caller-provided IV not permitted" on Android.
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != 12) {
            throw new IllegalStateException("Не удалось создать защищённый код шифрования.");
        }
        JSONObject data = new JSONObject();
        data.put("token", value.token);
        data.put("service", value.service);
        byte[] ciphertext = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        prefs.edit()
            .putString("iv", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply();
    }

    static Credentials load(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String ciphertext = prefs.getString("data", null);
        String nonce = prefs.getString("iv", null);
        if (ciphertext == null || nonce == null) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(),
            new GCMParameterSpec(128, Base64.decode(nonce, Base64.NO_WRAP)));
        byte[] plain = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
        JSONObject data = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        return new Credentials(data.getString("token"), data.getString("service"));
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static SecretKey getKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
