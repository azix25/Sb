package id.co.sumberbeton.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.Toast;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setLoadsImagesAutomatically(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new PdfBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private class PdfBridge {
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

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
