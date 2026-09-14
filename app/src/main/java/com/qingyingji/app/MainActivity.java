package com.qingyingji.app;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.ResultKt;

public class MainActivity extends AppCompatActivity {
    private static final int FILE_CHOOSER_RESULT = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri currentPhotoUri;
    private Uri bridgePhotoUri;
    private ActivityResultLauncher<Set<String>> healthPermissionLauncher;
    private ActivityResultLauncher<String> activityPermissionLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<Uri> photoLauncher;
    private ActivityResultLauncher<String> galleryLauncher;
    private boolean localHealthRequested = false;

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
        webView.addJavascriptInterface(this, "PhotoBridge");
        registerHealthPermissionLauncher();
        registerActivityPermissionLauncher();
        registerPhotoLaunchers();

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    private void registerActivityPermissionLauncher() {
        activityPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted && localHealthRequested) {
                        localHealthRequested = false;
                        readLocalHealth();
                    } else if (localHealthRequested) {
                        localHealthRequested = false;
                        dispatchHealthResult("{\"error\":\"没有身体活动权限，无法读取本机步数\"}");
                    }
                });
    }

    private void registerPhotoLaunchers() {
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        launchCamera();
                    } else {
                        dispatchPhotoResult(null, "camera_permission_denied");
                    }
                });

        photoLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    Uri uri = bridgePhotoUri;
                    bridgePhotoUri = null;
                    if (Boolean.TRUE.equals(success) && uri != null) {
                        dispatchPhotoResult(uri, null);
                    } else {
                        dispatchPhotoResult(null, "camera_cancelled");
                    }
                });

        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        dispatchPhotoResult(uri, null);
                    } else {
                        dispatchPhotoResult(null, "image_cancelled");
                    }
                });
    }

    @JavascriptInterface
    public void takePhoto() {
        runOnUiThread(() -> {
            if (!hasCamera()) {
                dispatchPhotoResult(null, "no_camera");
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                launchCamera();
            } else {
                try {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
                } catch (Throwable t) {
                    dispatchPhotoResult(null, "camera_permission_error");
                }
            }
        });
    }

    @JavascriptInterface
    public void pickImage() {
        runOnUiThread(() -> {
            try {
                galleryLauncher.launch("image/*");
            } catch (Throwable t) {
                dispatchPhotoResult(null, "image_picker_error");
            }
        });
    }

    private boolean hasCamera() {
        return getPackageManager() != null
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private void launchCamera() {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir == null) {
                dispatchPhotoResult(null, "no_picture_dir");
                return;
            }
            File image = File.createTempFile("food_", ".jpg", dir);
            bridgePhotoUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", image);
            photoLauncher.launch(bridgePhotoUri);
        } catch (IOException e) {
            dispatchPhotoResult(null, "create_photo_failed");
        } catch (Throwable t) {
            dispatchPhotoResult(null, "camera_launch_failed");
        }
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

    private void dispatchPhotoResult(Uri uri, String error) {
        if (uri == null) {
            sendPhotoResult(null, error);
            return;
        }
        new Thread(() -> {
            String dataUrl = uriToDataUrl(uri);
            String finalError = dataUrl == null ? "decode_photo_failed" : error;
            sendPhotoResult(dataUrl, finalError);
        }).start();
    }

    private void sendPhotoResult(String dataUrl, String error) {
        runOnUiThread(() -> {
            if (webView == null) {
                return;
            }
            try {
                JSONObject obj = new JSONObject();
                obj.put("dataUrl", dataUrl == null ? JSONObject.NULL : dataUrl);
                obj.put("error", error == null ? JSONObject.NULL : error);
                String json = obj.toString().replace("\\", "\\\\").replace("'", "\\'");
                webView.evaluateJavascript(
                        "window.onPhotoBridgeResult && window.onPhotoBridgeResult('" + json + "');",
                        null);
            } catch (Throwable ignored) {
            }
        });
    }

    private String uriToDataUrl(Uri uri) {
        Bitmap bitmap = decodeSampledBitmap(uri, 1600, 1600);
        if (bitmap == null) {
            return null;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos);
            return "data:image/jpeg;base64," + Base64.encodeToString(
                    baos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private Bitmap decodeSampledBitmap(Uri uri, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                BitmapFactory.decodeStream(in, null, bounds);
            }

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= reqWidth
                    && bounds.outHeight / (sample * 2) >= reqHeight) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Throwable ignored) {
            return null;
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
        boolean isVivo = isVivoDevice();
        String[] vivoPackages = vivoHealthPackages();
        boolean localAvailable = hasStepCounter();
        StringBuilder base = new StringBuilder();
        base.append("{\"available\":").append(false)
                .append(",\"granted\":").append(false)
                .append(",\"isVivo\":").append(isVivo)
                .append(",\"localAvailable\":").append(localAvailable)
                .append(",\"vivoPackages\":[");
        for (int i = 0; i < vivoPackages.length; i++) {
            if (i > 0) base.append(",");
            base.append("\"").append(vivoPackages[i]).append("\"");
        }
        base.append("]}");
        HealthConnectClient client = getHealthConnectClient();
        if (client == null) {
            return injectJson(base.toString(), "\"error\":\"当前设备不支持 Health Connect，或未安装 Google Health Connect\"");
        }
        try {
            Set<String> granted = awaitSet(client.getPermissionController().getGrantedPermissions(blockingContinuation()));
            Set<String> required = requiredPermissions();
            if (granted.containsAll(required)) {
                return injectJson(base.toString(), "\"available\":true,\"granted\":true");
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
            return injectJson(base.toString(), "\"available\":true,\"granted\":false,\"missing\":" + arr);
        } catch (Throwable t) {
            return injectJson(base.toString(), "\"available\":true,\"granted\":false,\"error\":\"健康数据权限读取失败\"");
        }
    }

    private String injectJson(String object, String fields) {
        if (object == null || !object.endsWith("}")) {
            return object;
        }
        return object.substring(0, object.length() - 1) + "," + fields + "}";
    }

    private boolean isVivoDevice() {
        String brand = Build.BRAND;
        String manufacturer = Build.MANUFACTURER;
        if (brand != null) {
            String b = brand.toLowerCase(Locale.ROOT);
            if (b.contains("vivo") || b.contains("iqoo")) return true;
        }
        if (manufacturer != null) {
            String m = manufacturer.toLowerCase(Locale.ROOT);
            if (m.contains("vivo") || m.contains("bbk")) return true;
        }
        return false;
    }

    private String[] vivoHealthPackages() {
        List<String> known = Arrays.asList(
                "com.vivo.health",
                "com.vivo.exhealth",
                "com.vivo.healthwidget",
                "com.vivo.stepcount",
                "com.vivo.assistant");
        PackageManager pm = getPackageManager();
        StringBuilder arr = new StringBuilder();
        boolean first = true;
        for (String pkg : known) {
            try {
                pm.getPackageInfo(pkg, 0);
                if (!first) arr.append("|");
                arr.append(pkg);
                first = false;
            } catch (Throwable ignored) {
            }
        }
        if (arr.length() == 0) return new String[0];
        return arr.toString().split("\\|");
    }

    private boolean hasStepCounter() {
        try {
            SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
        } catch (Throwable t) {
            return false;
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

    @JavascriptInterface
    public void requestActivityPermission() {
        runOnUiThread(() -> {
            try {
                if (hasActivityPermission()) {
                    readLocalHealth();
                    return;
                }
                localHealthRequested = true;
                activityPermissionLauncher.launch("android.permission.ACTIVITY_RECOGNITION");
            } catch (Throwable t) {
                dispatchHealthResult("{\"error\":\"无法打开身体活动权限\"}");
            }
        });
    }

    @JavascriptInterface
    public void readLocalHealth() {
        runOnUiThread(() -> {
            if (!hasActivityPermission()) {
                localHealthRequested = true;
                try {
                    activityPermissionLauncher.launch("android.permission.ACTIVITY_RECOGNITION");
                } catch (Throwable t) {
                    localHealthRequested = false;
                    dispatchHealthResult("{\"error\":\"无法打开身体活动权限\"}");
                }
                return;
            }
            readStepCounter();
        });
    }

    private boolean hasActivityPermission() {
        return ContextCompat.checkSelfPermission(this, "android.permission.ACTIVITY_RECOGNITION")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void readStepCounter() {
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (sensor == null) {
            dispatchHealthResult("{\"error\":\"此手机没有可用的步数传感器\"}");
            return;
        }
        new Thread(() -> {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Float> steps = new AtomicReference<>();
            SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event.values != null && event.values.length > 0) {
                        steps.set(event.values[0]);
                        latch.countDown();
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            try {
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
                long deadline = System.currentTimeMillis() + 2500L;
                while (latch.getCount() > 0 && System.currentTimeMillis() < deadline) {
                    latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    sm.unregisterListener(listener);
                } catch (Throwable ignored) {
                }
            }
            Float value = steps.get();
            if (value == null) {
                dispatchHealthResult("{\"error\":\"未获取到本机步数，请尝试授权 Health Connect 或手动记录\"}");
                return;
            }
            long rounded = Math.max(0L, Math.round(value));
            dispatchHealthResult("{\"steps\":" + rounded + ",\"activeCalories\":null,\"totalCalories\":null,\"source\":\"device_sensor\"}");
        }).start();
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
