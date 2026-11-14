package id.co.sumberbeton.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String RELEASE_API = "https://api.github.com/repos/azix25/Sb/releases/latest";
    private static final String PREFS = "sb_updater";
    private static final String KEY_DOWNLOAD_ID = "pending_download_id";
    private static final String APK_NAME = "SUMBERBETON-update.apk";

    private WebView webView;
    private final ExecutorService updaterExecutor = Executors.newSingleThreadExecutor();
    private DownloadManager downloadManager;
    private long activeDownloadId = -1L;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (id != activeDownloadId) return;
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
            try (android.database.Cursor cursor = downloadManager.query(query)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        getPreferences(MODE_PRIVATE).edit().putLong(KEY_DOWNLOAD_ID, id).apply();
                        Toast.makeText(MainActivity.this, "Pembaruan selesai diunduh", Toast.LENGTH_LONG).show();
                        installDownloadedUpdate();
                    } else {
                        Toast.makeText(MainActivity.this, "Unduhan pembaruan gagal", Toast.LENGTH_LONG).show();
                    }
                }
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Gagal memproses pembaruan", Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }

        webView = new WebView(this);
        setContentView(webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setLoadsImagesAutomatically(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectUpdateButton();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new AppBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");

        webView.postDelayed(() -> checkForUpdates(false), 2500);
    }

    private void injectUpdateButton() {
        String script = "(function(){if(document.getElementById('sb-app-update-button'))return;" +
                "var b=document.createElement('button');b.id='sb-app-update-button';b.type='button';" +
                "b.textContent='↻ Update';" +
                "b.setAttribute('aria-label','Periksa pembaruan aplikasi');" +
                "b.style.cssText='position:fixed;left:12px;bottom:12px;z-index:2147483647;border:0;border-radius:22px;padding:9px 13px;background:#202b25;color:#fff;font:600 12px Arial,sans-serif;box-shadow:0 2px 8px #0005;opacity:.92;';" +
                "b.onclick=function(){if(window.Android&&Android.checkForUpdates)Android.checkForUpdates();};" +
                "document.body.appendChild(b);})();";
        webView.evaluateJavascript(script, null);
    }

    private class AppBridge {
        @JavascriptInterface
        public void checkForUpdates() {
            runOnUiThread(() -> MainActivity.this.checkForUpdates(true));
        }

        @JavascriptInterface
        public void savePdf(String dataUri, String fileName) {
            runOnUiThread(() -> {
                try {
                    String base64 = dataUri.substring(dataUri.indexOf(",") + 1);
                    byte[] pdf = Base64.decode(base64, Base64.DEFAULT);
                    String safeName = fileName == null ? "Surat-Penawaran-SUMBERBETON.pdf" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new Exception("Tidak dapat membuat file PDF");
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new Exception("Tidak dapat menyimpan file PDF");
                        out.write(pdf);
                    }
                    Toast.makeText(MainActivity.this, "PDF tersimpan di folder Download", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Gagal menyimpan PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void checkForUpdates(boolean manual) {
        if (manual) Toast.makeText(this, "Memeriksa pembaruan...", Toast.LENGTH_SHORT).show();
        updaterExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "SUMBERBETON-Android-Updater");
                int response = connection.getResponseCode();
                if (response != HttpURLConnection.HTTP_OK) throw new Exception("Server pembaruan tidak dapat diakses");
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }

                JSONObject release = new JSONObject(body.toString());
                String tag = release.optString("tag_name", "").trim();
                String latestVersion = tag.replaceFirst("^[vV]", "");
                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                if (latestVersion.isEmpty() || apkUrl == null || !apkUrl.startsWith("https://")) {
                    throw new Exception("Rilis belum memiliki file APK");
                }

                final String version = latestVersion;
                final String url = apkUrl;
                runOnUiThread(() -> {
                    if (compareVersions(version, BuildConfig.VERSION_NAME) > 0) {
                        showUpdateDialog(version, url);
                    } else if (manual) {
                        new AlertDialog.Builder(this)
                                .setTitle("Aplikasi sudah terbaru")
                                .setMessage("SUMBERBETON versi " + BuildConfig.VERSION_NAME + " sudah menggunakan versi terbaru.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            } catch (Exception e) {
                if (manual) runOnUiThread(() ->
                        Toast.makeText(this, "Tidak dapat memeriksa pembaruan. Coba lagi nanti.", Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private int compareVersions(String first, String second) {
        String[] a = first.split("[.-]");
        String[] b = second.split("[.-]");
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int x = i < a.length ? parseVersionPart(a[i]) : 0;
            int y = i < b.length ? parseVersionPart(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private int parseVersionPart(String part) {
        String digits = part.replaceAll("[^0-9].*$", "");
        try { return digits.isEmpty() ? 0 : Integer.parseInt(digits); }
        catch (NumberFormatException e) { return 0; }
    }

    private void showUpdateDialog(String version, String apkUrl) {
        new AlertDialog.Builder(this)
                .setTitle("Pembaruan tersedia")
                .setMessage("Versi baru " + version + " tersedia. Unduh dan pasang sekarang?")
                .setNegativeButton("Nanti", null)
                .setPositiveButton("Update Sekarang", (dialog, which) -> downloadUpdate(apkUrl))
                .show();
    }

    private void downloadUpdate(String apkUrl) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("Pembaruan SUMBERBETON");
            request.setDescription("Mengunduh versi terbaru aplikasi");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_NAME);
            activeDownloadId = downloadManager.enqueue(request);
            Toast.makeText(this, "Pembaruan sedang diunduh", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat memulai unduhan pembaruan", Toast.LENGTH_LONG).show();
        }
    }

    private void installDownloadedUpdate() {
        long id = getPreferences(MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -1L);
        if (id < 0) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Izinkan pemasangan")
                    .setMessage("Izinkan SUMBERBETON memasang pembaruan dari file yang diunduh. Android akan meminta konfirmasi.")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Lanjutkan", (dialog, which) -> {
                        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(settings);
                    })
                    .show();
            return;
        }
        Uri apkUri = downloadManager.getUriForDownloadedFile(id);
        if (apkUri == null) {
            Toast.makeText(this, "File pembaruan tidak ditemukan", Toast.LENGTH_LONG).show();
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(apkUri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(install);
        } catch (Exception e) {
            Toast.makeText(this, "Android tidak dapat membuka pemasang aplikasi", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
            long pending = getPreferences(MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -1L);
            if (pending >= 0) installDownloadedUpdate();
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        updaterExecutor.shutdown();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
