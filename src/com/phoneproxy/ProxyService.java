package com.phoneproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;


public class ProxyService extends Service {
    
    private static final String CHANNEL_ID = "phone_proxy_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String SERVER_URL = "https://svoyaigra.pro/api/proxy.php";
    private static final int POLL_INTERVAL = 5000;
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 30000;
    
    // ===== СОСТОЯНИЕ (хранится в Service!) =====
    public boolean isRunning = false;
    public String token = null;
    public int totalTasks = 0;
    public int completedTasks = 0;
    public int failedTasks = 0;
    
    // ===== ЛОГ =====
    public StringBuilder logBuilder = new StringBuilder();
    
    // ===== WakeLock — держим CPU активным, чтобы Android не усыпил сервис =====
    private PowerManager.WakeLock wakeLock = null;
    
    // Binder для связи с Activity
    private final LocalBinder binder = new LocalBinder();
    
    public class LocalBinder extends Binder {
        public ProxyService getService() {
            return ProxyService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        addLog("=================================");
        addLog("Phone Proxy Service создан");
        addLog("Сервер: " + SERVER_URL);
        addLog("=================================");
        
        // Разрешение работы без ограничений батареи (Android 6+)
        requestIgnoreBatteryOptimizations();
    }
    
    // ===== ЗАПРОС ОТКЛЮЧЕНИЯ BATTERY OPTIMIZATION =====
    // ИСПРАВЛЕНО: раньше этот блок был в теле класса (вне метода) — это синтаксическая ошибка,
    // класс не компилировался. Теперь это отдельный метод, вызывается из onCreate().
    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Exception e) {
            addLog("⚠️ Battery optimization запрос не удался: " + e.getMessage());
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());
        
        if (!isRunning) {
            startProxy();
        }
        
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    // ===== ЗАПУСК/ОСТАНОВКА =====
    
    public void startProxy() {
        // Защита от повторного запуска.
        if (isRunning) return;
        
        isRunning = true;
        addLog("▶ ЗАПУСК ПРОКСИ");
        
        // Держим CPU активным, пока идёт поллинг
        acquireWakeLock();
        
        updateNotification("Подключение...");
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                register();
            }
        }).start();
    }
    
    public void stopProxy() {
        isRunning = false;
        token = null;
        addLog("⏹ ОСТАНОВКА ПРОКСИ");
        
        releaseWakeLock();
        
        updateNotification("Остановлено");
    }
    
    // ===== WAKELOCK =====
    // PARTIAL_WAKE_LOCK держит CPU активным при погашенном экране.
    // Без него Android уводит сервис в Doze — задачи приходят пачкой раз в 15-120 минут.
    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneProxy:PollLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            addLog("🔒 WakeLock получен");
        } catch (Exception e) {
            addLog("⚠️ WakeLock не удался: " + e.getMessage());
        }
    }
    
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                addLog("🔓 WakeLock отпущен");
            }
        } catch (Exception e) {
            // ignore
        }
        wakeLock = null;
    }
    
    // ===== РЕГИСТРАЦИЯ =====
    
    private void register() {
        try {
            addLog("📡 Регистрация на сервере...");

            long startTime = System.currentTimeMillis();

            String apiKey = getApiKey();
            String deviceName = getOrCreateDeviceName();

            String jsonBody;
            if (apiKey != null && !apiKey.isEmpty()) {
                jsonBody = "{\"action\":\"register\",\"device_name\":\"" + deviceName + "\",\"api_key\":\"" + apiKey + "\"}";
            } else {
                jsonBody = "{\"action\":\"register\",\"device_name\":\"" + deviceName + "\"}";
            }

            String response = makeRequest(SERVER_URL, jsonBody);
            
            long elapsed = System.currentTimeMillis() - startTime;
            
            addLog("✅ Ответ за " + elapsed + "мс");
            
            if (response.contains("\"token\"")) {
                if (response.contains("<br />")) {
                    int jsonStart = response.indexOf("{\"success\"");
                    if (jsonStart >= 0) {
                        response = response.substring(jsonStart);
                    }
                }
                
                int start = response.indexOf("\"token\":\"") + 9;
                int end = response.indexOf("\"", start);
                
                if (start > 9 && end > start) {
                    token = response.substring(start, end);
                    addLog("🔑 Токен: " + token.substring(0, Math.min(16, token.length())) + "...");
                    
                    updateNotification("Подключено");
                    
                    startTaskPolling();
                }
            } else {
                addLog("❌ Токен не найден!");
                updateNotification("Ошибка регистрации");
            }
            
        } catch (Exception e) {
            addLog("❌ ОШИБКА РЕГИСТРАЦИИ: " + e.getMessage());
            updateNotification("Ошибка: " + e.getMessage());
        }
    }

    private String getApiKey() {
        android.content.SharedPreferences prefs = 
            getSharedPreferences("PhoneProxyPrefs", MODE_PRIVATE);
        return prefs.getString("api_key", null);
    }

    private String getOrCreateDeviceName() {
        android.content.SharedPreferences prefs = 
            getSharedPreferences("PhoneProxyPrefs", MODE_PRIVATE);
        
        String deviceName = prefs.getString("device_name", null);
        if (deviceName != null && !deviceName.isEmpty()) {
            return deviceName;
        }
        
        String androidId = null;
        try {
            androidId = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ANDROID_ID
            );
        } catch (Exception e) {
            // ignore
        }
        
        boolean isBad = (androidId == null || androidId.isEmpty() 
                         || "9774d56d682e549c".equals(androidId)
                         || "unknown".equalsIgnoreCase(androidId));
        
        if (isBad) {
            androidId = java.util.UUID.randomUUID().toString().replace("-", "");
        }
        
        String shortId = androidId.substring(0, Math.min(8, androidId.length()));
        deviceName = "Android_" + shortId;
        
        prefs.edit().putString("device_name", deviceName).apply();
        return deviceName;
    }
    
    // ===== ЦИКЛ ЗАДАНИЙ =====
    
    private void startTaskPolling() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int pollCount = 0;
                
                while (isRunning && token != null) {
                    pollCount++;
                    
                    try {
                        long startTime = System.currentTimeMillis();
                        
                        String response = makeRequest(SERVER_URL, 
                            "{\"action\":\"get_tasks\",\"token\":\"" + token + "\"}");
                        
                        long elapsed = System.currentTimeMillis() - startTime;
                        
                        if (response.contains("<br />")) {
                            int jsonStart = response.indexOf("{\"success\"");
                            if (jsonStart >= 0) {
                                response = response.substring(jsonStart);
                            }
                        }
                        
                        if (response.contains("\"tasks\":[]") || response.contains("\"tasks\": []")) {
                            if (pollCount % 12 == 1) {
                                addLog("⏳ Нет заданий (#" + pollCount + ", " + elapsed + "мс)");
                            }
                        } else if (response.contains("\"tasks\"")) {
                            addLog("📋 ПОЛУЧЕНЫ ЗАДАНИЯ! (" + elapsed + "мс)");
                            executeTasks(response);
                        } else if (response.contains("\"error\"")) {
                            addLog("❌ API: " + truncate(response, 100));
                            
                            if (response.contains("Invalid token")) {
                                addLog("🔑 Токен невалиден! Перезапуск...");
                                token = null;
                                break;
                            }
                        }
                        
                        Thread.sleep(POLL_INTERVAL);
                        
                    } catch (InterruptedException e) {
                        addLog("⏹ Цикл остановлен");
                        break;
                    } catch (Exception e) {
                        addLog("❌ Ошибка опроса: " + e.getMessage());
                        
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
                
                addLog("🔚 Цикл завершён");
            }
        }).start();
    }

    // Человекочитаемое описание задачи — не палим URL VK-метода
    private String describeTask(String url) {
        if (url == null || url.isEmpty()) return "Запрос";
        if (url.contains("/wall.get"))             return "Чтение постов";
        if (url.contains("/wall.createComment"))   return "Отправка комментария";
        if (url.contains("/likes.add"))            return "Постановка лайка";
        if (url.contains("/friends.add"))          return "Заявка в друзья";
        if (url.contains("/messages.send"))        return "Отправка сообщения";
        if (url.contains("/messages.setActivity")) return "Печатает...";
        if (url.contains("/users.get"))            return "Получение профиля";
        return "Запрос";
    }

    // ===== ВЫПОЛНЕНИЕ ЗАДАНИЙ =====
    
    private void executeTasks(String response) {
        try {
            JSONObject jsonResponse = new JSONObject(response);
            JSONArray tasks = jsonResponse.getJSONArray("tasks");
            
            for (int i = 0; i < tasks.length(); i++) {
                if (!isRunning) break;
                
                JSONObject task = tasks.getJSONObject(i);
                
                String taskId = task.getString("id");
                String url = task.getString("url");
                String method = task.optString("method", "GET");
                String body = task.optString("body", null);
                
                Map<String, String> headers = new HashMap<>();
                if (task.has("headers") && !task.isNull("headers")) {
                    Object headersRaw = task.opt("headers");
                    if (headersRaw instanceof JSONObject) {
                        JSONObject headersJson = (JSONObject) headersRaw;
                        JSONArray keys = headersJson.names();
                        if (keys != null) {
                            for (int j = 0; j < keys.length(); j++) {
                                String key = keys.getString(j);
                                String value = headersJson.getString(key);
                                headers.put(key, value);
                            }
                        }
                    }
                }
                
                String actionType = task.optString("action_type", "http");

                if ("vk_collect".equals(actionType)) {
                    addLog("📤 VK-задача [" + (i+1) + "]: ID=" + taskId);
                    String payloadStr = task.optString("payload", null);
                    executeVkCollectTask(taskId, payloadStr);
                } else if (url != null && taskId != null) {
                    url = url.replace("\\/", "/");
                    addLog("📤 Задание [" + (i+1) + "]: ID=" + taskId);
                    addLog("   URL: " + url);
                    
                    executeTask(taskId, url, method, headers, body);
                }
            }
            
        } catch (Exception e) {
            addLog("❌ ОШИБКА парсинга JSON: " + e.getMessage());
        }
    }

    // ===== VK-ЗАДАЧА: собрать группы клиента =====
    private void executeVkCollectTask(String taskId, String payloadJson) {
        totalTasks++;
        updateNotification("VK: синхронизация групп...");

        addLog("🔎 RAW payload: [" + (payloadJson == null ? "null" : payloadJson) + "]");

        if (payloadJson == null || payloadJson.isEmpty()) {
            addLog("❌ VK: пустой payload");
            sendError(taskId, "empty_payload");
            failedTasks++;
            return;
        }

        String token = null;
        int vkUserId = 0;
        String ua = null;
        try {
            JSONObject p = new JSONObject(payloadJson);
            token    = p.optString("token", null);
            vkUserId = p.optInt("vk_user_id", 0);
            ua       = p.optString("user_agent", "");
        } catch (Exception e) {
            addLog("❌ VK: bad payload: " + e.getMessage());
            sendError(taskId, "bad_payload");
            failedTasks++;
            return;
        }

        if (token == null || token.isEmpty() || vkUserId <= 0) {
            addLog("❌ VK: не хватает token/vk_user_id");
            sendError(taskId, "bad_payload_fields");
            failedTasks++;
            return;
        }

        String[] roleFilter   = {"admin", "editor", "moderator"};
        int[]    adminLevels  = {1, 2, 3};

        Map<String, JSONObject> byId = new HashMap<>();

        try {
            for (int i = 0; i < roleFilter.length; i++) {
                if (!isRunning) { addLog("⏹ VK: остановлено"); return; }
                if (i > 0) Thread.sleep(1500);

                String role = roleFilter[i];
                String apiUrl = "https://web.api.vk.ru/method/groups.get"
                        + "?filter=" + role
                        + "&extended=1&count=1000&offset=0"
                        + "&fields=members_count,screen_name,photo_200,description,is_closed,type,is_admin,admin_level"
                        + "&access_token=" + java.net.URLEncoder.encode(token, "UTF-8")
                        + "&v=5.199";

                String respBody = vkHttpGet(apiUrl, ua);
                JSONObject respJson = new JSONObject(respBody);

                if (respJson.has("error")) {
                    JSONObject err = respJson.getJSONObject("error");
                    int code = err.optInt("error_code", 0);

                    if (code == 5 || code == 10) {
                        addLog("❌ VK: токен протух (код " + code + ")");
                        sendError(taskId, "token_expired");
                        failedTasks++;
                        return;
                    }

                    if (code == 6 || code == 29) {
                        addLog("⏳ VK: rate limit, пауза 5с");
                        Thread.sleep(5000);
                        respBody = vkHttpGet(apiUrl, ua);
                        respJson = new JSONObject(respBody);
                        if (respJson.has("error")) {
                            addLog("❌ VK: rate limit не отпустил");
                            sendError(taskId, "rate_limit");
                            failedTasks++;
                            return;
                        }
                    } else {
                        addLog("⚠️ VK " + code + " на filter=" + role + ", пропускаем");
                        continue;
                    }
                }

                JSONObject response = respJson.optJSONObject("response");
                if (response == null) continue;
                JSONArray items = response.optJSONArray("items");
                if (items == null) continue;

                for (int j = 0; j < items.length(); j++) {
                    JSONObject g = items.getJSONObject(j);
                    int gid = g.optInt("id", 0);
                    if (gid <= 0) continue;

                    String key = String.valueOf(gid);
                    JSONObject existing = byId.get(key);
                    if (existing != null) {
                        // admin > editor > moderator — оставляем более приоритетную роль
                        String curRole = existing.optString("role", "");
                        if (vkRoleRank(curRole) <= vkRoleRank(role)) continue;
                    }

                    JSONObject rec = new JSONObject();
                    rec.put("id", gid);
                    rec.put("name", g.optString("name", ""));
                    rec.put("screen_name", g.optString("screen_name", "club" + gid));
                    rec.put("photo_200", g.optString("photo_200", ""));
                    rec.put("description", g.optString("description", ""));
                    rec.put("members_count", g.optInt("members_count", 0));
                    rec.put("is_closed", g.optInt("is_closed", 0));
                    rec.put("role", role);
                    rec.put("admin_level", adminLevels[i]);

                    byId.put(key, rec);
                }
            }

            JSONArray groups = new JSONArray();
            for (JSONObject g : byId.values()) groups.put(g);

            addLog("📊 VK: собрано " + groups.length() + " управляемых групп");

            // Отправляем результат
            JSONObject result = new JSONObject();
            result.put("action", "submit_result");
            result.put("token", this.token);
            result.put("task_id", taskId);
            result.put("status_code", 200);
            result.put("parsed_json", groups.toString());

            String resp = makeRequest(SERVER_URL, result.toString());
            if (resp.contains("\"success\":true")) {
                addLog("✅ VK-результат доставлен");
                completedTasks++;
            } else {
                addLog("⚠️ VK-результат отклонён: " + truncate(resp, 100));
                failedTasks++;
            }

        } catch (Exception e) {
            addLog("❌ VK-ошибка: " + e.getMessage());
            sendError(taskId, e.getMessage() == null ? "exception" : e.getMessage());
            failedTasks++;
        }

        updateNotification("Подключено | Выполнено: " + completedTasks);
    }

    private int vkRoleRank(String role) {
        if ("admin".equals(role))     return 1;
        if ("editor".equals(role))    return 2;
        if ("moderator".equals(role)) return 3;
        return 4;
    }

    private String vkHttpGet(String urlStr, String ua) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        if (ua != null && !ua.isEmpty()) {
            conn.setRequestProperty("User-Agent", ua);
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);

        int code = conn.getResponseCode();
        BufferedReader reader;
        if (code >= 400) {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        }
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }
    
    private void executeTask(String taskId, String url, String method, 
                             Map<String, String> headers, String body) {
        totalTasks++;
        
        updateNotification("Выполняю: " + describeTask(url));
        
        try {
            URL requestUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) requestUrl.openConnection();
            conn.setRequestMethod(method);
            
            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            
            if (body != null && !body.isEmpty() && 
                (method.equals("POST") || method.equals("PUT"))) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            
            int responseCode = conn.getResponseCode();
            
            BufferedReader reader;
            if (responseCode >= 400) {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            }
            
            StringBuilder responseBody = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseBody.append(line);
            }
            reader.close();
            
            sendResult(taskId, responseCode, responseBody.toString());
            completedTasks++;
            
        } catch (Exception e) {
            addLog("❌ ОШИБКА: " + e.getMessage());
            sendError(taskId, e.getMessage());
            failedTasks++;
        }
        
        updateNotification("Подключено | Выполнено: " + completedTasks);
    }
    
    // ===== ОТПРАВКА РЕЗУЛЬТАТОВ =====
    
    private void sendResult(String taskId, int statusCode, String body) {
        try {
            String trimmedBody = body;
            if (trimmedBody.length() > 500000) {
                trimmedBody = trimmedBody.substring(0, 500000) + "...[обрезано]";
            }
            
            JSONObject json = new JSONObject();
            json.put("action", "submit_result");
            json.put("token", token);
            json.put("task_id", taskId);
            json.put("status_code", statusCode);
            json.put("body", trimmedBody);
            
            String response = makeRequest(SERVER_URL, json.toString());
            
            if (response.contains("\"success\":true")) {
                addLog("✅ Результат доставлен");
            } else {
                addLog("⚠️ Сервер: " + truncate(response, 100));
            }
            
        } catch (Exception e) {
            addLog("❌ Ошибка отправки: " + e.getMessage());
        }
    }
    
    private void sendError(String taskId, String error) {
        try {
            String escapedError = error.replace("\"", "\\\"").replace("\n", " ");
            
            makeRequest(SERVER_URL, 
                "{\"action\":\"submit_result\",\"token\":\"" + token + 
                "\",\"task_id\":\"" + taskId + 
                "\",\"error\":\"" + escapedError + "\"}");
            
            addLog("📤 Ошибка отправлена");
            
        } catch (Exception e) {
            addLog("❌ Ошибка отправки ошибки: " + e.getMessage());
        }
    }
    
    // ===== HTTP =====
    
    private String makeRequest(String urlString, String jsonBody) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        
        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.close();
        
        int responseCode = conn.getResponseCode();
        
        BufferedReader reader;
        if (responseCode >= 400) {
            reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
        } else {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        }
        
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        if (responseCode >= 400) {
            throw new Exception("HTTP " + responseCode + ": " + truncate(response.toString(), 200));
        }
        
        return response.toString();
    }
    
    // ===== JSON =====
    
    private String extractJsonField(String json, String field) {
        try {
            String search = "\"" + field + "\":\"";
            int start = json.indexOf(search);
            if (start >= 0) {
                start += search.length();
                int end = json.indexOf("\"", start);
                if (end > start) {
                    return json.substring(start, end);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
    
    // ===== ЛОГИ =====
    
    public void addLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        String newLine = "[" + timestamp + "] " + message + "\n";
        
        logBuilder.append(newLine);
        
        if (logBuilder.length() > 30000) {
            logBuilder.delete(0, logBuilder.length() - 30000);
        }
        
        android.util.Log.d("PhoneProxy", message);
        
        notifyLogChanged();
    }
    
    public String getLogs() {
        return logBuilder.toString();
    }
    
    public void clearLogs() {
        logBuilder = new StringBuilder();
        logBuilder.append("Лог очищен\n");
        notifyLogChanged();
    }
    
    private Runnable logChangeListener = null;
    
    public void setLogChangeListener(Runnable listener) {
        this.logChangeListener = listener;
    }
    
    private void notifyLogChanged() {
        if (logChangeListener != null) {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(logChangeListener);
        }
    }
    
    // ===== УВЕДОМЛЕНИЯ =====
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Phone Proxy",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Прокси работает в фоне");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private void updateNotification(String text) {
        Notification notification = createNotification(text);
        
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }
    
    private Notification createNotification() {
        return createNotification("Прокси работает");
    }
    
    private Notification createNotification(String text) {
        Intent notificationIntent = new Intent(this, PhoneProxy.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        Notification.Builder builder;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        
        return builder
            .setContentTitle("Свой.Лид")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(
                getResources(), R.mipmap.ic_launcher))
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build();
    }
    
    // ===== УТИЛИТЫ =====
    
    private String truncate(String text, int maxLength) {
        if (text == null) return "null";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...(" + text.length() + ")";
    }
    
    @Override
    public void onDestroy() {
        isRunning = false;
        releaseWakeLock();
        addLog("🔚 Service уничтожен");
        super.onDestroy();
    }
}
