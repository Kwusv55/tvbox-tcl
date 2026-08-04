package com.tvbox.legacy.net;

import android.text.TextUtils;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Small API-17-safe HTTP client used by the rule engine. */
public final class HttpClient {
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final String DEFAULT_UA =
            "TVBox-TCL/1.0 (Android 4.2; public-media-client)";

    private HttpClient() {
    }

    public static Response get(String url, Map<String, String> extraHeaders)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            URL requestUrl = new URL(url);
            connection = (HttpURLConnection) requestUrl.openConnection();
            enableLegacyTls(connection);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "text/html,application/json,*/*");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("User-Agent", DEFAULT_UA);
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    if (!TextUtils.isEmpty(entry.getKey()) && entry.getValue() != null) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
            }
            int status = connection.getResponseCode();
            InputStream raw = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (raw == null) {
                throw new IOException("HTTP " + status + " (empty response)");
            }
            InputStream stream = new BufferedInputStream(raw);
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && encoding.toLowerCase(java.util.Locale.US).contains("gzip")) {
                stream = new GZIPInputStream(stream);
            }
            byte[] body = readLimited(stream, MAX_BODY_BYTES);
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " for " + url);
            }
            Map<String, String> responseHeaders = new LinkedHashMap<String, String>();
            String contentType = connection.getHeaderField("Content-Type");
            if (contentType != null) {
                responseHeaders.put("Content-Type", contentType);
            }
            responseHeaders.put("Final-Url", connection.getURL().toString());
            return new Response(status, body, responseHeaders);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        try {
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) {
                    throw new IOException("response exceeds " + limit + " bytes");
                }
                output.write(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        return output.toByteArray();
    }

    /** Android 4.2 has TLS 1.2 but does not always enable it by default. */
    private static void enableLegacyTls(HttpURLConnection connection) throws IOException {
        if (!(connection instanceof HttpsURLConnection)
                || Build.VERSION.SDK_INT < 16 || Build.VERSION.SDK_INT > 19) {
            return;
        }
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, null, null);
            ((HttpsURLConnection) connection).setSSLSocketFactory(context.getSocketFactory());
        } catch (Exception error) {
            throw new IOException("TLS 1.2 unavailable", error);
        }
    }

    public static final class Response {
        public final int status;
        public final byte[] body;
        public final Map<String, String> headers;

        private Response(int status, byte[] body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        public String text(String fallbackCharset) {
            String charset = extractCharset(headers.get("Content-Type"));
            if (TextUtils.isEmpty(charset)) {
                charset = fallbackCharset;
            }
            try {
                return new String(body, charset == null ? "UTF-8" : charset);
            } catch (java.io.UnsupportedEncodingException ignored) {
                return new String(body);
            }
        }

        private static String extractCharset(String contentType) {
            if (contentType == null) {
                return null;
            }
            String lower = contentType.toLowerCase(java.util.Locale.US);
            int index = lower.indexOf("charset=");
            if (index < 0) {
                return null;
            }
            String value = contentType.substring(index + 8).trim();
            if (value.startsWith("\"")) {
                value = value.substring(1);
            }
            if (value.endsWith("\"")) {
                value = value.substring(0, value.length() - 1);
            }
            int semicolon = value.indexOf(';');
            return semicolon >= 0 ? value.substring(0, semicolon).trim() : value;
        }
    }
}
