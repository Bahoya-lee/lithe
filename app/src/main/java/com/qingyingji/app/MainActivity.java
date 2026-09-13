package com.qingyingji.app;

import android.content.Intent;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.PermissionController;
import androidx.health.connect.client.aggregate.AggregateMetric;
import androidx.health.connect.client.aggregate.AggregationResult;
import androidx.health.connect.client.permission.HealthPermission;
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord;
import androidx.health.connect.client.records.StepsRecord;
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord;
import androidx.health.connect.client.records.metadata.DataOrigin;
import androidx.health.connect.client.request.AggregateRequest;
import androidx.health.connect.client.time.TimeRangeFilter;
import androidx.health.connect.client.units.Energy;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.ResultKt;

public class MainActivity extends AppCompatActivity {
    private static final int FILE_CHOOSER_RESULT = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri currentPhotoUri;
    private ActivityResultLauncher<Set<String>> healthPermissionLauncher;

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
        registerHealthPermissionLauncher();

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    private void registerHealthPermissionLauncher() {
        if (Build.VERSION.SDK_INT < 34) {
            return;
        }
        HealthConnectClient client = getHealthConnectClient();
        if (client == null) {
            return;
        }
        healthPermissionLauncher = registerForActivityResult(
                PermissionController.createRequestPermissionResultContract(),
                granted -> {
                    if (granted == null || granted.isEmpty()) {
                        dispatchHealthResult("{\"error\":\"未授权读取健康数据\"}");
                        return;
                    }
                    readHealthData();
                });
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
    public String healthStatus() {
        HealthConnectClient client = getHealthConnectClient();
        if (client == null) {
            return "{\"available\":false,\"error\":\"当前设备不支持 Health Connect，或未安装 Google Health Connect\"}";
        }
        try {
            Set<String> granted = awaitSet(client.getPermissionController().getGrantedPermissions(blockingContinuation()));
            Set<String> required = requiredPermissions();
            if (granted.containsAll(required)) {
                return "{\"available\":true,\"granted\":true}";
            }
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(granted);
            StringBuilder arr = new StringBuilder("[");
            boolean first = true;
            for (String p : missing) {
                if (!first) arr.append(",");
                arr.append("\"").append(p).append("\"");
                first = false;
            }
            arr.append("]");
            return "{\"available\":true,\"granted\":false,\"missing\":" + arr + "}";
        } catch (Throwable t) {
            return "{\"available\":true,\"granted\":false,\"error\":\"健康数据权限读取失败\"}";
        }
    }

    @JavascriptInterface
    public void requestHealthPermissions() {
        runOnUiThread(() -> {
            if (healthPermissionLauncher == null) {
                dispatchHealthResult("{\"error\":\"当前设备不支持 Health Connect\"}");
                return;
            }
            try {
                healthPermissionLauncher.launch(requiredPermissions());
            } catch (Throwable t) {
                dispatchHealthResult("{\"error\":\"无法打开健康数据授权\"}");
            }
        });
    }

    @JavascriptInterface
    public void readHealth() {
        readHealthData();
    }

    private HealthConnectClient getHealthConnectClient() {
        if (Build.VERSION.SDK_INT < 34) {
            return null;
        }
        try {
            if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
                return null;
            }
            return HealthConnectClient.getOrCreate(this);
        } catch (Throwable t) {
            return null;
        }
    }

    private Set<String> requiredPermissions() {
        Set<String> permissions = new HashSet<>();
        permissions.add(HealthPermission.READ_STEPS);
        permissions.add(HealthPermission.READ_ACTIVE_CALORIES_BURNED);
        try {
            permissions.add(HealthPermission.READ_TOTAL_CALORIES_BURNED);
        } catch (Throwable ignored) {
        }
        return permissions;
    }

    private void readHealthData() {
        HealthConnectClient client = getHealthConnectClient();
        if (client == null) {
            dispatchHealthResult("{\"error\":\"当前设备不支持 Health Connect\"}");
            return;
        }

        new Thread(() -> {
            try {
                Instant start = LocalDate.now(ZoneId.systemDefault())
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant();
                Instant end = Instant.now();

                Set<AggregateMetric<?>> metrics = new HashSet<>();
                metrics.add(StepsRecord.COUNT_TOTAL);
                metrics.add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL);
                boolean hasTotalCalories = true;
                try {
                    metrics.add(TotalCaloriesBurnedRecord.ENERGY_TOTAL);
                } catch (Throwable t) {
                    hasTotalCalories = false;
                }

                AggregateRequest request = new AggregateRequest(
                        metrics,
                        TimeRangeFilter.between(start, end),
                        Collections.<DataOrigin>emptySet());
                AggregationResult response = awaitResult(client.aggregate(request, blockingContinuation()));

                Long steps = response.get(StepsRecord.COUNT_TOTAL);
                Energy activeEnergy = response.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL);
                Energy totalEnergy = hasTotalCalories
                        ? response.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                        : null;

                StringBuilder json = new StringBuilder("{");
                json.append("\"steps\":").append(steps == null ? "null" : steps).append(",");
                json.append("\"activeCalories\":")
                        .append(activeEnergy == null ? "null" : Math.round(activeEnergy.getKilocalories())).append(",");
                json.append("\"totalCalories\":")
                        .append(totalEnergy == null ? "null" : Math.round(totalEnergy.getKilocalories()));
                json.append("}");
                dispatchHealthResult(json.toString());
            } catch (Throwable t) {
                dispatchHealthResult("{\"error\":\"健康数据读取失败：\"}");
            }
        }).start();
    }

    private void dispatchHealthResult(final String json) {
        runOnUiThread(() -> {
            if (webView != null) {
                String safe = json == null ? "{}" : json.replace("\\", "\\\\").replace("'", "\\'");
                webView.evaluateJavascript(
                        "window.onHealthBridgeResult && window.onHealthBridgeResult('" + safe + "');", null);
            }
        });
    }

    private <T> T awaitResult(Object value) {
        if (value instanceof BlockingContinuation) {
            return ((BlockingContinuation<T>) value).await();
        }
        return (T) value;
    }

    private Set<String> awaitSet(Object value) {
        return awaitResult(value);
    }

    private <T> BlockingContinuation<T> blockingContinuation() {
        return new BlockingContinuation<>();
    }

    private static final class BlockingContinuation<T> implements Continuation<T> {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<Object> result = new AtomicReference<>();

        @Override
        public CoroutineContext getContext() {
            return EmptyCoroutineContext.INSTANCE;
        }

        @Override
        public void resumeWith(Object resumeValue) {
            result.set(resumeValue);
            latch.countDown();
        }

        T await() {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("健康数据读取被中断", e);
            }
            Object value = result.get();
            if (value == null) {
                throw new RuntimeException("健康数据读取失败");
            }
            try {
                ResultKt.throwOnFailure(value);
            } catch (Throwable t) {
                throw new RuntimeException("健康数据读取失败", t);
            }
            return (T) value;
        }
    }
}
