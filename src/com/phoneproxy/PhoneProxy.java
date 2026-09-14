package com.phoneproxy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
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

public class PhoneProxy extends Activity {
    
    // UI компоненты
    private TextView statusText;
    private TextView logText;
    private TextView statsText;
    private ScrollView logScrollView;
    private Button startButton;
    private Button stopButton;
    private Button clearLogButton;
    private Button shareLogButton;
    
    // Состояние
    private boolean isRunning = false;
    private String token = null;
    private int totalTasks = 0;
    private int completedTasks = 0;
    private int failedTasks = 0;
    
    // Счётчик логов
    private int logCount = 0;
    
    private static final String SERVER_URL = "https://svoyaigra.pro/api/proxy.php";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        // Разрешаем сетевые операции
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        
        // Инициализация UI
        statusText = (TextView) findViewById(R.id.statusText);
        logText = (TextView) findViewById(R.id.logText);
        statsText = (TextView) findViewById(R.id.statsText);
        logScrollView = (ScrollView) findViewById(R.id.logScrollView);
        
        startButton = (Button) findViewById(R.id.startButton);
        stopButton = (Button) findViewById(R.id.stopButton);
        clearLogButton = (Button) findViewById(R.id.clearLogButton);
        shareLogButton = (Button) findViewById(R.id.shareLogButton);
        
        // Обработчики
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
        addLog("Phone Proxy v2.0 запущен");
        addLog("Сервер: " + SERVER_URL);
        addLog("=================================");
        addLog("");
    }
    
    // ===== УПРАВЛЕНИЕ =====
    
    private void startProxy() {
        isRunning = true;
        
        addLog("▶ ЗАПУСК ПРОКСИ");
        
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
        addLog("📡 Регистрация на сервере...");
        addLog("   Отправляю: action=register");
        
        try {
            long startTime = System.currentTimeMillis();
            
            String response = makeRequest(SERVER_URL, 
                "{\"action\":\"register\",\"device_name\":\"Android Phone\"}");
            
            long elapsed = System.currentTimeMillis() - startTime;
            
            addLog("✅ Ответ получен за " + elapsed + "мс");
            addLog("   Ответ: " + truncate(response, 100));
            
            if (response.contains("\"token\"")) {
                // Парсинг токена
                int start = response.indexOf("\"token\":\"") + 9;
                int end = response.indexOf("\"", start);
                
                if (start > 9 && end > start) {
                    token = response.substring(start, end);
                    
                    addLog("🔑 Токен получен: " + token.substring(0, 16) + "...");
                    
                    // Парсинг device_id
                    if (response.contains("\"device_id\"")) {
                        int devStart = response.indexOf("\"device_id\":\"") + 13;
                        int devEnd = response.indexOf("\"", devStart);
                        if (devStart > 13 && devEnd > devStart) {
                            String deviceId = response.substring(devStart, devEnd);
                            addLog("🆔 ID устройства: " + deviceId);
                        }
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("✅ Подключено");
                        }
                    });
                    
                    addLog("🚀 Запуск цикла получения заданий (каждые 5 сек)");
                    
                    // Запуск цикла
                    startTaskPolling();
                }
            } else {
                addLog("❌ Ошибка: токен не найден в ответе!");
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
                        addLog("🔄 Опрос #" + pollCount + "...");
                        
                        long startTime = System.currentTimeMillis();
                        
                        String response = makeRequest(SERVER_URL, 
                            "{\"action\":\"get_tasks\",\"token\":\"" + token + "\"}");
                        
                        long elapsed = System.currentTimeMillis() - startTime;
                        
                        if (response.contains("\"tasks\":[]") || response.contains("\"tasks\": []")) {
                            // Заданий нет
                            // Не логируем каждый раз (иначе лог разрастётся)
                            if (pollCount % 12 == 1) { // Каждую минуту
                                addLog("⏳ Заданий нет (опрос #" + pollCount + ", " + elapsed + "мс)");
                            }
                        } else if (response.contains("\"tasks\"")) {
                            addLog("📋 ПОЛУЧЕНЫ ЗАДАНИЯ! (" + elapsed + "мс)");
                            addLog("   Ответ: " + truncate(response, 200));
                            
                            executeTasks(response);
                        } else if (response.contains("\"error\"")) {
                            addLog("❌ Ошибка API: " + truncate(response, 100));
                            
                            // Токен мог стать невалидным
                            if (response.contains("Invalid token")) {
                                addLog("🔑 Токен невалиден! Перезегистрация...");
                                token = null;
                                register();
                                break;
                            }
                        }
                        
                        Thread.sleep(5000); // 5 секунд
                        
                    } catch (InterruptedException e) {
                        addLog("⏹ Цикл остановлен");
                        break;
                    } catch (Exception e) {
                        addLog("❌ Ошибка опроса: " + e.getMessage());
                        addLog("   Тип: " + e.getClass().getSimpleName());
                        
                        try {
                            Thread.sleep(10000); // 10 сек при ошибке
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
                
                addLog("🔚 Цикл получения заданий завершён");
            }
        }).start();
    }
    
    // ===== ВЫПОЛНЕНИЕ ЗАДАНИЙ =====
    
    private void executeTasks(String response) {
        try {
            String[] parts = response.split("\\{\"id\":\"");
            
            int taskCount = parts.length - 1;
            addLog("📦 Заданий к выполнению: " + taskCount);
            
            for (int i = 1; i < parts.length; i++) {
                if (!isRunning) break;
                
                String part = parts[i];
                
                // Парсинг ID
                int idEnd = part.indexOf("\"");
                String taskId = part.substring(0, idEnd);
                
                // Парсинг URL
                int urlStart = part.indexOf("\"url\":\"") + 7;
                int urlEnd = part.indexOf("\"", urlStart);
                
                // Парсинг метода
                String method = "GET";
                if (part.contains("\"method\":\"")) {
                    int mStart = part.indexOf("\"method\":\"") + 10;
                    int mEnd = part.indexOf("\"", mStart);
                    if (mStart > 10 && mEnd > mStart) {
                        method = part.substring(mStart, mEnd);
                    }
                }
                
                if (urlStart > 7 && urlEnd > urlStart) {
                    String url = part.substring(urlStart, urlEnd);
                    
                    addLog("📤 Задание [" + (i) + "/" + taskCount + "]: " + taskId);
                    addLog("   URL: " + url);
                    addLog("   Метод: " + method);
                    
                    executeTask(taskId, url, method);
                } else {
                    addLog("❌ Ошибка парсинга задания " + i);
                }
            }
            
        } catch (Exception e) {
            addLog("❌ ОШИБКА парсинга заданий: " + e.getMessage());
        }
    }
    
    private void executeTask(final String taskId, final String url, final String method) {
        totalTasks++;
        updateStats();
        
        updateStatusTask("Выполняю: " + truncate(url, 40));
        
        try {
            addLog("🌐 Начинаю запрос к: " + url);
            
            long startTime = System.currentTimeMillis();
            
            URL requestUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) requestUrl.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            
            // User-Agent
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) PhoneProxy/2.0");
            
            int responseCode = conn.getResponseCode();
            
            long elapsed = System.currentTimeMillis() - startTime;
            
            addLog("📊 Код ответа: " + responseCode + " (" + elapsed + "мс)");
            
            // Чтение ответа
            BufferedReader reader;
            if (responseCode >= 400) {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            }
            
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            reader.close();
            
            String bodyStr = body.toString();
            addLog("📄 Размер ответа: " + bodyStr.length() + " символов");
            addLog("   Начало: " + truncate(bodyStr, 150));
            
            // Отправка результата
            addLog("📤 Отправляю результат на сервер...");
            sendResult(taskId, responseCode, bodyStr);
            
            completedTasks++;
            updateStats();
            
            updateStatusTask("✅ Готово: " + truncate(url, 40));
            
        } catch (Exception e) {
            addLog("❌ ОШИБКА выполнения: " + e.getMessage());
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
            
            // Ограничиваем размер
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
                addLog("⚠️ Сервер ответил: " + truncate(response, 100));
            }
            
        } catch (Exception e) {
            addLog("❌ Ошибка отправки результата: " + e.getMessage());
        }
    }
    
    private void sendError(String taskId, String error) {
        try {
            String escapedError = error.replace("\"", "\\\"").replace("\n", " ");
            
            String response = makeRequest(SERVER_URL, 
                "{\"action\":\"submit_result\",\"token\":\"" + token + 
                "\",\"task_id\":\"" + taskId + 
                "\",\"error\":\"" + escapedError + "\"}");
            
            addLog("📤 Ошибка отправлена на сервер");
            
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
        conn.setRequestProperty("User-Agent", "PhoneProxy/2.0 Android");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        
        // Отправка
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
    
    // ===== UI МЕТОДЫ =====
    
    private void addLog(final String message) {
        final String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    logCount++;
                    
                    String currentText = logText.getText().toString();
                    String newLine = "[" + timestamp + "] " + message + "\n";
                    
                    // Ограничение размера лога (макс 30KB)
                    String newText = currentText + newLine;
                    if (newText.length() > 30000) {
                        newText = newText.substring(newText.length() - 30000);
                    }
                    
                    logText.setText(newText);
                    
                    // Автоскролл вниз
                    logScrollView.post(new Runnable() {
                        @Override
                        public void run() {
                            logScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                        }
                    });
                    
                } catch (Exception e) {
                    // Ошибка UI
                }
            }
        });
        
        // Также пишем в системный лог
        android.util.Log.d("PhoneProxy", message);
    }
    
    private void clearLog() {
        logText.setText("Лог очищен\n");
        logCount = 0;
        addLog("🗑 Лог очищен пользователем");
    }
    
    private void shareLog() {
        String logContent = logText.getText().toString();
        
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Phone Proxy Log");
        shareIntent.putExtra(Intent.EXTRA_TEXT, logContent);
        
        startActivity(Intent.createChooser(shareIntent, "Отправить лог через:"));
    }
    
    private void updateStats() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statsText.setText("Заданий: " + totalTasks + " | Успешно: " + completedTasks + " | Ошибок: " + failedTasks);
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
    protected void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }
    
    @Override
    protected void onPause() {
        // Продолжаем работу в фоне
        super.onPause();
    }
}
