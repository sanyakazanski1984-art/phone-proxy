package com.phoneproxy;

import com.codename1.ui.Form;
import com.codename1.ui.Label;
import com.codename1.ui.Button;
import com.codename1.ui.layouts.BoxLayout;
import com.codename1.ui.Display;
import com.codename1.ui.events.ActionEvent;
import com.codename1.ui.events.ActionListener;
import com.codename1.io.ConnectionRequest;
import com.codename1.io.NetworkManager;
import com.codename1.io.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

public class PhoneProxy {
    
    private static final String SERVER_URL = "https://svoyaigra.pro/api/proxy.php";
    
    private Form mainForm;
    private Label statusLabel;
    private Label statsLabel;
    private Button startButton;
    private Button stopButton;
    
    private boolean isRunning = false;
    private String token = null;
    private int completedCount = 0;
    private int errorCount = 0;
    
    public void init(Object context) {
        Log.p("Phone Proxy: init");
    }
    
    public void start() {
        if (mainForm != null) {
            mainForm.show();
            return;
        }
        createUI();
        mainForm.show();
    }
    
    private void createUI() {
        mainForm = new Form("Phone Proxy", new BoxLayout(BoxLayout.Y_AXIS));
        
        Label titleLabel = new Label("📱 Phone Proxy");
        mainForm.add(titleLabel);
        
        statusLabel = new Label("Не подключено");
        mainForm.add(statusLabel);
        
        startButton = new Button("🚀 Запустить");
        startButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                startProxy();
            }
        });
        mainForm.add(startButton);
        
        stopButton = new Button("⏹ Остановить");
        stopButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                stopProxy();
            }
        });
        stopButton.setVisible(false);
        mainForm.add(stopButton);
        
        statsLabel = new Label("Выполнено: 0\nОшибок: 0");
        mainForm.add(statsLabel);
    }
    
    private void startProxy() {
        isRunning = true;
        
        Display.getInstance().callSerially(new Runnable() {
            @Override
            public void run() {
                startButton.setVisible(false);
                stopButton.setVisible(true);
                statusLabel.setText("Подключение...");
                mainForm.revalidate();
            }
        });
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                register();
            }
        }).start();
    }
    
    private void stopProxy() {
        isRunning = false;
        
        Display.getInstance().callSerially(new Runnable() {
            @Override
            public void run() {
                startButton.setVisible(true);
                stopButton.setVisible(false);
                statusLabel.setText("Остановлено");
                mainForm.revalidate();
            }
        });
    }
    
    private void register() {
        try {
            ConnectionRequest request = new ConnectionRequest() {
                @Override
                protected void readResponse(InputStream input) throws IOException {
                    String response = readInputStream(input);
                    
                    if (response.contains("\"token\"")) {
                        int tokenStart = response.indexOf("\"token\":\"") + 9;
                        int tokenEnd = response.indexOf("\"", tokenStart);
                        
                        if (tokenStart > 9 && tokenEnd > tokenStart) {
                            final String newToken = response.substring(tokenStart, tokenEnd);
                            
                            Display.getInstance().callSerially(new Runnable() {
                                @Override
                                public void run() {
                                    token = newToken;
                                    statusLabel.setText("Подключено!");
                                    
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            startPolling();
                                        }
                                    }).start();
                                }
                            });
                        }
                    }
                }
                
                @Override
                protected void handleException(Exception err) {
                    Display.getInstance().callSerially(new Runnable() {
                        @Override
                        public void run() {
                            statusLabel.setText("Ошибка: " + err.getMessage());
                        }
                    });
                }
            };
            
            request.setUrl(SERVER_URL);
            request.setHttpMethod("POST");
            request.setContentType("application/json");
            request.setRequestBody("{\"action\":\"register\",\"device_name\":\"Android Phone\"}");
            
            NetworkManager.getInstance().addToQueue(request);
            
        } catch (Exception e) {
            Log.p("Phone Proxy: Error: " + e.getMessage());
        }
    }
    
    private void startPolling() {
        while (isRunning && token != null) {
            try {
                getTasks();
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    private void getTasks() {
        if (token == null) return;
        
        ConnectionRequest request = new ConnectionRequest() {
            @Override
            protected void readResponse(InputStream input) throws IOException {
                String response = readInputStream(input);
                
                if (response.contains("\"tasks\"") && !response.contains("[]")) {
                    processTasks(response);
                }
            }
        };
        
        request.setUrl(SERVER_URL);
        request.setHttpMethod("POST");
        request.setContentType("application/json");
        request.setRequestBody("{\"action\":\"get_tasks\",\"token\":\"" + token + "\"}");
        
        NetworkManager.getInstance().addToQueueAndWait(request);
    }
    
    private void processTasks(String response) {
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
        Display.getInstance().callSerially(new Runnable() {
            @Override
            public void run() {
                statusLabel.setText("Выполняю: " + url);
            }
        });
        
        ConnectionRequest request = new ConnectionRequest() {
            @Override
            protected void readResponse(InputStream input) throws IOException {
                String body = readInputStream(input);
                submitResult(taskId, 200, body);
                completedCount++;
                updateStats();
            }
            
            @Override
            protected void handleException(Exception err) {
                submitError(taskId, err.getMessage());
                errorCount++;
                updateStats();
            }
        };
        
        request.setUrl(url);
        request.setHttpMethod("GET");
        request.setTimeout(30000);
        
        NetworkManager.getInstance().addToQueue(request);
    }
    
    private void submitResult(String taskId, int statusCode, String body) {
        String escaped = body.replace("\\", "\\\\")
                             .replace("\"", "\\\"")
                             .replace("\n", "\\n")
                             .replace("\r", "");
        
        ConnectionRequest request = new ConnectionRequest();
        request.setUrl(SERVER_URL);
        request.setHttpMethod("POST");
        request.setContentType("application/json");
        request.setRequestBody("{\"action\":\"submit_result\",\"token\":\"" + token + 
            "\",\"task_id\":\"" + taskId + 
            "\",\"status_code\":" + statusCode + 
            ",\"body\":\"" + escaped + "\"}");
        
        NetworkManager.getInstance().addToQueue(request);
    }
    
    private void submitError(String taskId, String error) {
        ConnectionRequest request = new ConnectionRequest();
        request.setUrl(SERVER_URL);
        request.setHttpMethod("POST");
        request.setContentType("application/json");
        request.setRequestBody("{\"action\":\"submit_result\",\"token\":\"" + token + 
            "\",\"task_id\":\"" + taskId + 
            "\",\"error\":\"" + error + "\"}");
        
        NetworkManager.getInstance().addToQueue(request);
    }
    
    private String readInputStream(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = input.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString("UTF-8");
    }
    
    private void updateStats() {
        Display.getInstance().callSerially(new Runnable() {
            @Override
            public void run() {
                statsLabel.setText("Выполнено: " + completedCount + 
                    "\nОшибок: " + errorCount);
                mainForm.revalidate();
            }
        });
    }
    
    public void stop() {
        if (isRunning) {
            stopProxy();
        }
    }
}
