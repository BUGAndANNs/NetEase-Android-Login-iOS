package cn.ratwl.androidloginios.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 有界、单次请求的本地 HTTP 解析器；请求体不落日志。 */
public final class HttpRequest {
    private static final Pattern CONTENT_LENGTH = Pattern.compile("^[0-9]{1,5}$");
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 4096;

    public final String method;
    public final String path;
    public final Map<String, String> headers;
    public final byte[] body;

    private HttpRequest(String method, String path, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.headers = headers;
        this.body = body;
    }

    public static HttpRequest read(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        int end = 0;
        while (end != 4) {
            int b = input.read();
            if (b < 0) {
                throw new IOException("incomplete-header");
            }
            head.write(b);
            if (head.size() > MAX_HEADER_BYTES) {
                throw new IOException("header-too-large");
            }
            if ((end == 0 || end == 2) && b == '\r') {
                end = end + 1;
            } else if ((end == 1 || end == 3) && b == '\n') {
                end = end + 1;
            } else if (b == '\r') {
                end = 1;
            } else {
                end = 0;
            }
        }

        String rawHeader = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
        if (rawHeader.endsWith("\r\n\r\n")) {
            rawHeader = rawHeader.substring(0, rawHeader.length() - 4);
        }
        String[] lines = rawHeader.split("\r\n");
        if (lines.length == 0) {
            throw new IOException("request-line");
        }

        String[] first = lines[0].split(" ");
        if (first.length != 3 || !"HTTP/1.1".equals(first[2])
                || (!"GET".equals(first[0]) && !"POST".equals(first[0]))) {
            throw new IOException("request-line");
        }
        String requestPath = first[1];
        if (!requestPath.startsWith("/") || requestPath.contains("..") || requestPath.contains("%")) {
            throw new IOException("request-path");
        }

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon < 1) {
                throw new IOException("header-format");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (headers.containsKey(name)) {
                throw new IOException("duplicate-header");
            }
            headers.put(name, value);
        }

        if (headers.containsKey("transfer-encoding")) {
            throw new IOException("unsupported-transfer");
        }

        String lengthHeader = headers.get("content-length");
        int count = 0;
        if (lengthHeader != null) {
            if (!CONTENT_LENGTH.matcher(lengthHeader).matches()) {
                throw new IOException("body-length");
            }
            count = Integer.parseInt(lengthHeader);
            if (count > MAX_BODY_BYTES) {
                throw new IOException("body-too-large");
            }
        }
        if ("POST".equals(first[0]) && lengthHeader == null) {
            throw new IOException("body-length-missing");
        }

        byte[] body = new byte[count];
        int offset = 0;
        while (offset < count) {
            int got = input.read(body, offset, count - offset);
            if (got < 0) {
                throw new IOException("incomplete-body");
            }
            offset += got;
        }
        return new HttpRequest(first[0], requestPath, headers, body);
    }
}
