package com.cineisle.app;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.ImageReader;
import android.media.Image;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.hardware.display.DisplayManager;
import android.view.accessibility.AccessibilityEvent;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class CinemaAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService screenshotExecutor = Executors.newSingleThreadExecutor();
    private String foregroundPackage = "";
    private boolean loopStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "CineIsle 录屏服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("用于保持截图功能在后台运行");
            NotificationManager mgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (mgr != null) mgr.createNotificationChannel(channel);
        }
    }

    private void ensureForeground() {
        if (foregroundStarted) return;
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }
            Notification notification = builder
                .setContentTitle("CineIsle 观影助手")
                .setContentText("正在后台运行截图功能")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
            startForeground(NOTIFICATION_ID, notification);
            foregroundStarted = true;
        } catch (Exception e) {
            setStatus("前台通知失败：" + e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : ""));
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        setStatus("无障碍服务已连接，等待截图请求");
        startLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            ensureForeground();
            if (intent != null && intent.hasExtra("resultCode") && intent.hasExtra("data")) {
                int resultCode = intent.getIntExtra("resultCode", -1);
                Intent data = (Intent) intent.getParcelableExtra("data");
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startMediaProjection(resultCode, data);
                }
            }
        } catch (Exception e) {
            setStatus("onStartCommand 异常：" + e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : ""));
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private final Runnable screenshotLoop = new Runnable() {
        @Override public void run() {
            tryUploadScreenshot();
            handler.postDelayed(this, 5000);
        }
    };

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            foregroundPackage = event.getPackageName().toString();
        }
        startLoop();
    }

    @Override public void onInterrupt() {}

    private void startLoop() {
        if (loopStarted) return;
        loopStarted = true;
        handler.postDelayed(screenshotLoop, 1500);
    }

    private void tryUploadScreenshot() {
        android.content.SharedPreferences sp = getSharedPreferences("cineisle", 0);
        String serverUrl = sp.getString("serverUrl", "");
        String roomId = sp.getString("roomId", "");
        String token = sp.getString("token", "");
        String name = sp.getString("name", "观影人");
        String assistantName = helperName(sp.getString("assistantName", "观影助手"));
        if (serverUrl.length() == 0 || roomId.length() == 0) return;

        long localReq = sp.getLong("screenshotRequestId", 0);
        long handledLocalReq = sp.getLong("lastHandledScreenshotRequestId", 0);
        boolean localForce = localReq > 0 && localReq != handledLocalReq;
        if (localForce) {
            sp.edit().putLong("lastHandledScreenshotRequestId", localReq).apply();
        }

        boolean remoteForce = checkRemoteRequest(serverUrl, roomId, token, sp, assistantName);
        boolean auto = sp.getBoolean("autoScreenshot", false);
        if (!auto && !localForce && !remoteForce) return;

        boolean force = localForce || remoteForce;
        long now = System.currentTimeMillis();
        long last = sp.getLong("lastScreenshotUploadMs", 0);
        long intervalMs = Math.max(10000, sp.getLong("screenshotIntervalMs", 15000));
        if (!force && now - last < intervalMs) return;
        sp.edit().putLong("lastScreenshotUploadMs", now).apply();
        takeAndUpload(serverUrl, roomId, token, name, force ? "accessibility-request" : "accessibility-low-frequency");
    }

    private String helperName(String s) {
        s = s == null ? "" : s.trim();
        return s.length() > 0 ? s : "观影助手";
    }

    private boolean checkRemoteRequest(String serverUrl, String roomId, String token, android.content.SharedPreferences sp, String assistantName) {
        try {
            String since = sp.getString("lastRemoteScreenshotRequestId", "");
            URL url = new URL(serverUrl + "/api/rooms/" + roomId + "/screenshot-request?since=" + since);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            if (token != null && token.length() > 0) c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            if (code >= 400) return false;
            String text = readAll(c.getInputStream());
            JSONObject obj = new JSONObject(text);
            if (!obj.optBoolean("pending", false)) return false;
            String requestId = obj.optString("requestId", "");
            if (requestId.length() == 0 || requestId.equals(since)) return false;
            sp.edit().putString("lastRemoteScreenshotRequestId", requestId).apply();
            setStatus("收到" + helperName(assistantName) + "截图请求：" + requestId);
            return true;
        } catch(Exception ignored) {
            return false;
        }
    }

    private String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) >= 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static final String CHANNEL_ID = "cineisle_projection";
    private static final int NOTIFICATION_ID = 1;
    private boolean foregroundStarted = false;

    private MediaProjection mediaProjection = null;
    private ImageReader imageReader = null;
    private int screenWidth = 0, screenHeight = 0, screenDensity = 0;

    public void startMediaProjection(int resultCode, Intent data) {
        if (mediaProjection != null) return;
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mgr.getMediaProjection(resultCode, data);
        if (mediaProjection != null) {
            // Android 14+ 要求必须先注册回调
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    mediaProjection = null;
                    if (imageReader != null) { imageReader.close(); imageReader = null; }
                    setStatus("MediaProjection 已停止");
                }
            }, handler);
            initImageReader();
            setStatus("MediaProjection 已启动，可以截图");
        } else {
            setStatus("MediaProjection 启动失败");
        }
    }

    private void initImageReader() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);
        mediaProjection.createVirtualDisplay("cineisle", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
    }

    private void takeAndUpload(final String serverUrl, final String roomId, final String token, final String name, final String source) {
        if (mediaProjection == null || imageReader == null) {
            setStatus("截图失败：MediaProjection 未启动，请先授权录屏");
            getSharedPreferences("cineisle", 0).edit().putLong("lastScreenshotUploadMs", 0).apply();
            return;
        }
        // 在后台线程执行截图，避免阻塞主线程
        screenshotExecutor.execute(() -> {
            try {
                handler.post(() -> setStatus("正在截图…"));
                Image image = imageReader.acquireLatestImage();
                // 第一次获取可能为 null（VirtualDisplay 尚未生成第一帧），等待重试
                if (image == null) {
                    Thread.sleep(300);
                    image = imageReader.acquireLatestImage();
                }
                if (image == null) {
                    handler.post(() -> setStatus("截图失败：未获取到图像"));
                    getSharedPreferences("cineisle", 0).edit().putLong("lastScreenshotUploadMs", 0).apply();
                    return;
                }
                try {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * screenWidth;
                    Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);
                    Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
                    bitmap.recycle();

                    Bitmap resized = resize(cropped, 720);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    resized.compress(Bitmap.CompressFormat.JPEG, 55, bos);
                    int w = resized.getWidth();
                    int h = resized.getHeight();
                    String base64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                    upload(serverUrl, roomId, token, name, base64, w, h, source);
                } finally {
                    image.close();
                }
            } catch (Throwable e) {
                handler.post(() -> setStatus("截图失败：" + e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "未知错误")));
                getSharedPreferences("cineisle", 0).edit().putLong("lastScreenshotUploadMs", 0).apply();
            }
        });
    }

    private Bitmap resize(Bitmap src, int maxWidth) {
        if (src.getWidth() <= maxWidth) return src;
        int w = maxWidth;
        int h = Math.max(1, Math.round(src.getHeight() * (maxWidth / (float)src.getWidth())));
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    private void upload(String serverUrl, String roomId, String token, String name, String base64, int width, int height, String source) throws Exception {
        handler.post(() -> setStatus("正在上传截图…"));
        URL url = new URL(serverUrl + "/api/rooms/" + roomId + "/screenshot");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (token != null && token.length() > 0) c.setRequestProperty("Authorization", "Bearer " + token);
        JSONObject body = new JSONObject();
        body.put("actor", name);
        body.put("mime", "image/jpeg");
        body.put("imageBase64", base64);
        body.put("width", width);
        body.put("height", height);
        body.put("source", source);
        body.put("note", "映屿画面同步：用户开启截图后上传当前屏幕，不再要求映屿处于前台。");
        String bodyStr = body.toString();
        byte[] bodyBytes = bodyStr.getBytes("UTF-8");
        handler.post(() -> setStatus("上传请求体大小：" + bodyBytes.length + " 字节"));
        try(OutputStream os = c.getOutputStream()) {
            os.write(bodyBytes);
            os.flush();
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            String err = "";
            try {
                java.io.InputStream es = c.getErrorStream();
                if (es != null) {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(es));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    err = sb.toString();
                }
            } catch (Exception ignored) {}
            throw new RuntimeException("HTTP " + code + ": " + err);
        }
        setStatus("截图已上传：" + width + "×" + height + "，HTTP " + code);
    }

    private void setStatus(String s) {
        getSharedPreferences("cineisle", 0).edit()
                .putString("lastScreenshotStatus", s)
                .putLong("lastScreenshotStatusAt", System.currentTimeMillis())
                .apply();
    }
}
