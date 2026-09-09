package com.diario360.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val appUrl = "https://diario360.pcm360.online/"

    inner class NativeBridge {

        @JavascriptInterface
        fun scheduleDailyReminders(morning: String, evening: String) {
            runOnUiThread {
                requestNotificationPermissionIfNeeded()
                scheduleReminder(
                    morning, 1001,
                    "Reflexão estoica do dia",
                    "Seu Mentor Estoico está esperando por você."
                )
                scheduleReminder(
                    evening, 1002,
                    "Revisão do dia",
                    "Como você viveu os princípios de hoje?"
                )
            }
        }

        @JavascriptInterface
        fun saveBase64(dataUrl: String, fileName: String, mimeType: String) {
            try {
                val cleanMime = mimeType.ifBlank { mimeFromDataUrl(dataUrl) }
                val bytes = decodeDataUrl(dataUrl)
                saveToDownloads(bytes, sanitizeFileName(fileName, cleanMime), cleanMime)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Não foi possível salvar o arquivo.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun shareBase64(dataUrl: String, fileName: String, mimeType: String, text: String) {
            try {
                val cleanMime = mimeType.ifBlank { mimeFromDataUrl(dataUrl) }
                val bytes = decodeDataUrl(dataUrl)
                val safeName = sanitizeFileName(fileName, cleanMime)

                val shareDir = File(cacheDir, "shared").apply { mkdirs() }
                val out = File(shareDir, safeName)
                out.writeBytes(bytes)

                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    out
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = cleanMime.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                runOnUiThread {
                    startActivity(Intent.createChooser(intent, "Compartilhar com"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Não foi possível compartilhar o arquivo.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannel(this)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        webView.addJavascriptInterface(NativeBridge(), "Diario360Native")
        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val host = url.host.orEmpty()

                return if (
                    host == "diario360.pcm360.online" ||
                    host.endsWith(".pcm360.online")
                ) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "Não foi possível abrir este link.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectNativeShareFallback()
            }
        }

        // HTTP/HTTPS downloads remain handled by Android.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        addRequestHeader("User-Agent", userAgent)
                        setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        val guessed = android.webkit.URLUtil.guessFileName(
                            url, contentDisposition, mimeType
                        )
                        setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            guessed
                        )
                    }
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    Toast.makeText(this, "Download iniciado.", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Não foi possível iniciar o download.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(appUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun injectNativeShareFallback() {
        val js = """
            (function(){
              if (window.__d360NativeBridgeInstalled) return;
              window.__d360NativeBridgeInstalled = true;

              function fileToDataUrl(file){
                return new Promise(function(resolve,reject){
                  var r = new FileReader();
                  r.onload = function(){ resolve(String(r.result || '')); };
                  r.onerror = reject;
                  r.readAsDataURL(file);
                });
              }

              async function nativeShare(data){
                data = data || {};
                var files = data.files || [];
                if(files.length && window.Diario360Native){
                  var f = files[0];
                  var dataUrl = await fileToDataUrl(f);
                  window.Diario360Native.shareBase64(
                    dataUrl,
                    f.name || 'diario360-compartilhamento',
                    f.type || 'application/octet-stream',
                    data.text || ''
                  );
                  return;
                }

                if(data.text && navigator.clipboard){
                  try{
                    await navigator.clipboard.writeText(data.text);
                    alert('Texto copiado. Selecione o aplicativo onde deseja compartilhar.');
                    return;
                  }catch(e){}
                }

                throw new Error('Compartilhamento nativo indisponível.');
              }

              try {
                Object.defineProperty(navigator, 'share', {
                  configurable: true,
                  value: nativeShare
                });
              } catch(e) {
                try { navigator.share = nativeShare; } catch(ignore) {}
              }

              try {
                Object.defineProperty(navigator, 'canShare', {
                  configurable: true,
                  value: function(){ return true; }
                });
              } catch(e) {
                try { navigator.canShare = function(){ return true; }; } catch(ignore) {}
              }

              document.addEventListener('click', async function(ev){
                var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
                if(!a || !a.href || !a.href.startsWith('blob:') || !window.Diario360Native) return;

                ev.preventDefault();
                ev.stopPropagation();

                try{
                  var response = await fetch(a.href);
                  var blob = await response.blob();
                  var file = new File(
                    [blob],
                    a.download || 'diario360-arquivo',
                    {type: blob.type || 'application/octet-stream'}
                  );
                  var dataUrl = await fileToDataUrl(file);
                  window.Diario360Native.saveBase64(
                    dataUrl,
                    file.name,
                    file.type
                  );
                }catch(err){
                  console.error('DIÁRIO 360 native download fallback:', err);
                }
              }, true);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun mimeFromDataUrl(dataUrl: String): String {
        val header = dataUrl.substringBefore(',')
        return header.substringAfter("data:", "")
            .substringBefore(';')
            .ifBlank { "application/octet-stream" }
    }

    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val base64 = dataUrl.substringAfter(',', "")
        require(base64.isNotBlank()) { "Data URL inválida" }
        return Base64.decode(base64, Base64.DEFAULT)
    }

    private fun sanitizeFileName(fileName: String, mimeType: String): String {
        var name = fileName.ifBlank { "diario360-arquivo" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")

        if (!name.contains('.')) {
            val extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                .orEmpty()
            if (extension.isNotBlank()) name += ".$extension"
        }
        return name
    }

    private fun saveToDownloads(bytes: ByteArray, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException("Não foi possível criar o arquivo.")

            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Não foi possível gravar o arquivo.")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeBytes(bytes)
        }

        runOnUiThread {
            Toast.makeText(
                this,
                "Arquivo salvo em Downloads.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                440
            )
        }
    }

    private fun scheduleReminder(
        time: String,
        requestCode: Int,
        title: String,
        text: String
    ) {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("title", title)
            putExtra("text", text)
            putExtra("requestCode", requestCode)
        }

        val pi = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pi
        )
    }
}
