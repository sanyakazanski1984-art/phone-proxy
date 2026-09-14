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
        Button shareLogButton = (Button) findViewById(R.id.shareLogButton);
        
        // Обработчики
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // ИСПРАВЛЕНО: ВСЕГДА запускаем сервис через startForegroundService,
                // а не только startProxy() на привязанном экземпляре.
                //
                // Раньше, если bind уже прошёл, вызывался только proxyService.startProxy().
                // Сервис оставался "bound-only" (не started), и при onStop -> unbindService
                // Android его уничтожал. После повторного открытия приложения сервис
                // создавался заново с пустыми isRunning/token/logBuilder.
                //
                // Теперь сервис становится started foreground service -> переживает unbind.
                startProxyService();
                
                // Если уже привязаны, дёрнем startProxy() для мгновенного отклика UI.
                // Guard "if (isRunning) return;" в ProxyService.startProxy() не даст
                // запустить второй цикл опроса, когда чуть позже придёт onStartCommand.
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
        
        clearLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (proxyService != null) {
                    proxyService.clearLogs();
                }
            }
        });
        
        shareLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLog();
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
