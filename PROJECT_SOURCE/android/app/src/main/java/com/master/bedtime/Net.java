package com.master.bedtime;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class Net {
    private Net(){}
    public static JSONObject request(String method, String base, String path, String tokenHeader, String tokenValue, JSONObject body) throws Exception {
        return request(method, base, path, tokenHeader, tokenValue, body, 6000, 6000);
    }

    /** Route-specific timeout overload for quick local failure and responsive cloud fallback. */
    public static JSONObject request(String method, String base, String path, String tokenHeader, String tokenValue, JSONObject body, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        URL u = new URL(base.replaceAll("/$", "") + path);
        HttpURLConnection c = (HttpURLConnection)u.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(Math.max(500, connectTimeoutMs));
        c.setReadTimeout(Math.max(500, readTimeoutMs));
        c.setUseCaches(false);
        c.setRequestProperty("Accept","application/json");
        if(tokenHeader != null && tokenValue != null && !tokenValue.isEmpty()) c.setRequestProperty(tokenHeader, tokenValue);
        if(body != null){ c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json; charset=utf-8"); try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));} }
        int code=c.getResponseCode(); InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        StringBuilder s=new StringBuilder(); if(in!=null) try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}
        c.disconnect(); JSONObject out=new JSONObject(s.toString().isEmpty()?"{}":s.toString()); if(code<200||code>=300) throw new Exception(out.optString("error","HTTP "+code)); return out;
    }
}
