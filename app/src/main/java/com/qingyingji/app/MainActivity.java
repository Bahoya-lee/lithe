package com.qingyingji.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_RESULT = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri currentPhotoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                openFileChooser(params);
                return true;
            }
        });

        webView.addJavascriptInterface(this, "HealthBridge");
        if (Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 200);
        }

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    private void openFileChooser(WebChromeClient.FileChooserParams params) {
        Intent contentIntent = params.createIntent();
        try {
            createImageFile();
            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (currentPhotoUri != null && cameraIntent.resolveActivity(getPackageManager()) != null) {
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri);
                cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(contentIntent, "选择图片");
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
                startActivityForResult(chooser, FILE_CHOOSER_RESULT);
                return;
            }
        } catch (IOException ignored) {
        }
        startActivityForResult(contentIntent, FILE_CHOOSER_RESULT);
    }

    private void createImageFile() throws IOException {
        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) {
            throw new IOException("no external files directory");
        }
        File image = File.createTempFile("food_", ".jpg", dir);
        currentPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", image);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_RESULT) {
            if (filePathCallback == null) {
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                if (data != null && data.getData() != null) {
                    results = new Uri[]{data.getData()};
                } else if (currentPhotoUri != null) {
                    results = new Uri[]{currentPhotoUri};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            currentPhotoUri = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @JavascriptInterface
    public String readSteps() {
        if (Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            return "{\"steps\":null,\"error\":\"未授权活动识别权限\"}";
        }

        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) {
            return "{\"steps\":null,\"error\":\"传感器服务不可用\"}";
        }
        Sensor stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (stepSensor == null) {
            return "{\"steps\":null,\"error\":\"设备无计步传感器\"}";
        }

        final float[] value = new float[]{-1f};
        final boolean[] got = new boolean[]{false};
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                value[0] = event.values[0];
                got[0] = true;
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };

        sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_UI);
        long t0 = System.currentTimeMillis();
        while (!got[0] && System.currentTimeMillis() - t0 < 1200L) {
            try {
                Thread.sleep(60L);
            } catch (InterruptedException e) {
                break;
            }
        }
        sensorManager.unregisterListener(listener);

        if (!got[0]) {
            return "{\"steps\":null,\"error\":\"未能读取步数\"}";
        }

        long total = (long) value[0];
        SharedPreferences sp = getSharedPreferences("lithe_health", MODE_PRIVATE);
        String today = todayStr();
        String lastDay = sp.getString("step_day", "");
        long baseline = sp.getLong("step_baseline", total);
        if (!today.equals(lastDay)) {
            baseline = total;
            sp.edit().putLong("step_baseline", baseline).putString("step_day", today).apply();
        }
        long steps = Math.max(0L, total - baseline);
        return "{\"steps\":" + steps + ",\"totalSinceBoot\":" + total + "}";
    }

    private String todayStr() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
