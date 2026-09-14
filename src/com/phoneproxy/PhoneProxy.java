package com.phoneproxy;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import android.view.View;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class PhoneProxy extends Activity {
    
    private TextView statusText;
    private Button startButton;
    private Button stopButton;
    private boolean isRunning = false;
    private String token = null;
    private Handler handler = new Handler();
    
    private static final String SERVER_URL = "https://svoyaigra.pro/api/proxy.php";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        statusText = (TextView) findViewById(R.id.statusText);
        startButton = (Button) findViewById(R.id.startButton);
        stopButton = (Button) findViewById(R.id.stopButton);
        
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
    }
    
    private void startProxy() {
        isRunning = true;
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                statusText.setText("Подключение...");
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
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                statusText.setText("Остановлено");
            }
        });
    }
    
    private void register() {
        try {
            String response = makeRequest(SERVER_URL, 
                "{\"action\":\"register\",\"device_name\":\"Android Phone\"}");
            
            if (response.contains("\"token\"")) {
                // Простой парсинг токена
                int start = response.indexOf("\"token\":\"") + 9;
                int end = response.indexOf("\"", start);
                
                if (start > 9 && end > start) {
                    token = response.substring(start, end);
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("Подключено!");
                        }
                    });
                    
                    // Запуск цикла получения заданий
                    getTasksLoop();
                }
            }
        } catch (Exception e) {
            final String error = e.getMessage();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    statusText.setText("Ошибка: " + error);
                }
            });
        }
    }
    
    private void getTasksLoop() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning && token != null) {
                    try {
                        String taskResponse = makeRequest(SERVER_URL, 
                            "{\"action\":\"get_tasks\",\"token\":\"" + token + "\"}");
                        
                        if (taskResponse.contains("\"tasks\"") && !taskResponse.contains("[]")) {
                            // Есть задания
                            executeTasks(taskResponse);
                        }
                        
                        Thread.sleep(5000); // 5 секунд
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        // Ошибка сети
                    }
                }
            }
        }).start();
    }
    
    private void executeTasks(String response) {
        // Простой парсинг заданий
        // В реальном проекте используй JSON библиотеку
        String[] parts = response.split("\\{\"id\":\"");
        
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            
            int idEnd = part.indexOf("\"");
            String taskId = part.substring(0, idEnd);
            
            int urlStart = part.indexOf("\"url\":\"") + 7;
            int urlEnd = part.indexOf("\"", urlStart);
            
            if (urlStart > 7 && urlEnd > urlStart) {
                String url = part.substring(urlStart, urlEnd);
                executeTask(taskId, url);
            }
        }
    }
    
    private void executeTask(final String taskId, final String url) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText("Выполняю: " + url);
            }
        });
        
        try {
            // Выполнение GET запроса
            URL requestUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) requestUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            
            int responseCode = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            reader.close();
            
            // Отправка результата
            sendResult(taskId, responseCode, body.toString());
            
        } catch (Exception e) {
            sendError(taskId, e.getMessage());
        }
    }
    
    private void sendResult(String taskId, int statusCode, String body) {
        try {
            String escapedBody = body.replace("\\", "\\\\")
                                     .replace("\"", "\\\"")
                                     .replace("\n", "\\n")
                                     .replace("\r", "");
            
            makeRequest(SERVER_URL, 
                "{\"action\":\"submit_result\",\"token\":\"" + token + 
                "\",\"task_id\":\"" + taskId + 
                "\",\"status_code\":" + statusCode + 
                ",\"body\":\"" + escapedBody + "\"}");
        } catch (Exception e) {
            // Ошибка отправки
        }
    }
    
    private void sendError(String taskId, String error) {
        try {
            makeRequest(SERVER_URL, 
                "{\"action\":\"submit_result\",\"token\":\"" + token + 
                "\",\"task_id\":\"" + taskId + 
                "\",\"error\":\"" + error + "\"}");
        } catch (Exception e) {
            // Ошибка отправки
        }
    }
    
    private String makeRequest(String urlString, String jsonBody) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        
        // Отправка тела запроса
        OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.close();
        
        // Чтение ответа
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        return response.toString();
    }
}
