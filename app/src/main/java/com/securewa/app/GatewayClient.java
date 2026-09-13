package com.securewa.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * HTTPS-only client for the optional server-side gateway.
 *
 * Provider and Meta credentials never enter this class. The gateway owns them;
 * this client sends only the already-reviewed message and a short-lived
 * user-configured gateway bearer token.
 */
public final class GatewayClient {

    public interface Callback {
        void onSuccess(String message);

        void onError(String message);
    }

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isHttpsEndpoint(String value) {
        if (value == null || value.trim().length() == 0) return false;
        try {
            URL url = new URL(value.trim());
            return "https".equalsIgnoreCase(url.getProtocol())
                    && url.getHost() != null
                    && url.getHost().length() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isE164(String value) {
        if (value == null) return false;
        String phone = value.trim();
        if (phone.length() < 9 || phone.length() > 16 || phone.charAt(0) != '+') return false;
        for (int i = 1; i < phone.length(); i++) {
            if (!Character.isDigit(phone.charAt(i))) return false;
        }
        return true;
    }

    public void complete(String baseUrl, String bearerToken, String message, Callback callback) {
        JSONObject request = new JSONObject();
        try {
            request.put("message", message);
            request.put("client", "securewa-android");
            request.put("version", "1.1");
        } catch (Exception exception) {
            postError(callback, "Could not prepare the gateway request");
            return;
        }
        postJson(join(baseUrl, "/v1/secure/complete"), bearerToken, request.toString(),
                true, callback);
    }

    public void sendWhatsApp(String baseUrl, String bearerToken, String recipient,
                             String message, Callback callback) {
        JSONObject request = new JSONObject();
        try {
            request.put("to", recipient);
            request.put("text", message);
            request.put("client", "securewa-android");
        } catch (Exception exception) {
            postError(callback, "Could not prepare the WhatsApp request");
            return;
        }
        postJson(join(baseUrl, "/v1/whatsapp/send"), bearerToken, request.toString(),
                false, callback);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void postJson(String endpoint, String bearerToken, String body,
                          boolean expectsText, Callback callback) {
        if (!isHttpsEndpoint(endpoint)) {
            postError(callback, "Gateway URL must use HTTPS");
            return;
        }
        executor.execute(() -> {
            HttpsURLConnection connection = null;
            try {
                URL url = new URL(endpoint);
                connection = (HttpsURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                if (bearerToken != null && bearerToken.trim().length() > 0) {
                    connection.setRequestProperty("Authorization", "Bearer " + bearerToken.trim());
                }

                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }

                int status = connection.getResponseCode();
                String response = readBody(status >= 400
                        ? connection.getErrorStream() : connection.getInputStream());
                if (status < 200 || status >= 300) {
                    postError(callback, errorMessage(status, response));
                    return;
                }

                if (!expectsText) {
                    postSuccess(callback, "WhatsApp Cloud API accepted the message.");
                    return;
                }
                JSONObject json = new JSONObject(response);
                String text = json.optString("text", json.optString("reply", ""));
                if (text.trim().length() == 0) {
                    postError(callback, "Gateway returned an empty response");
                } else {
                    postSuccess(callback, text.trim());
                }
            } catch (Exception exception) {
                postError(callback, "Gateway connection failed. Check the HTTPS URL and try again.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private String readBody(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder body = new StringBuilder();
        int bytes = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                bytes += line.length();
                if (bytes > MAX_RESPONSE_BYTES) throw new IllegalStateException("response too large");
                body.append(line);
            }
        }
        return body.toString();
    }

    private String errorMessage(int status, String body) {
        try {
            String error = new JSONObject(body).optString("error", "");
            if (error.length() > 0 && error.length() < 180) return error;
        } catch (Exception ignored) {
            // Return a status-only message for non-JSON errors.
        }
        return "Gateway rejected the request (HTTP " + status + ")";
    }

    private void postSuccess(Callback callback, String message) {
        main.post(() -> callback.onSuccess(message));
    }

    private void postError(Callback callback, String message) {
        main.post(() -> callback.onError(message));
    }

    private static String join(String base, String path) {
        String value = base == null ? "" : base.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value + path;
    }
}
