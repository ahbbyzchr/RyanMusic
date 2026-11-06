package cn.ryanmusic.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var overlay: View
    private lateinit var loading: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var fallbackPanel: LinearLayout
    private lateinit var remoteUrlInput: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnUseCloud: Button
    private lateinit var btnRetryLocal: Button

    private var baseUrl: String = "http://127.0.0.1:18765/"
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var safeTopDp = 0f
    private var safeBottomDp = 0f
    private var playbackService: PlaybackSessionService? = null
    private var playbackBound = false
    private var pendingPlayback: String? = null
    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as PlaybackSessionService.LocalBinder).service
            playbackService = service
            service.commandSink = { action, position -> dispatchMediaCommand(action, position) }
            pendingPlayback?.let { service.updatePlayback(it) }
            pendingPlayback = null
        }
        override fun onServiceDisconnected(name: ComponentName) { playbackService = null }
    }

    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileChooserCallback ?: return@registerForActivityResult
        pendingFileChooserCallback = null
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        overlay = findViewById(R.id.overlay)
        loading = findViewById(R.id.loading)
        statusText = findViewById(R.id.statusText)
        fallbackPanel = findViewById(R.id.fallbackPanel)
        remoteUrlInput = findViewById(R.id.remoteUrlInput)
        btnConnect = findViewById(R.id.btnConnect)
        btnUseCloud = findViewById(R.id.btnUseCloud)
        btnRetryLocal = findViewById(R.id.btnRetryLocal)

        remoteUrlInput.setText(prefs().getString(KEY_REMOTE, "") ?: "")
        btnConnect.setOnClickListener {
            connectRemote(remoteUrlInput.text?.toString().orEmpty(), PersistMode.CUSTOM)
        }
        btnUseCloud.setOnClickListener { connectCloud(PersistMode.CLOUD) }
        btnRetryLocal.setOnClickListener { startPreferredServer() }

        setupWebView()
        setupSafeAreaInsets()
        setupBackPress()
        migrateLegacyPrefs()
        startPreferredServer()
    }

    private fun setupSafeAreaInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // systemBars ∪ displayCutout（刘海屏顶部可能比状态栏更高）
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // insets 是物理像素，WebView 的 CSS px 等于 dp，需按 density 换算
            val density = resources.displayMetrics.density
            safeTopDp = bars.top / density
            safeBottomDp = bars.bottom / density
            injectSafeAreaInsets()
            insets
        }
    }

    private fun injectSafeAreaInsets() {
        if (this::webView.isInitialized && webView.visibility == View.VISIBLE) {
            val top = String.format(Locale.US, "%.1f", safeTopDp)
            val bottom = String.format(Locale.US, "%.1f", safeBottomDp)
            val script =
                "document.documentElement.style.setProperty('--safe-top', '${top}px');" +
                    "document.documentElement.style.setProperty('--safe-bottom', '${bottom}px');"
            webView.evaluateJavascript(script, null)
        }
    }

    private fun prefs() = getSharedPreferences("ryanmusic", Context.MODE_PRIVATE)

    private fun migrateLegacyPrefs() {
        if (prefs().contains(KEY_MODE)) return
        val useRemote = prefs().getBoolean(KEY_USE_REMOTE_LEGACY, false)
        val remote = prefs().getString(KEY_REMOTE, "").orEmpty().trim()
        if (useRemote && remote.isNotEmpty()) {
            prefs().edit().putString(KEY_MODE, MODE_CUSTOM).apply()
        }
    }

    private fun startPreferredServer() {
        // 方案 D：优先上次成功的自定义地址；没有记录才连在线服务（备用）
        val saved = prefs().getString(KEY_REMOTE, "").orEmpty().trim()
        if (saved.isNotEmpty()) {
            connectRemote(saved, PersistMode.CUSTOM)
        } else {
            connectCloud(PersistMode.CLOUD)
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (this@MainActivity::webView.isInitialized &&
                    webView.visibility == View.VISIBLE &&
                    webView.canGoBack()
                ) {
                    webView.goBack()
                    return
                }
                if (fallbackPanel.visibility != View.VISIBLE) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.app_name)
                        .setItems(
                            arrayOf(
                                getString(R.string.menu_use_cloud),
                                getString(R.string.menu_change_server),
                                getString(R.string.menu_exit),
                            ),
                        ) { _, which ->
                            when (which) {
                                0 -> connectCloud(PersistMode.CLOUD)
                                1 -> showServerPicker()
                                2 -> finish()
                            }
                        }
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showServerPicker() {
        stopPlaybackSession()
        webView.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        loading.visibility = View.GONE
        fallbackPanel.visibility = View.VISIBLE
        statusText.setText(R.string.fallback_title)
    }

    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(false)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 RyanMusicAndroid/${BuildConfig.VERSION_NAME}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = false
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.addJavascriptInterface(
            NativeBridge(this, { baseUrl }) { payload -> runOnUiThread { receivePlayback(payload) } },
            "AndroidBridge",
        )
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                // 上一个选择器还没返回：直接拒绝新请求，保留旧回调等它回来
                if (pendingFileChooserCallback != null) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                pendingFileChooserCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: Exception) {
                    pendingFileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    Toast.makeText(this@MainActivity, R.string.file_chooser_missing, Toast.LENGTH_SHORT).show()
                    // 已自行回调过 null，返回 true 避免 WebView 再回调一次
                    true
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                injectNativeBridge()
                overlay.visibility = View.GONE
                loading.visibility = View.GONE
                fallbackPanel.visibility = View.GONE
                webView.visibility = View.VISIBLE
                injectSafeAreaInsets()
            }
        }
    }

    private fun injectNativeBridge() {
        val script = """
            (function(){
              document.documentElement.classList.add('platform-android-app');
              if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.ryanSave) return;
              window.webkit = window.webkit || {};
              window.webkit.messageHandlers = window.webkit.messageHandlers || {};
              window.webkit.messageHandlers.ryanSave = {
                postMessage: function(payload) {
                  try {
                    var json = (typeof payload === 'string') ? payload : JSON.stringify(payload);
                    AndroidBridge.save(json);
                  } catch (e) {}
                }
              };
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun connectCloud(persist: PersistMode) {
        connectRemote(BuildConfig.CLOUD_ORIGIN, persist, connectingLabel = R.string.connecting_cloud)
    }

    /**
     * 连通性探测：先 GET /health（与后端 B1 对齐，返回 JSON），非 2xx 再退回根路径，
     * 兼容未部署 /health 的旧桌面版；云端 Vercel 两个路径都会被兜住。
     */
    private fun probeServer(base: String, timeoutMs: Int) {
        var lastCode = -1
        for (path in PROBE_PATHS) {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            try {
                lastCode = conn.responseCode
            } finally {
                conn.disconnect()
            }
            if (lastCode in 200..399) return
        }
        throw IllegalStateException("HTTP $lastCode")
    }

    private fun connectRemote(
        raw: String,
        persist: PersistMode,
        connectingLabel: Int = R.string.connecting_remote,
    ) {
        var url = raw.trim()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.hint_remote_url, Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) url += "/"

        overlay.visibility = View.VISIBLE
        loading.visibility = View.VISIBLE
        fallbackPanel.visibility = View.GONE
        webView.visibility = View.GONE
        statusText.setText(connectingLabel)

        val timeoutMs = if (url.startsWith("https://", ignoreCase = true)) 15_000 else 5_000
        thread(name = "ryan-remote") {
            try {
                probeServer(url, timeoutMs)
                baseUrl = url
                val editor = prefs().edit()
                when (persist) {
                    PersistMode.CLOUD -> editor.putString(KEY_MODE, MODE_CLOUD)
                    PersistMode.CUSTOM -> editor
                        .putString(KEY_MODE, MODE_CUSTOM)
                        .putString(KEY_REMOTE, url)
                    PersistMode.NONE -> { /* 自动回退不改下次启动偏好 */ }
                }
                editor.apply()
                runOnUiThread { webView.loadUrl(baseUrl) }
            } catch (e: Exception) {
                runOnUiThread {
                    overlay.visibility = View.VISIBLE
                    loading.visibility = View.GONE
                    fallbackPanel.visibility = View.VISIBLE
                    statusText.text = getString(R.string.remote_failed, e.message ?: "unknown")
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun receivePlayback(payload: String) {
        val data = try { JSONObject(payload) } catch (_: Exception) { return }
        val state = data.optString("state")
        if (state !in setOf("playing", "paused", "buffering", "stopped")) return
        val pausedWithoutTrack = state == "paused" && data.optString("title", "").isBlank() &&
            data.optString("artist", "").isBlank() && data.optLong("durationMs", 0) <= 0
        if (state == "stopped" || pausedWithoutTrack) {
            val stopped = "{\"state\":\"stopped\"}"
            pendingPlayback = stopped
            playbackService?.updatePlayback(stopped)
            return
        }
        pendingPlayback = payload
        if (playbackService != null) {
            playbackService?.updatePlayback(payload)
            pendingPlayback = null
        } else if (!playbackBound) {
            val intent = Intent(this, PlaybackSessionService::class.java).setAction(PlaybackSessionService.ACTION_LOCAL_BIND)
            playbackBound = bindService(intent, playbackConnection, Context.BIND_AUTO_CREATE)
        }
        if ((state == "playing" || state == "buffering") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs().getBoolean("notification_permission_requested", false)
        ) {
            prefs().edit().putBoolean("notification_permission_requested", true).apply()
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun dispatchMediaCommand(action: String, position: Long?) {
        val command = JSONObject().put("action", action)
        if (position != null) command.put("positionMs", position)
        webView.post {
            if (!isFinishing && !isDestroyed) {
                webView.evaluateJavascript("window.__ryanNativeMediaCommand && window.__ryanNativeMediaCommand(${command});", null)
            }
        }
    }

    private fun stopPlaybackSession() {
        pendingPlayback = null
        playbackService?.updatePlayback("{\"state\":\"stopped\"}")
        playbackService?.commandSink = null
        if (playbackBound) unbindService(playbackConnection)
        playbackBound = false
        playbackService = null
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) {
            webView.onResume()
        }
    }

    override fun onPause() {
        // 不调用 webView.onPause()，避免切到后台后歌词动画和音频被挂起
        super.onPause()
    }

    override fun onDestroy() {
        stopPlaybackSession()
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
        try {
            CookieManager.getInstance().flush()
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private enum class PersistMode {
        NONE,
        CLOUD,
        CUSTOM,
    }

    companion object {
        private val PROBE_PATHS = arrayOf("health", "health/", "")
        private const val KEY_REMOTE = "remote_url"
        private const val KEY_USE_REMOTE_LEGACY = "use_remote"
        private const val KEY_MODE = "server_mode"
        private const val MODE_CLOUD = "cloud"
        private const val MODE_CUSTOM = "custom"
    }
}
