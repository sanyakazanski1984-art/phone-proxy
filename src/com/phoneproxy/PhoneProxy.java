package com.phoneproxy;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.os.StrictMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;

public class PhoneProxy extends Activity {
    
    // UI
    private TextView statusText;
    private TextView logText;
    private TextView statsText;
    private ScrollView logScrollView;
    private Button startButton;
    private Button stopButton;
    
    // Состояние
    private boolean isRunning = false;
    private String token = null;
    private int totalTasks = 0;
    private int completedTasks = 0;
    private int failedTasks = 0;
    
    private static final String SERVER_URL = "https://svoyaigra.pro/api/proxy.php";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        // Для старых версий
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        
        // UI
        statusText = (TextView) findViewById(R.id.statusText);
        logText = (TextView) findViewById(R.id.logText);
        statsText = (TextView) findViewById(R.id.statsText);
        logScrollView = (ScrollView) findViewById(R.id.logScrollView);
        
        startButton = (Button) findViewById(R.id.startButton);
        stopButton = (Button) findViewById(R.id.stopButton);
        Button clearLogButton = (Button) findViewById(R.id.clearLogButton);
        Button shareLogButton = (Button) findViewById(R.id.shareLogButton);
        
        // Обработчики
        startButton.setOnClickListener(v -> startProxy());
        stopButton.setOnClickListener(v -> stopProxy());
        clearLogButton.setOnClickListener(v -> clearLog());
        shareLogButton.setOnClickListener(v -> shareLog());
        
        addLog("=================================");
        addLog("Phone Proxy v3.0 (Android 10+)");
        addLog("Сервер: " + SERVER_URL);
        addLog("=================================");
    }
    
    private void startProxy() {
        isRunning = true;
        addLog("▶ ЗАПУСК");
        
        // Запуск foreground service для Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent serviceIntent = new Intent(this, ProxyService.class);
            startForegroundService(serviceIntent);
        }
        
        runOnUiThread(() -> {
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            statusText.setText("🔄 Подключение...");
        });
        
        new Thread(this::register).start();
    }
    
    private void stopProxy() {
        isRunning = false;
        addLog("⏹ ОСТАНОВКА");
        
        // Остановка сервиса
        Intent serviceIntent = new Intent(this, ProxyService.class);
        stopService(serviceIntent);
        
        runOnUiThread(() -> {
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            statusText.setText("⏹ Остановлено");
        });
    }
    
    // ... остальные методы как в v2 ...
    
    private void register() {
        try {
            addLog("📡 Регистрация...");
            
            String response = makeRequest(SERVER_URL, 
                "{\"action\":\"register\",\"device_name\":\"Android Phone\"}");
            
            if (response.contains("\"token\"")) {
                int start = response.indexOf("\"token\":\"") + 9;
                int end = response.indexOf("\"", start);
                
                if (start > 9 && end > start) {
                    token = response.substring(start, end);
                    addLog("🔑 Токен получен");
                    
                    runOnUiThread(() -> statusText.setText("✅ Подключено"));
                    
                    startTaskPolling();
                }
            }
        } catch (Exception e) {
            addLog("❌ Ошибка регистрации: " + e.getMessage());
        }
    }
    
    private void startTaskPolling() {
        new Thread(() -> {
            while (isRunning && token != null) {
                try {
                    String response = makeRequest(SERVER_URL, 
                        "{\"action\":\"get_tasks\",\"token\":\"" + token + "\"}");
                    
                    if (response.contains("\"tasks\"") && !response.contains("[]")) {
                        executeTasks(response);
                    }
                    
                    Thread.sleep(5000);
                    
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    addLog("❌ Ошибка опроса: " + e.getMessage());
                    try { Thread.sleep(10000); } catch (InterruptedException ie) { break; }
                }
            }
        }).start();
    }
    
    private void executeTasks(String response) {
        try {
            // Убираем PHP warnings если есть
            if (response.contains("<br />")) {
                int jsonStart = response.indexOf('{' );
                if (jsonStart > 0) {
                    response = response.substring(jsonStart);
                }
            }
            
            String[] parts = response.split("\\{\"id\":\"");
            
            for (int i = 1; i < parts.length; i++) {
                if (!isRunning) break;
                
                String part = parts[i];
                
                // Парсинг всех полей
                String taskId = extractJsonField(part, "id");
                String url = extractJsonField(part, "url");
                String method = extractJsonField(part, "method");
                String headersJson = extractJsonField(part, "headers");
                String body = extractJsonField(part, "body");
                
                if (url != null) {
                    // Убираем экранирование
                    url = url.replace("\\/", "/")
                             .replace("\\\"", "\"");
                    
                    // Парсим заголовки
                    Map<String, String> headers = parseHeaders(headersJson);
                    
                    addLog("📤 Задание: " + taskId);
                    addLog("   URL: " + url);
                    addLog("   Метод: " + (method != null ? method : "GET"));
                    
                    executeTask(taskId, url, method != null ? method : "GET", 
                               headers, body);
                }
            }
            
        } catch (Exception e) {
            addLog("❌ Ошибка парсинга: " + e.getMessage());
        }
    }
    
    // Парсинг JSON поля
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
    
    // Парсинг заголовков из JSON
    private Map<String, String> parseHeaders(String headersJson) {
        Map<String, String> headers = new HashMap<>();
        if (headersJson == null) return headers;
        
        try {
            // Убираем фигурные скобки
            String clean = headersJson.replace("{", "").replace("}", "");
            
            // Разбиваем на пары
            String[] pairs = clean.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim().replace("\"", "");
                    headers.put(key, value);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return headers;
    }
    
    // ... остальные методы ...
    
    private String makeRequest(String urlString, String jsonBody) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        
        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.close();
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        return response.toString();
    }
    
    private void addLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        
        runOnUiThread(() -> {
            try {
                String currentText = logText.getText().toString();
                String newLine = "[" + timestamp + "] " + message + "\n";
                String newText = currentText + newLine;
                
                if (newText.length() > 30000) {
                    newText = newText.substring(newText.length() - 30000);
                }
                
                logText.setText(newText);
                logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
                
            } catch (Exception e) {
                // ignore
            }
        });
    }
    
    private void clearLog() {
        logText.setText("Лог очищен\n");
    }
    
    private void shareLog() {
        String logContent = logText.getText().toString();
        
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Phone Proxy Log");
        shareIntent.putExtra(Intent.EXTRA_TEXT, logContent);
        
        startActivity(Intent.createChooser(shareIntent, "Отправить лог:"));
    }
    
    private void updateStats() {
        runOnUiThread(() -> {
            statsText.setText("Заданий: " + totalTasks + 
                " | Успешно: " + completedTasks + 
                " | Ошибок: " + failedTasks);
        });
    }
    
    private String truncate(String text, int maxLength) {
        if (text == null) return "null";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...(" + text.length() + ")";
    }
}
