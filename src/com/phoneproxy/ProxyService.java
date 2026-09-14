package com.phoneproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;

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
    
    // Binder для связи с Activity
    private final LocalBinder binder = new LocalBinder();
    
    public class LocalBinder extends Binder {
        // ИСПРАВЛЕНО: добавлен public, чтобы getService() был доступен
        // из Activity, даже если она окажется в другом пакете
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
        // ИСПРАВЛЕНО: защита от повторного запуска.
        // Без этой проверки двойной тап по кнопке (или повторный
        // onStartCommand) запускал бы второй register-thread и,
        // как следствие, второй цикл startTaskPolling() — каждая
        // задача выполнялась бы дважды.
        if (isRunning) return;
        
        isRunning = true;
        addLog("▶ ЗАПУСК ПРОКСИ");
        
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
        
        updateNotification("Остановлено");
    }
    
    // ===== РЕГИСТРАЦИЯ =====
    
    private void register() {
        try {
            addLog("📡 Регистрация на сервере...");
            
            long startTime = System.currentTimeMillis();
            
            String response = makeRequest(SERVER_URL, 
                "{\"action\":\"register\",\"device_name\":\"Android Phone\"}");
            
            long elapsed = System.currentTimeMillis() - startTime;
            
            addLog("✅ Ответ за " + elapsed + "мс");
            
            if (response.contains("\"token\"")) {
                // Убираем PHP warnings
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
                        
                        // Убираем PHP warnings
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
                
                // Парсим заголовки
                Map<String, String> headers = new HashMap<>();
                if (task.has("headers") && !task.isNull("headers")) {
                    JSONObject headersJson = task.getJSONObject("headers");
                    JSONArray keys = headersJson.names();
                    if (keys != null) {
                        for (int j = 0; j < keys.length(); j++) {
                            String key = keys.getString(j);
                            String value = headersJson.getString(key);
                            headers.put(key, value);
                        }
                    }
                }
                
                if (url != null && taskId != null) {
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
    
    private void executeTask(String taskId, String url, String method, 
                             Map<String, String> headers, String body) {
        totalTasks++;
        
        updateNotification("Выполняю: " + truncate(url, 30));
        
        try {
            URL requestUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) requestUrl.openConnection();
            conn.setRequestMethod(method);
            
            // ПРИМЕНЯЕМ ЗАГОЛОВКИ СЕРВЕРА
            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            
            // ОТПРАВЛЯЕМ ТЕЛО ЗАПРОСА
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
            String escapedBody = body.replace("\\", "\\\\")
                                     .replace("\"", "\\\"")
                                     .replace("\n", "\\n")
                                     .replace("\r", "")
                                     .replace("\t", "\\t");
            
            if (escapedBody.length() > 50000) {
                escapedBody = escapedBody.substring(0, 50000) + "...[обрезано]";
            }
            
            String response = makeRequest(SERVER_URL, 
                "{\"action\":\"submit_result\",\"token\":\"" + token + 
                "\",\"task_id\":\"" + taskId + 
                "\",\"status_code\":" + statusCode + 
                ",\"body\":\"" + escapedBody + "\"}");
            
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
        
        // Ограничение размера
        if (logBuilder.length() > 30000) {
            logBuilder.delete(0, logBuilder.length() - 30000);
        }
        
        android.util.Log.d("PhoneProxy", message);
        
        // Уведомляем Activity если подключено
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
    
    // Callback для Activity
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
        // ВАЖНО: PendingIntent для открытия Activity при клике!
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
            .setContentTitle("Phone Proxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)  // КЛИК ОТКРЫВАЕТ ПРИЛОЖЕНИЕ!
            .setAutoCancel(false)
            .setOngoing(true)  // Не закрывается свайпом
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
        addLog("🔚 Service уничтожен");
        super.onDestroy();
    }
}
