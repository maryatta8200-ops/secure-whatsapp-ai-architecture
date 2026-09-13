package com.securewa.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypted-at-rest storage for non-content audit metadata.
 *
 * Android Keystore protects the AES key. The raw message is never written to
 * this store; callers should persist only policy results and counters. This
 * avoids adding a third-party crypto dependency to the APK.
 */
public final class SecureStore {

    private static final String KEY_ALIAS = "securewa.audit.v1";
    private static final String PREFS = "securewa.encrypted_audit";
    private static final String AUDIT_PREFIX = "audit_";
    private static final String REDACTION_ENABLED = "redaction_enabled";
    private static final String AUDIT_ENABLED = "audit_enabled";
    private static final String GATEWAY_ENDPOINT = "gateway_endpoint";
    private static final String GATEWAY_TOKEN = "gateway_token_encrypted";
    private static final String WHATSAPP_RECIPIENT = "whatsapp_recipient";

    private final SharedPreferences preferences;

    public SecureStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isRedactionEnabled() {
        return preferences.getBoolean(REDACTION_ENABLED, true);
    }

    public void setRedactionEnabled(boolean enabled) {
        preferences.edit().putBoolean(REDACTION_ENABLED, enabled).apply();
    }

    public boolean isAuditEnabled() {
        return preferences.getBoolean(AUDIT_ENABLED, true);
    }

    public void setAuditEnabled(boolean enabled) {
        preferences.edit().putBoolean(AUDIT_ENABLED, enabled).apply();
    }

    public String getGatewayEndpoint() {
        return preferences.getString(GATEWAY_ENDPOINT, "");
    }

    public void setGatewayEndpoint(String endpoint) {
        preferences.edit().putString(GATEWAY_ENDPOINT, endpoint == null ? "" : endpoint.trim()).apply();
    }

    public String getGatewayToken() {
        String encrypted = preferences.getString(GATEWAY_TOKEN, "");
        if (encrypted.length() == 0) return "";
        try {
            return decrypt(encrypted);
        } catch (Exception ignored) {
            return "";
        }
    }

    public boolean setGatewayToken(String token) {
        String value = token == null ? "" : token.trim();
        if (value.length() == 0) {
            preferences.edit().remove(GATEWAY_TOKEN).apply();
            return true;
        }
        try {
            preferences.edit().putString(GATEWAY_TOKEN, encrypt(value)).apply();
            return true;
        } catch (Exception ignored) {
            // Never fall back to plaintext token storage.
            return false;
        }
    }

    public String getWhatsAppRecipient() {
        return preferences.getString(WHATSAPP_RECIPIENT, "");
    }

    public void setWhatsAppRecipient(String recipient) {
        preferences.edit().putString(WHATSAPP_RECIPIENT,
                recipient == null ? "" : recipient.trim()).apply();
    }

    public boolean saveAudit(String event) {
        if (!isAuditEnabled()) return true;
        try {
            String encrypted = encrypt(event);
            String key = AUDIT_PREFIX + System.currentTimeMillis();
            preferences.edit().putString(key, encrypted).apply();
            return true;
        } catch (Exception ignored) {
            // A failed audit write must never block the user from composing a
            // message. No unencrypted fallback is written.
            return false;
        }
    }

    public int auditCount() {
        int count = 0;
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(AUDIT_PREFIX)) count++;
        }
        return count;
    }

    public void clearAudits() {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(AUDIT_PREFIX)) editor.remove(key);
        }
        editor.apply();
    }

    /** Returns the newest encrypted audit events, decrypted only in memory. */
    public List<String> recentAudits(int limit) {
        List<String> keys = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(AUDIT_PREFIX)) keys.add(key);
        }
        Collections.sort(keys, Collections.reverseOrder());
        List<String> events = new ArrayList<>();
        for (int i = 0; i < keys.size() && i < limit; i++) {
            String value = preferences.getString(keys.get(i), null);
            if (value == null) continue;
            try {
                events.add(decrypt(value));
            } catch (Exception ignored) {
                // Ignore an unreadable entry rather than exposing ciphertext.
            }
        }
        return events;
    }

    private String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String data = Base64.encodeToString(ciphertext, Base64.NO_WRAP);
        return iv + ":" + data;
    }

    private String decrypt(String payload) throws Exception {
        String[] parts = payload.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("invalid encrypted audit");
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
