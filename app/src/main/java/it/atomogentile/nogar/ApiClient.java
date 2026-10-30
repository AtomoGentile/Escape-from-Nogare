package it.atomogentile.nogar;

import java.io.*;
import java.net.*;
import org.json.*;

final class ApiClient {
    static JSONObject post(String endpoint, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint + "/api").openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes("UTF-8"));
            }
            int status = c.getResponseCode();
            InputStream stream = status < 400 ? c.getInputStream() : c.getErrorStream();
            if (stream == null) throw new IOException("HTTP " + status);
            try (InputStream in = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096]; int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > 262144) throw new IOException("Response too large");
                }
                return new JSONObject(buffer.toString("UTF-8"));
            }
        } finally { c.disconnect(); }
    }
}
