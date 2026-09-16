package com.phoneproxy;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;

// ИСПРАВЛЕНО: добавлены импорты для addLog() и makeRequest()
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;

public class PhoneProxy extends Activity {
    
    // UI
    private TextView statusText;
    private TextView logText;
    private TextView statsText;
    private ScrollView logScrollView;
    private Button startButton;
    private Button stopButton;
    
    // Service
    private ProxyService proxyService;
    private boolean isServiceBound = false;

    // ===== API KEY =====
    private android.widget.EditText apiKeyInput;
    private android.widget.LinearLayout apiKeyLayout;
    private static final String PREFS_NAME = "PhoneProxyPrefs";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_PARTNER_ID = "partner_id";

    private static final String KEY_DEVICE_NAME = "device_name";

    // ИСПРАВЛЕНО: константа использовалась в validateApiKey/registerDeviceWithKey,
    // но не была объявлена в классе — компилятор падал на "cannot find symbol".
    private static final String SERVER_URL = "https://svoyaigra.pro/api/proxy.php";
    
    // Соединение с Service
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            ProxyService.LocalBinder binder = (ProxyService.LocalBinder) service;
            proxyService = binder.getService();
            isServiceBound = true;
            
            // Настраиваем обновление логов
            proxyService.setLogChangeListener(new Runnable() {
                @Override
                public void run() {
                    updateUI();
                }
            });
            
            // Обновляем UI сразу
            updateUI();
        }
        
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isServiceBound = false;
            proxyService = null;
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // UI
        statusText = (TextView) findViewById(R.id.statusText);
        logText = (TextView) findViewById(R.id.logText);
        statsText = (TextView) findViewById(R.id.statsText);
        logScrollView = (ScrollView) findViewById(R.id.logScrollView);
        
        startButton = (Button) findViewById(R.id.startButton);
        stopButton = (Button) findViewById(R.id.stopButton);
        Button clearLogButton = (Button) findViewById(R.id.clearLogButton);
        Button changeKeyButton = (Button) findViewById(R.id.changeKeyButton);

        // === ПРОВЕРКА API КЛЮЧА ===
        checkApiKey();
        
        // Обработчики
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // ИСПРАВЛЕНО: ВСЕГДА запускаем сервис через startForegroundService,
                // а не только startProxy() на привязанном экземпляре.
                startProxyService();
                
                // Если уже привязаны, дёрнем startProxy() для мгновенного отклика UI.
                if (proxyService != null) {
                    proxyService.startProxy();
                }
                
                updateUI();
            }
        });
        
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (proxyService != null) {
                    proxyService.stopProxy();
                    updateUI();
                }
            }
        });

        changeKeyButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmChangeKey();
                }
                });
        
        clearLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (proxyService != null) {
                    proxyService.clearLogs();
                }
            }
        });
        

    }
    
    @Override
    protected void onStart() {
        super.onStart();
        bindToService();
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
    }
    
    private void bindToService() {
        // Защита от повторной привязки — на случай, если onStart вызовется
        // дважды без парного onStop.
        if (isServiceBound) return;
        
        Intent intent = new Intent(this, ProxyService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }
    
    private void startProxyService() {
        Intent serviceIntent = new Intent(this, ProxyService.class);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        // bindToService() здесь НЕ вызываем — привязка идёт в onStart().
    }
    
    // Обновление UI из Service
    private void updateUI() {
        if (proxyService == null) return;
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Статус
                    if (proxyService.isRunning) {
                        if (proxyService.token != null) {
                            statusText.setText("✅ Подключено");
                        } else {
                            statusText.setText("🔄 Подключение...");
                        }
                        startButton.setEnabled(false);
                        stopButton.setEnabled(true);
                    } else {
                        statusText.setText("⏹ Остановлено");
                        startButton.setEnabled(true);
                        stopButton.setEnabled(false);
                    }
                    
                    // Статистика
                    statsText.setText("Заданий: " + proxyService.totalTasks + 
                        " | Успешно: " + proxyService.completedTasks + 
                        " | Ошибок: " + proxyService.failedTasks);
                    
                    // Логи
                    String logs = proxyService.getLogs();
                    logText.setText(logs);
                    
                    // Автоскролл
                    logScrollView.post(new Runnable() {
                        @Override
                        public void run() {
                            logScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                        }
                    });
                    
                } catch (Exception e) {
                    // ignore
                }
            }
        });
    }
    
    // ===== API KEY МЕТОДЫ =====
    
    private void checkApiKey() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedApiKey = prefs.getString(KEY_API_KEY, null);
        
        if (savedApiKey == null || savedApiKey.isEmpty()) {
            // Первый запуск - показываем окно ввода
            showApiKeyDialog();
        } else {
            // Ключ есть - пропускаем
            addLog("🔑 Приложение привязано к пользователю (ID: " + 
                   prefs.getInt(KEY_PARTNER_ID, 0) + ")");
        }
    }

    private void confirmChangeKey() {
    new android.app.AlertDialog.Builder(this)
        .setTitle("Сменить ключ?")
        .setMessage("Текущая привязка будет удалена. Понадобится ввести новый API ключ.")
        .setPositiveButton("Сменить", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                changeApiKey();
            }
        })
        .setNegativeButton("Отмена", null)
        .show();
}

private void changeApiKey() {
    // 1. Останавливаем сервис, если работает
    if (proxyService != null && proxyService.isRunning) {
        proxyService.stopProxy();
    }
    
    // 2. Чистим сохранённый ключ и partner_id.
    // device_name НЕ трогаем — он привязан к железу и останется тем же.
    android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    prefs.edit()
        .remove(KEY_API_KEY)
        .remove(KEY_PARTNER_ID)
        .apply();
    
    addLog("🔑 Ключ сброшен, введите новый");
    
    // 3. Показываем диалог ввода
    showApiKeyDialog();
}
    
    private void showApiKeyDialog() {
        // Создаём layout программно
        apiKeyLayout = new android.widget.LinearLayout(this);
        apiKeyLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        apiKeyLayout.setPadding(50, 30, 50, 30);
        
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Введите API ключ:");
        title.setTextSize(18);
        title.setPadding(0, 0, 0, 20);
        apiKeyLayout.addView(title);
        
        android.widget.TextView desc = new android.widget.TextView(this);
        desc.setText("Ключ находится в вашем личном кабинете на сайте");
        desc.setTextSize(14);
        desc.setPadding(0, 0, 0, 20);
        apiKeyLayout.addView(desc);
        
        apiKeyInput = new android.widget.EditText(this);
        apiKeyInput.setHint("Например: abc123xyz...");
        apiKeyInput.setTextSize(16);
        apiKeyLayout.addView(apiKeyInput);
        
        // Диалог
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("🔑 Привязка к аккаунту");
        builder.setView(apiKeyLayout);
        builder.setCancelable(false);
        
        builder.setPositiveButton("Проверить", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                String apiKey = apiKeyInput.getText().toString().trim();
                if (!apiKey.isEmpty()) {
                    validateApiKey(apiKey);
                } else {
                    android.widget.Toast.makeText(PhoneProxy.this, 
                        "Введите ключ!", android.widget.Toast.LENGTH_SHORT).show();
                    showApiKeyDialog(); // Показываем снова
                }
            }
        });
        
        builder.setNegativeButton("Позже", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                android.widget.Toast.makeText(PhoneProxy.this, 
                    "Без ключа приложение будет работать без привязки", 
                    android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        
        builder.show();
    }
    
    private void validateApiKey(final String apiKey) {
        addLog("🔑 Проверка ключа: " + apiKey.substring(0, Math.min(8, apiKey.length())) + "...");
        
        // Проверка на сервере
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String response = makeRequest(SERVER_URL, 
                        "{\"action\":\"validate_api_key\",\"api_key\":\"" + apiKey + "\"}");
                    
                    // Убираем PHP warnings
                    if (response.contains("<br />")) {
                        int jsonStart = response.indexOf("{\"success\"");
                        if (jsonStart >= 0) {
                            response = response.substring(jsonStart);
                        }
                    }
                    
                    if (response.contains("\"success\":true")) {
                        // Ключ валиден - извлекаем partner_id
                        String partnerId = extractJsonField(response, "partner_id");
                        String partnerName = extractJsonField(response, "partner_name");
                        
                        if (partnerId != null) {
                            // Сохраняем
                            android.content.SharedPreferences prefs = 
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                            android.content.SharedPreferences.Editor editor = prefs.edit();
                            editor.putString(KEY_API_KEY, apiKey);
                            editor.putInt(KEY_PARTNER_ID, Integer.parseInt(partnerId));
                            editor.apply();
                            
                            addLog("✅ Привязано к пользователю: " + 
                                (partnerName != null ? partnerName : "ID: " + partnerId));
                            
                            // ИСПРАВЛЕНО: сразу регистрируем устройство на сервере,
                            // чтобы строка в proxy_devices появилась уже сейчас, а не после
                            // первого нажатия «Старт». Повторный register из ProxyService
                            // найдёт эту же строку по (device_name, partner_id) и обновит токен.
                            registerDeviceWithKey(apiKey);
                            
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    android.widget.Toast.makeText(PhoneProxy.this, 
                                        "✅ Успешно привязано!", 
                                        android.widget.Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                        
                    } else {
                        // Ключ невалиден
                        addLog("❌ Неверный API ключ");
                        
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                android.widget.Toast.makeText(PhoneProxy.this, 
                                    "❌ Неверный ключ! Попробуйте снова", 
                                    android.widget.Toast.LENGTH_LONG).show();
                                showApiKeyDialog(); // Показываем снова
                            }
                        });
                    }
                    
                } catch (final Exception e) {
                    addLog("❌ Ошибка проверки ключа: " + e.getMessage());
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(PhoneProxy.this, 
                                "Ошибка соединения: " + e.getMessage(), 
                                android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }
    
    // Получить сохранённый API ключ
    private String getSavedApiKey() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_API_KEY, null);
    }

    // ИСПРАВЛЕНО: уникальное имя устройства на основе ANDROID_ID.
    // Создаётся один раз при первом запуске и сохраняется в SharedPreferences.
    // Используется и здесь, и в ProxyService — оба читают из "PhoneProxyPrefs".
    private String getOrCreateDeviceName() {
        android.content.SharedPreferences prefs = 
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        String deviceName = prefs.getString(KEY_DEVICE_NAME, null);
        if (deviceName != null && !deviceName.isEmpty()) {
            return deviceName;
        }
        
        // Пытаемся получить ANDROID_ID
        String androidId = null;
        try {
            androidId = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
            );
        } catch (Exception e) {
            // ignore — используем fallback
        }
        
        // Проверки:
        //  - null / пусто — не получилось
        //  - "9774d56d682e549c" — известный баг старых прошивок
        //  - "unknown" — тоже бывает
        boolean isBad = (androidId == null || androidId.isEmpty() 
                         || "9774d56d682e549c".equals(androidId)
                         || "unknown".equalsIgnoreCase(androidId));
        
        if (isBad) {
            // Fallback: случайный UUID, сохранится навсегда
            androidId = java.util.UUID.randomUUID().toString().replace("-", "");
        }
        
        // Берём первые 8 символов — этого достаточно для уникальности
        String shortId = androidId.substring(0, Math.min(8, androidId.length()));
        deviceName = "Android_" + shortId;
        
        prefs.edit().putString(KEY_DEVICE_NAME, deviceName).apply();
        return deviceName;
    }

    // ИСПРАВЛЕНО: регистрация устройства на сервере сразу после ввода API-ключа.
    // Отправляет тот же запрос, что делает ProxyService.register(), но из Activity —
    // чтобы не ждать нажатия «Старт».
    private void registerDeviceWithKey(final String apiKey) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String deviceName = getOrCreateDeviceName();
                    String response = makeRequest(SERVER_URL,
                        "{\"action\":\"register\",\"device_name\":\"" + deviceName + "\"," +
                        "\"api_key\":\"" + apiKey + "\"}");
                    
                    // Убираем PHP warnings, если вдруг есть
                    if (response.contains("<br />")) {
                        int jsonStart = response.indexOf("{\"success\"");
                        if (jsonStart >= 0) {
                            response = response.substring(jsonStart);
                        }
                    }
                    
                    if (response.contains("\"token\"")) {
                        String token = extractJsonField(response, "token");
                        addLog("✅ Устройство зарегистрировано на сервере" +
                               (token != null ? " (token: " + 
                                token.substring(0, Math.min(12, token.length())) + "...)" : ""));
                    } else {
                        addLog("⚠️ Регистрация устройства: " + response);
                    }
                } catch (final Exception e) {
                    addLog("❌ Ошибка регистрации устройства: " + e.getMessage());
                }
            }
        }).start();
    }

    // ===== ХЕЛПЕРЫ (скопированы из ProxyService) =====
    // ИСПРАВЛЕНО: эти три метода и SERVER_URL раньше вызывались, но не были
    // определены в этом классе. validateApiKey() и registerDeviceWithKey()
    // работают до привязки к сервису, поэтому нужны свои реализации.
    
    private void addLog(final String message) {
        android.util.Log.d("PhoneProxy", message);
        
        // Пишем в UI-лог, если он уже создан
        if (logText != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
                        logText.append("[" + timestamp + "] " + message + "\n");
                        
                        if (logScrollView != null) {
                            logScrollView.post(new Runnable() {
                                @Override
                                public void run() {
                                    logScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                                }
                            });
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            });
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
            throw new Exception("HTTP " + responseCode + ": " + 
                (response.length() > 200 ? response.substring(0, 200) : response.toString()));
        }
        
        return response.toString();
    }
    
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
            // Попытка для числового значения без кавычек: "partner_id":123
            search = "\"" + field + "\":";
            start = json.indexOf(search);
            if (start >= 0) {
                start += search.length();
                int end = start;
                while (end < json.length() && 
                       (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
                    end++;
                }
                if (end > start) {
                    return json.substring(start, end);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private void shareLog() {
        String logContent = "";
        if (proxyService != null) {
            logContent = proxyService.getLogs();
        }
        
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Phone Proxy Log");
        shareIntent.putExtra(Intent.EXTRA_TEXT, logContent);
        
        startActivity(Intent.createChooser(shareIntent, "Отправить лог:"));
    }
    
    // ===== ЖИЗНЕННЫЙ ЦИКЛ =====
    
    @Override
    public void onBackPressed() {
        // Сворачиваем, НЕ закрываем
        moveTaskToBack(true);
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Вызывается когда Activity уже открыто и кликнули по уведомлению
        setIntent(intent);
        updateUI();
    }
    
    @Override
    protected void onDestroy() {
        // НЕ останавливаем Service — он работает дальше
        super.onDestroy();
    }
}
