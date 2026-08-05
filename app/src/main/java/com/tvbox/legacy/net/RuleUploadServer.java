package com.tvbox.legacy.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Small API-17 compatible HTTP endpoint for importing a rule from a phone.
 *
 * <p>The server deliberately uses only java.net and java.io. It accepts the
 * raw JSON rule, a small JSON wrapper, URL encoded fields, and multipart form
 * uploads. The callback runs on the server thread; callers that touch views
 * must post back to the main thread.</p>
 */
public final class RuleUploadServer {
    public static final int DEFAULT_PORT = 8765;
    public static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    public static final int DEFAULT_SOCKET_TIMEOUT_MS = 15 * 1000;

    /** Empty pin disables authentication. Non-empty pin is required on POST. */
    public static final String NO_PIN = "";

    /** One-argument callback kept convenient for Activity integration. */
    public interface Callback {
        void onRuleUploaded(String ruleJson);

        /** Called when listening or request processing fails. */
        default void onError(Exception error) {
        }

        /** Optional filename supplied by multipart clients. */
        default void onRuleUploaded(String ruleJson, String fileName) {
            onRuleUploaded(ruleJson);
        }
    }

    /** Callback variant for callers that want filename without default methods. */
    public interface UploadCallback {
        void onRuleUploaded(String ruleJson, String fileName);

        default void onError(Exception error) {
        }
    }

    /** Immutable request payload useful to callbacks and tests. */
    public static final class Upload {
        public final String ruleJson;
        public final String fileName;
        public final String remoteAddress;

        Upload(String ruleJson, String fileName, String remoteAddress) {
            this.ruleJson = ruleJson;
            this.fileName = fileName;
            this.remoteAddress = remoteAddress;
        }
    }

    private final int requestedPort;
    private final String configuredPin;
    private final Callback callback;
    private final UploadCallback uploadCallback;
    private final int socketTimeoutMs;
    private final Object lifecycleLock = new Object();
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile int boundPort;

    public RuleUploadServer(Callback callback) {
        this(DEFAULT_PORT, NO_PIN, callback, DEFAULT_SOCKET_TIMEOUT_MS);
    }

    public RuleUploadServer(int port, String pin, Callback callback) {
        this(port, pin, callback, DEFAULT_SOCKET_TIMEOUT_MS);
    }

    public RuleUploadServer(int port, String pin, Callback callback, int socketTimeoutMs) {
        requestedPort = port;
        configuredPin = normalizePin(pin);
        this.callback = callback;
        uploadCallback = null;
        this.socketTimeoutMs = socketTimeoutMs > 0 ? socketTimeoutMs : DEFAULT_SOCKET_TIMEOUT_MS;
    }

    public RuleUploadServer(int port, String pin, UploadCallback callback) {
        requestedPort = port;
        configuredPin = normalizePin(pin);
        this.callback = null;
        uploadCallback = callback;
        socketTimeoutMs = DEFAULT_SOCKET_TIMEOUT_MS;
    }

    /** Starts listener thread. Repeated calls are harmless. */
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            try {
                ServerSocket socket = new ServerSocket(requestedPort, 8, (InetAddress) null);
                socket.setReuseAddress(true);
                serverSocket = socket;
                boundPort = socket.getLocalPort();
                running = true;
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        acceptLoop();
                    }
                }, "tcl-rule-upload");
                thread.setDaemon(true);
                acceptThread = thread;
                thread.start();
            } catch (IOException error) {
                running = false;
                serverSocket = null;
                boundPort = 0;
                notifyError(error);
            }
        }
    }

    /** Stops listener and closes active accept socket. */
    public void stop() {
        synchronized (lifecycleLock) {
            running = false;
            ServerSocket socket = serverSocket;
            serverSocket = null;
            boundPort = 0;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            Thread thread = acceptThread;
            acceptThread = null;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Returns actual bound port. Useful when constructor port is 0. */
    public int getPort() {
        return boundPort != 0 ? boundPort : requestedPort;
    }

    public int getRequestedPort() {
        return requestedPort;
    }

    public String getPin() {
        return configuredPin;
    }

    /** URL shown to the phone. Host is caller supplied to avoid guessing Wi-Fi address. */
    public String getUploadUrl(String host) {
        String value = host == null || host.trim().length() == 0 ? "127.0.0.1" : host.trim();
        return "http://" + value + ":" + getPort() + "/";
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket socket = serverSocket;
            if (socket == null) {
                break;
            }
            try {
                final Socket client = socket.accept();
                client.setSoTimeout(socketTimeoutMs);
                Thread requestThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client);
                    }
                }, "tcl-rule-upload-client");
                requestThread.setDaemon(true);
                requestThread.start();
            } catch (SocketException closed) {
                if (running && socket == serverSocket) {
                    notifyError(closed);
                }
                break;
            } catch (IOException error) {
                if (running && socket == serverSocket) {
                    notifyError(error);
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            Request request = readRequest(client.getInputStream());
            if (request == null) {
                return;
            }
            Response response = dispatch(request, client);
            writeResponse(client.getOutputStream(), response);
        } catch (Exception error) {
            notifyError(error);
            try {
                writeResponse(client.getOutputStream(), Response.json(500,
                        "{\"ok\":false,\"error\":\"server error\"}"));
            } catch (Exception ignored) {
            }
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Response dispatch(Request request, Socket client) throws IOException {
        String path = request.path;
        if ("GET".equals(request.method) || "HEAD".equals(request.method)) {
            if ("/".equals(path) || "/index.html".equals(path) || "/upload".equals(path)) {
                Response form = Response.html(uploadPage());
                return "HEAD".equals(request.method) ? form.withoutBody() : form;
            }
            if ("/api/status".equals(path)) {
                String status = "{\"ok\":true,\"running\":" + running
                        + ",\"port\":" + getPort()
                        + ",\"pinRequired\":" + (!configuredPin.isEmpty()) + "}";
                Response result = Response.json(200, status);
                return "HEAD".equals(request.method) ? result.withoutBody() : result;
            }
            return Response.json(404, "{\"ok\":false,\"error\":\"not found\"}");
        }
        if ("OPTIONS".equals(request.method)) {
            return new Response(204, "No Content", "text/plain; charset=UTF-8", new byte[0]);
        }
        if (!"POST".equals(request.method)) {
            return Response.json(405, "{\"ok\":false,\"error\":\"method not allowed\"}");
        }
        if (!("/".equals(path) || "/upload".equals(path) || "/api/upload".equals(path)
                || "/api/rules".equals(path))) {
            return Response.json(404, "{\"ok\":false,\"error\":\"not found\"}");
        }

        ParsedUpload upload = parseUpload(request);
        String suppliedPin = firstNonEmpty(request.headers.get("x-upload-pin"),
                request.headers.get("x-pin"), request.query.get("pin"), upload.pin);
        if (!pinMatches(suppliedPin)) {
            return Response.json(401, "{\"ok\":false,\"error\":\"invalid pin\"}");
        }
        if (upload.rule == null || upload.rule.trim().length() == 0) {
            return Response.json(400, "{\"ok\":false,\"error\":\"missing rule\"}");
        }
        String rule = upload.rule.trim();
        String remote = client.getInetAddress() == null ? "" : client.getInetAddress().getHostAddress();
        Upload value = new Upload(rule, upload.fileName, remote);
        try {
            notifyUpload(value);
        } catch (Exception error) {
            notifyError(error);
            return Response.json(500, "{\"ok\":false,\"error\":\"callback failed\"}");
        }
        String file = value.fileName == null ? "" : value.fileName;
        return Response.json(200, "{\"ok\":true,\"bytes\":" + rule.getBytes(UTF8).length
                + ",\"fileName\":" + quoteJson(file) + "}");
    }

    private void notifyUpload(Upload upload) {
        if (callback != null) {
            callback.onRuleUploaded(upload.ruleJson, upload.fileName);
        }
        if (uploadCallback != null) {
            uploadCallback.onRuleUploaded(upload.ruleJson, upload.fileName);
        }
        if (callback == null && uploadCallback == null) {
            throw new IllegalStateException("upload callback is not set");
        }
    }

    private void notifyError(Exception error) {
        try {
            if (callback != null) {
                callback.onError(error);
            }
            if (uploadCallback != null) {
                uploadCallback.onError(error);
            }
        } catch (RuntimeException ignored) {
            // Callback errors must not kill accept loop.
        }
    }

    private Request readRequest(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        String requestLine = readLine(buffered);
        if (requestLine == null || requestLine.trim().length() == 0) {
            return null;
        }
        String[] first = requestLine.trim().split("\\s+");
        if (first.length < 2) {
            throw new IOException("invalid request line");
        }
        String method = first[0].toUpperCase(Locale.US);
        String target = first[1];
        Map<String, String> headers = new HashMap<String, String>();
        String line;
        while ((line = readLine(buffered)) != null) {
            if (line.length() == 0) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }
        long length = parseLength(headers.get("content-length"));
        if (length > MAX_UPLOAD_BYTES) {
            throw new IOException("upload too large");
        }
        byte[] body = readBody(buffered, (int) length);
        int question = target.indexOf('?');
        String rawPath = question < 0 ? target : target.substring(0, question);
        String rawQuery = question < 0 ? "" : target.substring(question + 1);
        String path = decode(rawPath);
        if (path.length() == 0) {
            path = "/";
        }
        return new Request(method, path, parseParameters(rawQuery), headers, body);
    }

    private ParsedUpload parseUpload(Request request) throws IOException {
        String contentType = request.headers.get("content-type");
        if (contentType == null) {
            contentType = "";
        }
        String lower = contentType.toLowerCase(Locale.US);
        if (lower.startsWith("multipart/form-data")) {
            String boundary = boundary(contentType);
            if (boundary.length() == 0) {
                throw new IOException("multipart boundary missing");
            }
            return parseMultipart(request.body, boundary);
        }
        String text = new String(request.body, UTF8);
        if (lower.indexOf("application/x-www-form-urlencoded") >= 0) {
            Map<String, String> fields = parseParameters(text);
            String rule = firstNonEmpty(fields.get("rule"), fields.get("json"), fields.get("content"),
                    fields.get("data"), fields.get("file"));
            return new ParsedUpload(rule, fields.get("pin"), "");
        }
        if (lower.indexOf("application/json") >= 0 || text.trim().startsWith("{")
                || text.trim().startsWith("[")) {
            return parseJson(text);
        }
        return new ParsedUpload(text, null, "");
    }

    private ParsedUpload parseJson(String text) {
        String value = text == null ? "" : text.trim();
        if (value.length() == 0) {
            return new ParsedUpload("", null, "");
        }
        try {
            if (value.startsWith("[")) {
                new JSONArray(value); // Validate array while retaining exact formatting.
                return new ParsedUpload(value, null, "");
            }
            JSONObject object = new JSONObject(value);
            String pin = object.optString("pin", null);
            String fileName = object.optString("fileName", object.optString("filename", ""));
            Object rule = object.opt("rule");
            if (rule == null) {
                rule = object.opt("json");
            }
            if (rule == null) {
                rule = object.opt("content");
            }
            if (rule != null) {
                if (rule instanceof JSONObject || rule instanceof JSONArray) {
                    return new ParsedUpload(rule.toString(), pin, fileName);
                }
                return new ParsedUpload(String.valueOf(rule), pin, fileName);
            }
            return new ParsedUpload(value, pin, fileName);
        } catch (JSONException ignored) {
            return new ParsedUpload(value, null, "");
        }
    }

    private ParsedUpload parseMultipart(byte[] body, String boundary) throws IOException {
        String value = new String(body, ISO88591);
        String marker = "--" + boundary;
        int cursor = value.indexOf(marker);
        String pin = null;
        String rule = null;
        String fileName = "";
        while (cursor >= 0) {
            int partStart = cursor + marker.length();
            if (partStart + 1 < value.length() && value.startsWith("--", partStart)) {
                break;
            }
            if (value.startsWith("\r\n", partStart)) {
                partStart += 2;
            } else if (value.startsWith("\n", partStart)) {
                partStart += 1;
            }
            int headerEnd = value.indexOf("\r\n\r\n", partStart);
            int separatorLength = 4;
            if (headerEnd < 0) {
                headerEnd = value.indexOf("\n\n", partStart);
                separatorLength = 2;
            }
            if (headerEnd < 0) {
                break;
            }
            String partHeaders = value.substring(partStart, headerEnd);
            int dataStart = headerEnd + separatorLength;
            int next = value.indexOf("\r\n" + marker, dataStart);
            int prefix = 2;
            if (next < 0) {
                next = value.indexOf("\n" + marker, dataStart);
                prefix = 1;
            }
            if (next < 0) {
                next = value.length();
                prefix = 0;
            }
            String data = value.substring(dataStart, next);
            Map<String, String> disposition = parseDisposition(partHeaders);
            String name = disposition.get("name");
            if (name != null) {
                String decoded = new String(data.getBytes(ISO88591), UTF8).trim();
                if ("pin".equalsIgnoreCase(name)) {
                    pin = decoded;
                } else if ("rule".equalsIgnoreCase(name) || "json".equalsIgnoreCase(name)
                        || "content".equalsIgnoreCase(name) || "data".equalsIgnoreCase(name)
                        || "file".equalsIgnoreCase(name)) {
                    if (decoded.length() > 0 || rule == null) {
                        rule = decoded;
                    }
                    String partFile = disposition.get("filename");
                    if (partFile != null && partFile.length() > 0) {
                        fileName = partFile;
                    }
                }
            }
            cursor = next + prefix;
            if (cursor >= value.length()) {
                break;
            }
        }
        return new ParsedUpload(rule, pin, fileName);
    }

    private static Map<String, String> parseDisposition(String headers) {
        Map<String, String> result = new HashMap<String, String>();
        String[] lines = headers.split("\\r?\\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String headerName = line.substring(0, colon).trim().toLowerCase(Locale.US);
            if (!"content-disposition".equals(headerName)) {
                continue;
            }
            String[] values = line.substring(colon + 1).split(";");
            for (String item : values) {
                int equals = item.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = item.substring(0, equals).trim().toLowerCase(Locale.US);
                String value = item.substring(equals + 1).trim();
                if (value.length() >= 2 && value.charAt(0) == '"'
                        && value.charAt(value.length() - 1) == '"') {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
        }
        return result;
    }

    private String uploadPage() {
        String pinField = configuredPin.length() == 0 ? "" :
                "<label>PIN <input name=pin type=password autocomplete=off></label>";
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport "
                + "content='width=device-width,initial-scale=1'><title>TCL TVBox</title>"
                + "<style>body{font-family:system-ui,Arial;background:#111827;color:#e5e7eb;"
                + "max-width:760px;margin:0 auto;padding:28px}main{background:#1f2937;padding:24px;"
                + "border-radius:14px;box-shadow:0 12px 36px #0006}h1{margin:0 0 8px;font-size:28px}"
                + "p{color:#9ca3af}textarea{width:100%;min-height:280px;box-sizing:border-box;"
                + "background:#111827;color:#e5e7eb;border:1px solid #4b5563;border-radius:8px;"
                + "padding:12px;font:14px monospace}input,button{font-size:16px;padding:10px 12px;"
                + "border-radius:8px;margin-top:12px}button{background:#38bdf8;border:0;color:#082f49;"
                + "font-weight:700;cursor:pointer}.hint{font-size:13px}</style></head><body><main>"
                + "<h1>TCL TVBox 规则导入</h1><p>从手机选择规则文件，提交后电视立即更新。</p>"
                + "<form method=post action=/upload enctype=multipart/form-data>" + pinField
                + "<p><input type=file name=file accept='.json,.txt,application/json' required></p>"
                + "<br><button type=submit>上传规则</button></form><p class=hint>仅限同一局域网使用。</p>"
                + "</main></body></html>";
    }

    private boolean pinMatches(String supplied) {
        if (configuredPin.length() == 0) {
            return true;
        }
        String left = configuredPin;
        String right = supplied == null ? "" : supplied.trim();
        int difference = left.length() ^ right.length();
        int count = Math.max(left.length(), right.length());
        for (int i = 0; i < count; i++) {
            char a = i < left.length() ? left.charAt(i) : 0;
            char b = i < right.length() ? right.charAt(i) : 0;
            difference |= a ^ b;
        }
        return difference == 0;
    }

    private static long parseLength(String value) throws IOException {
        if (value == null || value.length() == 0) {
            return 0;
        }
        try {
            long length = Long.parseLong(value.trim());
            if (length < 0) {
                throw new IOException("invalid content length");
            }
            return length;
        } catch (NumberFormatException error) {
            throw new IOException("invalid content length");
        }
    }

    private static byte[] readBody(InputStream input, int length) throws IOException {
        if (length == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(length);
        byte[] buffer = new byte[8192];
        int remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("request body truncated");
            }
            output.write(buffer, 0, count);
            remaining -= count;
        }
        return output.toByteArray();
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                output.write(value);
            }
            if (output.size() > 16 * 1024) {
                throw new IOException("request line too long");
            }
        }
        if (value == -1 && output.size() == 0) {
            return null;
        }
        return output.toString("ISO-8859-1");
    }

    private static String boundary(String contentType) {
        String[] parts = contentType.split(";");
        for (String part : parts) {
            int equals = part.indexOf('=');
            if (equals < 0) {
                continue;
            }
            if (!"boundary".equalsIgnoreCase(part.substring(0, equals).trim())) {
                continue;
            }
            String value = part.substring(equals + 1).trim();
            if (value.length() >= 2 && value.charAt(0) == '"'
                    && value.charAt(value.length() - 1) == '"') {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return "";
    }

    private static Map<String, String> parseParameters(String source) {
        if (source == null || source.length() == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<String, String>();
        String[] pairs = source.split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            result.put(decode(key), decode(value));
        }
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, "UTF-8");
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && value.trim().length() > 0) {
                return value;
            }
        }
        return null;
    }

    private static String normalizePin(String pin) {
        return pin == null ? "" : pin.trim();
    }

    private static String quoteJson(String value) {
        if (value == null) {
            return "null";
        }
        try {
            return JSONObject.quote(value);
        } catch (RuntimeException ignored) {
            StringBuilder result = new StringBuilder(value.length() + 2);
            result.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\' || c == '"') {
                    result.append('\\');
                }
                if (c == '\n') {
                    result.append("\\n");
                } else if (c == '\r') {
                    result.append("\\r");
                } else {
                    result.append(c);
                }
            }
            return result.append('"').toString();
        }
    }

    private static void writeResponse(OutputStream output, Response response) throws IOException {
        BufferedOutputStream buffered = new BufferedOutputStream(output);
        byte[] body = response.body == null ? new byte[0] : response.body;
        String headers = "HTTP/1.1 " + response.status + " " + response.reason + "\r\n"
                + "Content-Type: " + response.contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        buffered.write(headers.getBytes(ISO88591));
        if (body.length > 0) {
            buffered.write(body);
        }
        buffered.flush();
    }

    private static final java.nio.charset.Charset UTF8 = java.nio.charset.Charset.forName("UTF-8");
    private static final java.nio.charset.Charset ISO88591 = java.nio.charset.Charset.forName("ISO-8859-1");

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> query;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String path, Map<String, String> query,
                Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class ParsedUpload {
        final String rule;
        final String pin;
        final String fileName;

        ParsedUpload(String rule, String pin, String fileName) {
            this.rule = rule;
            this.pin = pin;
            this.fileName = fileName;
        }
    }

    private static final class Response {
        final int status;
        final String reason;
        final String contentType;
        final byte[] body;

        Response(int status, String reason, String contentType, byte[] body) {
            this.status = status;
            this.reason = reason;
            this.contentType = contentType;
            this.body = body;
        }

        static Response html(String value) {
            return new Response(200, "OK", "text/html; charset=UTF-8", value.getBytes(UTF8));
        }

        static Response json(int status, String value) {
            String reason;
            switch (status) {
                case 200:
                    reason = "OK";
                    break;
                case 204:
                    reason = "No Content";
                    break;
                case 400:
                    reason = "Bad Request";
                    break;
                case 401:
                    reason = "Unauthorized";
                    break;
                case 404:
                    reason = "Not Found";
                    break;
                case 405:
                    reason = "Method Not Allowed";
                    break;
                case 500:
                    reason = "Internal Server Error";
                    break;
                default:
                    reason = "OK";
            }
            return new Response(status, reason, "application/json; charset=UTF-8", value.getBytes(UTF8));
        }

        Response withoutBody() {
            return new Response(status, reason, contentType, new byte[0]);
        }
    }
}
