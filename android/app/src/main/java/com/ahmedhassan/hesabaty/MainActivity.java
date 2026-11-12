/*
 * حساباتي - تطبيق أندرويد
 * تصميم وتطوير: أحمد حسن عبدالله  |  واتساب: +201015421255
 * © 2026 أحمد حسن عبدالله - جميع الحقوق محفوظة.
 *
 * التطبيق يعرض ملفات حساباتي المضمّنة داخله (تعمل بدون إنترنت)، ويضيف:
 *  - الطباعة وحفظ PDF عن طريق نظام أندرويد
 *  - حفظ النسخ الاحتياطية في مجلد التنزيلات
 *  - فتح روابط واتساب وفيسبوك في تطبيقاتها
 *  - تحميل التحديث الجديد وتثبيته مباشرة من داخل التطبيق
 */
package com.ahmedhassan.hesabaty;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    /** نطاق داخلي لعرض ملفات التطبيق (لا يتصل بالإنترنت) */
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/index.html";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_STORAGE = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long downloadId = -1;
    private String pendingUpdateUrl;
    private String pendingUpdateVersion;
    private String[] pendingSave; // {name, mime, text}

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0b1b33"));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        webView.addJavascriptInterface(new Bridge(), "HesabatyApp");
        webView.setWebViewClient(new AppClient());
        webView.setWebChromeClient(new AppChromeClient());

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        // لو المستخدم رجع بعد ما سمح بالتثبيت من هذا المصدر: نكمل التحميل
        if (pendingUpdateUrl != null && canInstallPackages()) {
            String url = pendingUpdateUrl, ver = pendingUpdateVersion;
            pendingUpdateUrl = null;
            startDownload(url, ver);
        }
        js("window.onAppResume && window.onAppResume()");
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    /** زر الرجوع: يقفل النافذة المفتوحة في التطبيق أولاً، وبعدها يخرج */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void js(final String code) {
        if (webView == null) return;
        handler.post(new Runnable() {
            @Override public void run() {
                if (webView != null) webView.evaluateJavascript(code, null);
            }
        });
    }

    private void toast(final String msg) {
        handler.post(new Runnable() {
            @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
        });
    }

    /* ------------------------------------------------------------------
       عرض ملفات التطبيق من داخل الـ APK + الخطوط بدون إنترنت
       ------------------------------------------------------------------ */
    private class AppClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return intercept(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleLink(Uri.parse(url));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleLink(request.getUrl());
        }
    }

    /** أي رابط خارج التطبيق (واتساب، فيسبوك، تحميل التحديث) يفتح في التطبيق المناسب */
    private boolean handleLink(Uri uri) {
        if (uri == null) return false;
        if (APP_HOST.equals(uri.getHost())) return false;
        openExternalUri(uri);
        return true;
    }

    private void openExternalUri(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("لا يوجد تطبيق لفتح هذا الرابط");
        }
    }

    private WebResourceResponse intercept(Uri uri) {
        String host = uri.getHost();
        if (host == null) return null;
        try {
            if (APP_HOST.equals(host)) {
                String path = uri.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                return asset("www" + path, mimeFor(path), false);
            }
            // خطوط Cairo مضمّنة داخل التطبيق علشان الشكل يفضل زي ما هو بدون إنترنت
            if ("fonts.googleapis.com".equals(host)) {
                WebResourceResponse r = asset("fonts/cairo.css", "text/css", true);
                if (r != null) return r;
            }
            if ("fonts.gstatic.com".equals(host)) {
                String name = uri.getLastPathSegment();
                if (name != null) {
                    WebResourceResponse r = asset("fonts/" + name, mimeFor(name), true);
                    if (r != null) return r;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private WebResourceResponse asset(String path, String mime, boolean cors) {
        try {
            InputStream in = getAssets().open(path);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            if (cors) headers.put("Access-Control-Allow-Origin", "*");
            String enc = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") ? "utf-8" : null;
            return new WebResourceResponse(mime, enc, 200, "OK", headers, in);
        } catch (IOException e) {
            if (path.startsWith("www/")) {
                Map<String, String> headers = new HashMap<>();
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers,
                        new java.io.ByteArrayInputStream(new byte[0]));
            }
            return null;
        }
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    /* ------------------------------------------------------------------
       اختيار الملفات (اللوجو + استعادة النسخة الاحتياطية)
       ------------------------------------------------------------------ */
    private class AppChromeClient extends WebChromeClient {
        /** رسائل الصفحة في سجل أندرويد (لاختبار التشغيل وتتبع الأخطاء) */
        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            int level = m.messageLevel() == ConsoleMessage.MessageLevel.ERROR ? Log.ERROR : Log.INFO;
            Log.println(level, "HesabatyWeb", m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            Intent intent;
            try {
                intent = params.createIntent();
            } catch (Exception e) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
            }
            // ملفات النسخة الاحتياطية قد تظهر بأنواع مختلفة على بعض الأجهزة
            if ("application/json".equals(intent.getType()) || ".json,application/json".equals(intent.getType())) intent.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(intent, "اختيار ملف"), REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                toast("لا يوجد تطبيق لاختيار الملفات");
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ------------------------------------------------------------------
       الجسر بين صفحة التطبيق وأندرويد
       ------------------------------------------------------------------ */
    private class Bridge {
        @JavascriptInterface
        public String versionName() {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return pi.versionName;
            } catch (Exception e) {
                return "0";
            }
        }

        @JavascriptInterface
        public int sdk() { return Build.VERSION.SDK_INT; }

        @JavascriptInterface
        public void openExternal(final String url) {
            handler.post(new Runnable() {
                @Override public void run() { openExternalUri(Uri.parse(url)); }
            });
        }

        @JavascriptInterface
        public void print(final String title) {
            handler.post(new Runnable() {
                @Override public void run() { doPrint(title); }
            });
        }

        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String text) {
            handler.post(new Runnable() {
                @Override public void run() { doSaveFile(name, mime, text); }
            });
        }

        @JavascriptInterface
        public void downloadUpdate(final String url, final String version) {
            handler.post(new Runnable() {
                @Override public void run() { requestUpdate(url, version); }
            });
        }
    }

    /* ---------- الطباعة / حفظ PDF ---------- */
    private void doPrint(String title) {
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            String job = (title == null || title.trim().isEmpty()) ? "حساباتي" : title.trim();
            PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(job);
            PrintAttributes attrs = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();
            pm.print(job, adapter, attrs);
        } catch (Exception e) {
            toast("تعذر فتح الطباعة على هذا الجهاز");
        }
    }

    /* ---------- حفظ النسخة الاحتياطية في التنزيلات ---------- */
    private void doSaveFile(String name, String mime, String text) {
        String safeName = name == null ? "hesabaty.json" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        byte[] bytes = (text == null ? "" : text).getBytes(Charset.forName("UTF-8"));
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/json" : mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hesabaty");
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("insert failed");
                OutputStream out = cr.openOutputStream(uri);
                if (out == null) throw new IOException("stream failed");
                out.write(bytes);
                out.close();
                toast("✔ تم الحفظ في التنزيلات ← Hesabaty\n" + safeName);
                return;
            }
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingSave = new String[]{name, mime, text};
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                return;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hesabaty");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
            File f = new File(dir, safeName);
            FileOutputStream out = new FileOutputStream(f);
            out.write(bytes);
            out.close();
            toast("✔ تم الحفظ في التنزيلات ← Hesabaty\n" + safeName);
        } catch (Exception e) {
            // آخر حل: حفظ داخل مجلد التطبيق
            try {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, safeName);
                FileOutputStream out = new FileOutputStream(f);
                out.write(bytes);
                out.close();
                toast("✔ تم الحفظ في:\n" + f.getAbsolutePath());
            } catch (Exception e2) {
                toast("⚠ تعذر حفظ الملف");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE && pendingSave != null) {
            String[] p = pendingSave;
            pendingSave = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) doSaveFile(p[0], p[1], p[2]);
            else toast("⚠ لازم توافق على صلاحية التخزين علشان تحفظ النسخة");
        }
    }

    /* ------------------------------------------------------------------
       تحميل التحديث الجديد وتثبيته مباشرة
       ------------------------------------------------------------------ */
    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void requestUpdate(String url, String version) {
        if (url == null || url.isEmpty()) return;
        if (!canInstallPackages()) {
            // أندرويد 8+ يطلب السماح بالتثبيت من هذا التطبيق مرة واحدة
            pendingUpdateUrl = url;
            pendingUpdateVersion = version;
            js("window.onAppUpdateProgress && window.onAppUpdateProgress(0,'permission')");
            toast("فعّل «السماح من هذا المصدر» ثم ارجع للتطبيق");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                pendingUpdateUrl = null;
                openExternalUri(Uri.parse(url));
            }
            return;
        }
        startDownload(url, version);
    }

    private static String updateFileName(String version) {
        return "hesabaty-" + (version == null ? "update" : version.replaceAll("[^0-9.]", "")) + ".apk";
    }

    private File updateFile(String version) {
        return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), updateFileName(version));
    }

    private void startDownload(final String url, final String version) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            File f = updateFile(version);
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle("تحديث حساباتي " + (version == null ? "" : version));
            r.setDescription("جاري تحميل التحديث الجديد");
            r.setMimeType(APK_MIME);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, updateFileName(version));
            downloadId = dm.enqueue(r);
            js("window.onAppUpdateProgress && window.onAppUpdateProgress(0,'progress')");
            pollDownload(version);
        } catch (Exception e) {
            // لو مدير التحميل مش متاح: نفتح الرابط في المتصفح
            js("window.onAppUpdateProgress && window.onAppUpdateProgress(0,'failed')");
            openExternalUri(Uri.parse(url));
        }
    }

    private void pollDownload(final String version) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (downloadId < 0) return;
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId));
                if (c == null || !c.moveToFirst()) {
                    if (c != null) c.close();
                    js("window.onAppUpdateProgress && window.onAppUpdateProgress(0,'failed')");
                    downloadId = -1;
                    return;
                }
                int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                long done = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                c.close();
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    js("window.onAppUpdateProgress && window.onAppUpdateProgress(100,'install')");
                    long id = downloadId;
                    downloadId = -1;
                    installApk(id, version);
                } else if (status == DownloadManager.STATUS_FAILED) {
                    js("window.onAppUpdateProgress && window.onAppUpdateProgress(0,'failed')");
                    downloadId = -1;
                } else {
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    js("window.onAppUpdateProgress && window.onAppUpdateProgress(" + pct + ",'progress')");
                    pollDownload(version);
                }
            }
        }, 500);
    }

    private void installApk(long id, String version) {
        try {
            File f = updateFile(version);
            Uri uri = Build.VERSION.SDK_INT >= 24 ? ApkProvider.uriFor(this, f.getName()) : Uri.fromFile(f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, APK_MIME);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("تم التحميل — افتح الإشعار علشان تثبّت التحديث");
        }
    }
}
