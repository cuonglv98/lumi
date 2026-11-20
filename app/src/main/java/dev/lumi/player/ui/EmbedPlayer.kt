package dev.lumi.player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lumi.player.player.EmbedPlayback

/**
 * Trình phát NHÚNG chính thức của YouTube (youtube.com/embed) chạy trong WebView.
 *
 * Chỉ dùng cho video YouTube không cho app ngoài lấy luồng phát (vd video «dành cho trẻ em»):
 * YouTube vẫn cho phép nhúng phần lớn các video này. Đổi lại YouTube tự điều khiển trình phát
 * (tự chọn chất lượng, có thể chèn quảng cáo).
 *
 * Được đặt ở tầng ngoài cùng của app (MainActivity) để thu nhỏ màn xem vẫn nghe tiếp.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbedPlayer(
    videoId: String,
    title: String?,
    modifier: Modifier,
    onEnded: () -> Unit,
    fullscreen: Boolean,
    onFullscreenRequest: (Boolean) -> Unit,
) {
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnFullscreenRequest by rememberUpdatedState(onFullscreenRequest)

    var webView by remember { mutableStateOf<WebView?>(null) }

    // Lumi đổi toàn màn hình (nút ⛶ / vuốt quay lại) → báo cho YouTube để nút của nó đổi đúng biểu tượng
    LaunchedEffect(webView, fullscreen) {
        webView?.evaluateJavascript("window.lumiSetFullscreen && window.lumiSetFullscreen($fullscreen);", null)
    }

    LaunchedEffect(title) { EmbedPlayback.updateTitle(title) }

    // Báo cho trang biết app đang ở nền (tắt màn hình / chuyển app)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> webView?.evaluateJavascript("window.__lumiBg = true;", null)
                Lifecycle.Event.ON_START -> webView?.evaluateJavascript("window.__lumiBg = false;", null)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            BackgroundWebView(context).apply {
                // Compose mặc định tạo View kiểu WRAP_CONTENT → WebView báo trang "chiều cao khung = 0"
                // → trình phát YouTube (cao 100%) bị thu về cao 0: có tiếng, có nút, nhưng hình đen.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false // tự phát ngay
                addJavascriptInterface(
                    EmbedBridge(
                        ended = { post { currentOnEnded() } },
                        fullscreen = { on -> post { currentOnFullscreenRequest(on) } },
                    ),
                    "Lumi",
                )
                // Bắt buộc với video HTML5: thiếu WebChromeClient thì WebView chỉ có tiếng, hình bị đen
                webChromeClient = object : WebChromeClient() {
                    // Ảnh chờ mặc định của WebView là nút play xám to → thay bằng ảnh trong suốt
                    override fun getDefaultVideoPoster(): Bitmap =
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

                    /**
                     * Dự phòng: nếu trang vẫn gọi được toàn màn hình gốc của WebView (tách video ra cửa sổ
                     * riêng) thì bỏ đi và dùng toàn màn hình của Lumi. Bình thường đoạn mã gài sẵn
                     * (INJECT_JS) đã bắt lệnh toàn màn hình từ trước.
                     */
                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                        callback.onCustomViewHidden()
                        post { currentOnFullscreenRequest(true) }
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        view.evaluateJavascript(INJECT_JS, null)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(INJECT_JS, null)
                    }
                }
                tag = videoId
                EmbedPlayback.attach(context, this, videoId, title)
                loadUrl(embedUrl(videoId), mapOf("Referer" to "$ORIGIN/"))
                webView = this
            }
        },
        update = { view ->
            // Chuyển sang video nhúng khác (vd bài tiếp theo trong hàng chờ) → dùng lại WebView
            if (view.tag != videoId) {
                view.tag = videoId
                EmbedPlayback.attach(view.context, view, videoId, title)
                view.loadUrl(embedUrl(videoId), mapOf("Referer" to "$ORIGIN/"))
            }
        },
        onRelease = { view ->
            EmbedPlayback.detach(view)
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        },
    )
}

/** YouTube yêu cầu trình phát nhúng trong app phải cho biết nguồn nhúng (Referer/origin). */
private const val ORIGIN = "https://dev.lumi.player"

private fun embedUrl(videoId: String) =
    "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&fs=1&enablejsapi=1&origin=$ORIGIN"

/** Cầu nối JavaScript → Kotlin. */
private class EmbedBridge(
    private val ended: () -> Unit,
    private val fullscreen: (Boolean) -> Unit,
) {
    @JavascriptInterface
    fun onEnded() = ended()

    /** Trình phát YouTube xin vào/thoát toàn màn hình (người dùng bấm nút của YouTube). */
    @JavascriptInterface
    fun fullscreen(on: Boolean) = fullscreen.invoke(on)

    @JavascriptInterface
    fun status(position: Double, duration: Double, paused: Boolean) =
        EmbedPlayback.onStatus(position, if (duration.isNaN()) 0.0 else duration, paused)

    @JavascriptInterface
    fun error(text: String) {
        Log.w("EmbedPlayer", "Trình phát nhúng báo lỗi: $text")
    }
}

/**
 * WebView "không bao giờ bị ẩn": khi tắt màn hình, Android báo cửa sổ bị ẩn → WebView treo
 * bộ phát video. Ta luôn báo là đang hiển thị để video (và tiếng) chạy tiếp.
 */
private class BackgroundWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

/**
 * - Giả vờ trang luôn "đang hiển thị" để trình phát YouTube không tự dừng khi tắt màn hình.
 * - Khi app ở nền: chặn lệnh "dừng" do trang tự gọi (người dùng vẫn dừng được qua lumiToggle).
 * - Báo về Kotlin: vị trí phát, hết video, lỗi.
 */
private const val INJECT_JS = """
(function () {
  if (window.__lumi) return; window.__lumi = true;
  try {
    Object.defineProperty(document, 'hidden', { get: function () { return false; }, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
    ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'blur'].forEach(function (name) {
      window.addEventListener(name, function (e) { e.stopImmediatePropagation(); }, true);
    });
  } catch (e) {}
  var originalPause = HTMLMediaElement.prototype.pause;
  HTMLMediaElement.prototype.pause = function () {
    if (window.__lumiBg) return;
    return originalPause.apply(this, arguments);
  };
  // Toàn màn hình: Lumi tự làm (phóng WebView, xoay ngang). Trang chỉ cần "tưởng" là đang toàn màn hình
  // để trình phát YouTube đổi giao diện và biểu tượng nút cho đúng.
  var fsElement = null;
  function setFs(el) {
    fsElement = el;
    ['fullscreenchange', 'webkitfullscreenchange'].forEach(function (n) { document.dispatchEvent(new Event(n)); });
  }
  try {
    ['fullscreenElement', 'webkitFullscreenElement', 'webkitCurrentFullScreenElement'].forEach(function (n) {
      Object.defineProperty(document, n, { get: function () { return fsElement; }, configurable: true });
    });
    Object.defineProperty(document, 'webkitIsFullScreen', { get: function () { return !!fsElement; }, configurable: true });
    ['fullscreenEnabled', 'webkitFullscreenEnabled'].forEach(function (n) {
      Object.defineProperty(document, n, { get: function () { return true; }, configurable: true });
    });
  } catch (e) {}
  Element.prototype.requestFullscreen = Element.prototype.webkitRequestFullscreen =
    Element.prototype.webkitRequestFullScreen = function () { setFs(this); Lumi.fullscreen(true); return Promise.resolve(); };
  document.exitFullscreen = document.webkitExitFullscreen = document.webkitCancelFullScreen =
    function () { setFs(null); Lumi.fullscreen(false); return Promise.resolve(); };
  window.lumiSetFullscreen = function (on) {
    if (on === !!fsElement) return;
    setFs(on ? (document.getElementById('movie_player') || document.documentElement) : null);
  };
  window.lumiToggle = function () {
    var v = document.querySelector('video');
    if (!v) return;
    if (v.paused) v.play(); else originalPause.call(v);
  };
  var hooked = false;
  setInterval(function () {
    var v = document.querySelector('video');
    if (v && !hooked) {
      hooked = true;
      v.addEventListener('ended', function () { Lumi.onEnded(); });
    }
    if (v) Lumi.status(v.currentTime, v.duration, v.paused);
    var err = document.querySelector('.ytp-error');
    if (err && err.offsetParent !== null) Lumi.error(err.innerText);
  }, 1000);
})();
"""
