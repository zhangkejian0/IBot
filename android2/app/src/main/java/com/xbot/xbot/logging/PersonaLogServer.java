package com.xbot.xbot.logging;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

/**
 * NanoHTTPD server exposing persona logs on the LAN (default port 8765).
 */
public class PersonaLogServer extends NanoHTTPD {
    private static final String TAG = "PersonaLogServer";
    private static final int DEFAULT_PORT = 8765;
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Gson GSON = new Gson();

    private final PersonaLogger logger;
    @Nullable private String url;

    public PersonaLogServer(PersonaLogger logger) {
        super(DEFAULT_PORT);
        this.logger = logger;
    }

    public PersonaLogServer(PersonaLogger logger, int port) {
        super(port);
        this.logger = logger;
    }

    @Nullable
    public String getUrl() {
        return url;
    }

    public String startServer() throws IOException {
        if (!isAlive()) {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        }
        String ip = detectLanIpv4();
        url = "http://" + (ip != null ? ip : "localhost") + ":" + getListeningPort();
        Log.i(TAG, "started at " + url);
        return url;
    }

    public void stopServer() {
        stop();
        url = null;
        Log.i(TAG, "stopped");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = session.getUri();
        Method method = session.getMethod();

        try {
            if ("/".equals(path) || "/index.html".equals(path)) {
                return withCors(newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html; charset=utf-8",
                        dashboardHtml()));
            }
            if ("/healthz".equals(path)) {
                return withCors(jsonResponse("{\"ok\":true}"));
            }
            if ("/api/dates".equals(path)) {
                List<String> dates = awaitDates();
                Map<String, Object> body = new HashMap<>();
                body.put("dates", dates);
                body.put("dir", logger.getDirectoryPath());
                return withCors(jsonResponse(GSON.toJson(body)));
            }
            if ("/api/logs".equals(path)) {
                String date = session.getParms().get("date");
                if (date == null || !DATE_PATTERN.matcher(date).matches()) {
                    return withCors(jsonResponse(
                            "{\"error\":\"missing or invalid date\"}",
                            Response.Status.BAD_REQUEST));
                }
                if (Method.DELETE.equals(method)) {
                    CountDownLatch latch = new CountDownLatch(1);
                    logger.deleteDate(date, latch::countDown);
                    latch.await(5, TimeUnit.SECONDS);
                    return withCors(jsonResponse("{\"ok\":true,\"deleted\":\"" + date + "\"}"));
                }
                List<PersonaLogger.PersonaLogEntry> entries = awaitLogs(date);
                List<Map<String, Object>> logs = new ArrayList<>();
                for (PersonaLogger.PersonaLogEntry e : entries) {
                    logs.add(e.toJson());
                }
                Map<String, Object> body = new HashMap<>();
                body.put("date", date);
                body.put("count", logs.size());
                body.put("logs", logs);
                return withCors(jsonResponse(GSON.toJson(body)));
            }
            return withCors(newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "404 Not Found"));
        } catch (Exception e) {
            Log.e(TAG, "handle error", e);
            return withCors(newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "500 Internal Server Error"));
        }
    }

    private List<String> awaitDates() throws InterruptedException {
        final List<String>[] holder = new List[1];
        CountDownLatch latch = new CountDownLatch(1);
        logger.availableDates(dates -> {
            holder[0] = dates;
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        return holder[0] != null ? holder[0] : Collections.emptyList();
    }

    private List<PersonaLogger.PersonaLogEntry> awaitLogs(String date) throws InterruptedException {
        final List<PersonaLogger.PersonaLogEntry>[] holder = new List[1];
        CountDownLatch latch = new CountDownLatch(1);
        logger.readDate(date, entries -> {
            holder[0] = entries;
            latch.countDown();
        });
        latch.await(10, TimeUnit.SECONDS);
        return holder[0] != null ? holder[0] : Collections.emptyList();
    }

    private static Response jsonResponse(String json) {
        return jsonResponse(json, Response.Status.OK);
    }

    private static Response jsonResponse(String json, Response.Status status) {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json);
    }

    private static Response withCors(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    @Nullable
    private static String detectLanIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "lan ip detect failed", e);
        }
        return null;
    }

    private static String dashboardHtml() {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<title>XBot Persona Logs</title>"
                + "<style>body{font-family:sans-serif;margin:24px}"
                + "table{border-collapse:collapse;width:100%}"
                + "td,th{border:1px solid #ccc;padding:6px;font-size:13px}</style></head>"
                + "<body><h1>Persona Logs</h1>"
                + "<label>Date <select id=\"dates\"></select></label> "
                + "<button onclick=\"load()\">Load</button>"
                + "<table><thead><tr><th>Time</th><th>Type</th><th>Person</th>"
                + "<th>User</th><th>Reply</th></tr></thead><tbody id=\"rows\"></tbody></table>"
                + "<script>"
                + "async function init(){const r=await fetch('/api/dates');const j=await r.json();"
                + "const s=document.getElementById('dates');(j.dates||[]).forEach(d=>{"
                + "const o=document.createElement('option');o.value=d;o.textContent=d;s.appendChild(o)});"
                + "if(s.options.length)load();}"
                + "async function load(){const d=document.getElementById('dates').value;"
                + "const r=await fetch('/api/logs?date='+encodeURIComponent(d));const j=await r.json();"
                + "const tb=document.getElementById('rows');tb.innerHTML='';"
                + "(j.logs||[]).forEach(e=>{const tr=document.createElement('tr');"
                + "tr.innerHTML='<td>'+(e.ts||'')+'</td><td>'+(e.type||'')+'</td><td>'"
                + "+(e.person||'')+'</td><td>'+(e.userText||'')+'</td><td>'+(e.replyText||'')+'</td>';"
                + "tb.appendChild(tr)});}"
                + "init();</script></body></html>";
    }
}
