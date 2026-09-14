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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;

public class PhoneProxy extends Activity {
    
    // ===== UI КОМПОНЕНТЫ =====
    private TextView statusText;
    private TextView logText;
    private TextView statsText;
    private ScrollView logScrollView;
    private Button startButton;
    private Button stopButton;
    
    // ===== СОСТОЯНИЕ =====
    private boolean isRunning = false;
    private String token = null;
    private int totalTasks = 0;
    private int completedTasks = 0;
    private int failedTasks = 0;
    
    // ===== КОНСТАНТЫ =====
    private static final String SERVER_URL = "https://svoyaigra.pro/api/proxy.php";
    private static final int POLL_INTERVAL = 5000; // 5 секунд
    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 30000;
    
    // ===== ИНИЦИАЛИЗАЦИЯ =====
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        // Останавливаем предыдущий Service если был
        Intent stopServiceIntent = new Intent(this, ProxyService.class);
        stopService(stopServiceIntent);
        
        // Инициализация UI
        statusText = (TextView) findViewById(R.id.statusText);
        logText = (TextView) findViewById(R.id.logText);
        statsText = (TextView) findViewById(R.id.statsText);
        logScrollView = (ScrollView) findViewById(R.id.logScrollView);
        
        startButton = (Button) findViewById(R.id.startButton);
        stopButton = (Button) findViewById(R.id.stopButton);
        Button clearLogButton = (Button) findViewById(R.id.clearLogButton);
        Button shareLogButton = (Button) findViewById(R.id.shareLogButton);
        
        // Обработчики кнопок
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startProxy();
            }
        });
        
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopProxy();
            }
        });
        
        clearLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearLog();
            }
        });
        
        shareLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLog();
            }
        });
        
        // Стартовое сообщение
        addLog("=================================");
        addLog("Phone Proxy v3.0 (Android 10+)");
        addLog("Сервер: " + SERVER_URL);
        addLog("Только HTTPS");
        addLog("=================================");
        addLog("");
    }
    
    // ===== УПРАВЛЕНИЕ =====
    
    private void startProxy() {
        isRunning = true;
        addLog("▶ ЗАПУСК ПРОКСИ");
        
        // Запуск foreground service для Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent serviceIntent = new Intent(this, ProxyService.class);
            startForegroundService(serviceIntent);
            addLog("🔧 Foreground сервис запущен");
        }
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                statusText.setText("🔄 Подключение...");
            }
        });
        
        // Регистрация в отдельном потоке
        new Thread(new Runnable() {
            @Override
            public void run() {
                register();
            }
        }).start();
    }
    
    private void stopProxy() {
        isRunning = false;
        addLog("⏹ ОСТАНОВКА ПРОКСИ");
        
        // Остановка сервиса
        Intent serviceIntent = new Intent(this, ProxyService.class);
        stopService(serviceIntent);
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                statusText.setText("⏹ Остановлено");
            }
        });
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
            addLog("   Ответ: " + truncate(response, 100));
            
            if (response.contains("\"token\"")) {
                // Убираем PHP warnings если есть
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
                    
                    // Парсинг device_id
                    String deviceId = extractJsonField(response, "device_id");
                    if (deviceId != null) {
                        addLog("🆔 ID устройства: " + deviceId);
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("✅ Подключено");
                        }
                    });
                    
                    addLog("🚀 Запуск цикла заданий (каждые " + (POLL_INTERVAL/1000) + " сек)");
                    
                    startTaskPolling();
                }
            } else {
                addLog("❌ Токен не найден в ответе!");
                updateStatusError("Ошибка регистрации");
            }
            
        } catch (Exception e) {
            addLog("❌ ОШИБКА РЕГИСТРАЦИИ: " + e.getMessage());
            addLog("   Тип: " + e.getClass().getSimpleName());
            updateStatusError("Ошибка: " + e.getMessage());
        }
    }
    
    // ===== ЦИКЛ ПОЛУЧЕНИЯ ЗАДАНИЙ =====
    
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
                            // Заданий нет - логируем редко
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
            // Убираем PHP warnings
            if (response.contains("<br />")) {
                int jsonStart = response.indexOf("{\"success\"");
                if (jsonStart >= 0) {
                    response = response.substring(jsonStart);
                }
            }
            
            addLog("📋 Сырой ответ: " + truncate(response, 300));
            
            String[] parts = response.split("\\{\"id\":\"");
            
            for (int i = 1; i < parts.length; i++) {
                if (!isRunning) break;
                
                String part = parts[i];
                
                // ПАРСИНГ TASK ID (исправлено!)
                String taskId = null;
                int idEnd = part.indexOf("\"");
                if (idEnd > 0) {
                    taskId = part.substring(0, idEnd);
                }
                
                // Если не нашли - пробуем другой способ
                if (taskId == null || taskId.equals("null")) {
                    int taskIdStart = part.indexOf("\"id\":") ;
                    if (taskIdStart >= 0) {
                        taskIdStart += 5;
                        int taskIdEnd = part.indexOf(",", taskIdStart);
                        if (taskIdEnd > taskIdStart) {
                            taskId = part.substring(taskIdStart, taskIdEnd)
                                        .replace("\"", "").trim();
                        }
                    }
                }
                
                // Парсинг URL
                String url = extractJsonField(part, "url");
                String method = extractJsonField(part, "method");
                
                if (url != null && taskId != null) {
                    // Убираем экранирование
                    url = url.replace("\\/", "/")
                             .replace("\\\"", "\"");
                    
                    if (method == null || method.isEmpty()) {
                        method = "GET";
                    }
                    
                    addLog("📤 Задание [" + i + "]: ID=" + taskId);
                    addLog("   URL: " + url);
                    addLog("   Метод: " + method);
                    
                    executeTask(taskId, url, method, null, null);
                } else {
                    addLog("❌ Не удалось распарсить задание " + i);
                    addLog("   Part: " + truncate(part, 100));
                }
            }
            
        } catch (Exception e) {
            addLog("❌ ОШИБКА парсинга: " + e.getMessage());
        }
    }
    

    
    private void executeTask(final String taskId, final String url, final String method, 
                             final Map<String, String> headers, final String body) {
        totalTasks++;
        updateStats();
        
        updateStatusTask("Выполняю: " + truncate(url, 40));
        
        try {
            addLog("🌐 Начинаю: " + method + " " + url);
            
            long startTime = System.currentTimeMillis();
            
            URL requestUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) requestUrl.openConnection();
            
            // Метод
            conn.setRequestMethod(method);
            
            // ТОЛЬКО заголовки из задания (никаких своих!)
            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                    addLog("   Header: " + entry.getKey() + ": " + truncate(entry.getValue(), 50));
                }
            }
            
            // Тело запроса для POST/PUT/PATCH
            if (body != null && !body.isEmpty() && 
                (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
                
                addLog("   Тело: " + truncate(body, 100));
            }
            
            // Таймауты
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            
            int responseCode = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - startTime;
            
            addLog("📊 Код: " + responseCode + " (" + elapsed + "мс)");
            
            // Чтение ответа
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
            
            String bodyStr = responseBody.toString();
            addLog("📄 Ответ: " + bodyStr.length() + " символов");
            addLog("   Начало: " + truncate(bodyStr, 150));
            
            // Отправка результата
            addLog("📤 Отправляю результат...");
            sendResult(taskId, responseCode, bodyStr);
            
            completedTasks++;
            updateStats();
            
            updateStatusTask("✅ Готово: " + truncate(url, 40));
            
        } catch (Exception e) {
            addLog("❌ ОШИБКА: " + e.getMessage());
            addLog("   Тип: " + e.getClass().getSimpleName());
            
            sendError(taskId, e.getMessage());
            
            failedTasks++;
            updateStats();
        }
    }
    
    // ===== ОТПРАВКА РЕЗУЛЬТАТОВ =====
    
    private void sendResult(String taskId, int statusCode, String body) {
        try {
            // Экранирование
            String escapedBody = body.replace("\\", "\\\\")
                                     .replace("\"", "\\\"")
                                     .replace("\n", "\\n")
                                     .replace("\r", "")
                                     .replace("\t", "\\t");
            
            // Ограничение размера
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
            
            String response = makeRequest(SERVER_URL, 
                "{\"action\":\"submit_result\",\"token\":\"" + token + 
                "\",\"task_id\":\"" + taskId + 
                "\",\"error\":\"" + escapedError + "\"}");
            
            addLog("📤 Ошибка отправлена");
            
        } catch (Exception e) {
            addLog("❌ Ошибка отправки ошибки: " + e.getMessage());
        }
    }
    
    // ===== HTTP ЗАПРОСЫ =====
    
    private String makeRequest(String urlString, String jsonBody) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        
        // Отправка тела
        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.close();
        
        // Ответ
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
    
    // ===== JSON УТИЛИТЫ =====
    
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
    
    private Map<String, String> parseHeaders(String headersJson) {
        Map<String, String> headers = new HashMap<>();
        if (headersJson == null || headersJson.isEmpty() || headersJson.equals("null")) {
            return headers;
        }
        
        try {
            // Убираем фигурные скобки
            String clean = headersJson.replace("{", "").replace("}", "");
            
            if (clean.isEmpty()) return headers;
            
            // Разбиваем на пары
            String[] pairs = clean.split(",");
            for (String pair : pairs) {
                if (pair.contains(":")) {
                    int colonIdx = pair.indexOf(":");
                    String key = pair.substring(0, colonIdx).trim().replace("\"", "");
                    String value = pair.substring(colonIdx + 1).trim().replace("\"", "");
                    
                    if (!key.isEmpty() && !value.isEmpty()) {
                        headers.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            addLog("⚠️ Ошибка парсинга заголовков: " + e.getMessage());
        }
        return headers;
    }
    
    // ===== UI МЕТОДЫ =====
    
    private void addLog(final String message) {
        final String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    String currentText = logText.getText().toString();
                    String newLine = "[" + timestamp + "] " + message + "\n";
                    
                    String newText = currentText + newLine;
                    
                    // Ограничение размера лога
                    if (newText.length() > 30000) {
                        newText = newText.substring(newText.length() - 30000);
                    }
                    
                    logText.setText(newText);
                    
                    // Автоскролл
                    logScrollView.post(new Runnable() {
                        @Override
                        public void run() {
                            logScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                        }
                    });
                    
                } catch (Exception e) {
                    // Ошибка UI - игнорируем
                }
            }
        });
        
        // Системный лог
        android.util.Log.d("PhoneProxy", message);
    }
    
    private void clearLog() {
        logText.setText("Лог очищен\n");
        addLog("🗑 Лог очищен");
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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statsText.setText("Заданий: " + totalTasks + 
                    " | Успешно: " + completedTasks + 
                    " | Ошибок: " + failedTasks);
            }
        });
    }
    
    private void updateStatusError(final String error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText("❌ " + error);
            }
        });
    }
    
    private void updateStatusTask(final String task) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText("🔄 " + task);
            }
        });
    }
    
    // ===== УТИЛИТЫ =====
    
    private String truncate(String text, int maxLength) {
        if (text == null) return "null";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...(" + text.length() + ")";
    }
    
    // ===== ЖИЗНЕННЫЙ ЦИКЛ =====
    
    @Override
    public void onBackPressed() {
        // При нажатии НАЗАД — сворачиваем в фон, НЕ закрываем
        moveTaskToBack(true);
        addLog("📱 Свернуто (прокси работает в фоне)");
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // При сворачивании — продолжаем работать
        addLog("📱 Приложение свёрнуто (работа продолжается)");
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        // Ничего НЕ делаем — Foreground Service работает
        android.util.Log.d("PhoneProxy", "App stopped, service continues");
    }
    
    @Override
    protected void onDestroy() {
        // НЕ останавливаем — пусть Service работает дальше
        // Даже если Activity уничтожено, прокси продолжает работать
        
        android.util.Log.d("PhoneProxy", "Activity destroyed, service continues");
        
        // isRunning НЕ сбрасываем — сервис работает
        
        super.onDestroy();
    }
}
