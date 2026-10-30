package it.atomogentile.nogar;

import java.io.*;
import java.net.*;
import org.json.*;

final class ApiClient {
    static void health(String endpoint) throws Exception {
        if(FirebaseRelay.isEndpoint(endpoint)){FirebaseRelay.client(endpoint).health();return;}
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint + "/health").openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) throw new IOException("Server health check failed");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[1024]; int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > 4096) throw new IOException("Invalid health response");
                }
                JSONObject result = new JSONObject(buffer.toString("UTF-8"));
                if (!result.optBoolean("ok", false) || !"nogar-relay".equals(result.optString("service")))
                    throw new IOException("Not a Nogar server");
            }
        } finally { c.disconnect(); }
    }

    static JSONObject post(String endpoint, JSONObject body) throws Exception {
        if(FirebaseRelay.isEndpoint(endpoint))return FirebaseRelay.client(endpoint).request(body);
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
