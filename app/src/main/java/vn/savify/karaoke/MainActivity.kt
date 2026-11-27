package vn.savify.karaoke

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class CardTag(val hit: SearchHit, val index: Int)
private class QueueTag(val item: QueueItem)

/**
 * Youtube Player - giao dien TV 3 cot: [QR + the loai] [tim kiem + luoi ket qua] [dang phat + hang doi].
 * Man hinh phat nho o goc phai, tu phong to toan man hinh sau 5 giay khong bam phim remote TV,
 * bam bat ky phim nao tren remote TV -> thu nho ngay. Lenh tu dien thoai KHONG lam thu nho.
 */
@Suppress("DEPRECATION")
class MainActivity : Activity(), IframeHost {

    private companion object {
        const val RED = 0xFFFF1744.toInt()
        const val CYAN = 0xFF29B6F6.toInt()
        const val PINK = 0xFFFF4D8D.toInt()
        const val GREEN = 0xFF2ECC71.toInt()
        const val REQ_VOICE = 77
        const val REQ_PICK_FILES = 78
        const val REQ_PICK_PLAY = 79
        const val REQ_PICK_TREE = 80
        const val QUEUE_W = 456
        const val TEAL = 0xFF26C6DA.toInt()
        var resumeFav = false
    }


    // ---- giao dien sang / toi ----
    private var dark = true
    private var BG = 0
    private var PANEL = 0
    private var PANEL2 = 0
    private var BORDER = 0
    private var MUTED = 0
    private var TXT = 0
    private var TXT2 = 0
    private var BTN = 0
    private var BTN_BORDER = 0
    private var BTN2 = 0
    private var THUMB = 0
    private var FOCUS = 0
    private var FOCUS2 = 0
    private var FOCUS_CARD = 0

    private fun applyTheme() {
        if (dark) {
            BG = 0xFF08080C.toInt(); PANEL = 0xFF111119.toInt(); PANEL2 = 0xFF1A1A26.toInt()
            BORDER = 0xFF262634.toInt(); MUTED = 0xFF9A9AB0.toInt()
            TXT = Color.WHITE; TXT2 = 0xFFD6D6E2.toInt()
            BTN = 0xFF2A2A38.toInt(); BTN_BORDER = 0xFF44445A.toInt(); BTN2 = 0xFF26263A.toInt()
            THUMB = 0xFF1B1B26.toInt(); FOCUS = 0x22FFFFFF; FOCUS2 = 0x33FFFFFF; FOCUS_CARD = 0xFF1A2230.toInt()
        } else {
            BG = 0xFFF1F3F8.toInt(); PANEL = 0xFFFFFFFF.toInt(); PANEL2 = 0xFFE9ECF3.toInt()
            BORDER = 0xFFCDD2DF.toInt(); MUTED = 0xFF5B6074.toInt()
            TXT = 0xFF14141C.toInt(); TXT2 = 0xFF2B2E3D.toInt()
            BTN = 0xFFE3E6EE.toInt(); BTN_BORDER = 0xFFB9BFCF.toInt(); BTN2 = 0xFFD5D9E5.toInt()
            THUMB = 0xFFD9DCE6.toInt(); FOCUS = 0x22000000; FOCUS2 = 0x33000000; FOCUS_CARD = 0xFFDCEBFA.toInt()
        }
    }

    /** Doi giao dien sang <-> toi: luu lai roi dung lai man hinh (video van phat vi nam trong service). */
    private fun toggleTheme() {
        Settings(this).darkUi = !dark
        resumeFav = favMode
        recreate()
    }

    /** Bam logo: thoat Yeu thich / danh sach phat / hop thoai, ve trang tim kiem cu. */
    private fun goHome() {
        val c = core ?: return
        listOf(dlDialog, localDlg, catDialog).forEach { try { if (it?.isShowing == true) it.dismiss() } catch (e: Exception) {} }
        val sv = c.controller.search.value
        val back = plReturn
        if (favMode) {
            leaveFavMode()
            syncCatToQuery(sv.query)
            renderSearch(sv)
        } else if (SearchService.isSubQuery(sv.query) && back != null) {
            plReturn = null
            tvSearch(back)
        }
        if (cardViews.isNotEmpty() && !fullscreen) cardViews[0].post { cardViews[0].requestFocus() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var core: Core? = null
    private var coreJobs = ArrayList<Job>()
    private var searchJob: Job? = null

    // ---- views ----
    private lateinit var root: FrameLayout
    private lateinit var dash: LinearLayout
    private lateinit var videoBox: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var slot: View
    private lateinit var idle: TextView
    private lateinit var resTag: TextView
    private lateinit var nowBadge: TextView
    private lateinit var fsBtn: View
    private lateinit var react: ReactionView
    private lateinit var msgView: MessageView
    private lateinit var volBox: LinearLayout
    private lateinit var volText: TextView
    private lateinit var volBar: android.widget.ProgressBar
    private val volHide = Runnable { volBox.visibility = View.GONE }

    private lateinit var remoteDot: IconView
    private lateinit var remoteText: TextView
    private lateinit var wifiIcon: IconView
    private lateinit var wifiText: TextView
    private lateinit var clock: TextView
    private lateinit var portPill: TextView
    private lateinit var qrImg: ImageView
    private lateinit var qrCard: LinearLayout
    private lateinit var dlBadge: TextView
    private lateinit var repeatBtn: View
    private lateinit var repeatIcon: IconView
    private lateinit var volSlider: SliderView
    private var dlDialog: AlertDialog? = null
    private var dlProgBox: LinearLayout? = null
    private var dlLibBox: LinearLayout? = null
    private var dlScopeBox: LinearLayout? = null
    private var dlLibTitle: TextView? = null
    private var dlFoot: TextView? = null
    private var dlFilter = ""
    private var dlLastCount = -1
    private var localDlg: AlertDialog? = null
    private var localItems: List<LocalItem> = emptyList()
    private var localFilter = ""
    private var localListBox: LinearLayout? = null
    private var localChipBox: LinearLayout? = null
    private var localAddAll: TextView? = null
    private var localStatus: TextView? = null
    private var pendingPlayIds: List<String> = emptyList()
    private lateinit var catBtnView: View
    private var catDialog: AlertDialog? = null
    private val queueBtns = ArrayList<ArrayList<View>>()
    private var pendingQFocus: Pair<Int, Int>? = null
    private var gridCols = 3
    private var centerWpx = 0
    private lateinit var qrCap: TextView
    private lateinit var qrUrl: TextView
    private lateinit var catBox: LinearLayout
    private lateinit var qualText: TextView
    private lateinit var searchEt: EditText
    private lateinit var searchField: LinearLayout
    private lateinit var karaokeBtn: LinearLayout
    private lateinit var karaokeLbl: TextView
    private lateinit var karaokeDot: View
    private lateinit var status: TextView
    private lateinit var gridBox: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var timeL: TextView
    private lateinit var timeR: TextView
    private lateinit var bar: BarView
    private lateinit var playIcon: IconView
    private lateinit var queueBox: LinearLayout
    private lateinit var queueCount: TextView
    private lateinit var queueTotal: TextView

    // ---- state ----
    private var sc = 1f
    private var cardW = 0
    private var web: WebView? = null
    private var webFailed = false
    private var pageReady = false
    /** Tu khoa tim kiem truoc khi mo mot playlist (bam BACK de quay lai). */
    private var plReturn: String? = null
    private var pendingJs: String? = null
    private var surfaceAttached = false
    private var inSession = false
    private var vw = 0
    private var vh = 0
    private var par = 1f
    private var lastUi: UiState = UiState()
    private var lastQrUrl: String? = null

    private var fullscreen = false
    private var lastControlAt = 0L
    private var savedFocus: View? = null
    private var dialogs = 0

    private val cats = ArrayList<Cat>()
    private val favs = ArrayList<SearchHit>()
    private var favMode = false
    private var favSearchQuery = ""
    private lateinit var favBtn: LinearLayout
    private lateinit var countdownTv: TextView
    private var voiceWasPlaying = false
    private var selCat = 0
    private var startedSearch = false

    private val cardViews = ArrayList<View>()
    private var shownQuery = ""
    private var shownCount = 0
    private var shownFirst: String? = null
    private var lastQueueSig = ""

    private fun p(v: Int): Int = (v * sc).toInt()
    private fun TextView.sz(v: Int) = setTextSize(TypedValue.COMPLEX_UNIT_PX, v * sc)

    private val videoListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            vw = videoSize.width
            vh = videoSize.height
            par = if (videoSize.pixelWidthHeightRatio > 0) videoSize.pixelWidthHeightRatio else 1f
            resTag.text = if (vh > 0) "${vh}p" else ""
            fitSurface()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = Settings(this).darkUi
        setTheme(if (dark) R.style.AppTheme else R.style.AppThemeLight)
        applyTheme()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastControlAt = SystemClock.elapsedRealtime()
        buildUi()
        setContentView(root)
        hideSystemBars()

        CrashLog.read(this)?.let { CrashLog.showScreen(this) }

        try {
            val i = Intent(this, KaraokeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Throwable) {
            CrashLog.write(this, "START_SERVICE", e)
            CrashLog.read(this)?.let { CrashLog.showScreen(this) }
        }
        scope.launch {
            delay(5000)
            if (core == null) CrashLog.read(this@MainActivity)?.let { CrashLog.showScreen(this@MainActivity) }
        }
        scope.launch {
            AppRuntime.core.collect { c ->
                detachCore()
                if (c != null) attachCore(c)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        core?.let {
            attachSurface(it)
            it.controller.attachIframeHost(this)
            it.controller.tvForeground()
        }
    }

    override fun onStop() {
        core?.let {
            it.controller.tvBackground()
            it.controller.attachIframeHost(null)
            detachSurface(it)
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        web?.onResume()
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        detachCore()
        scope.cancel()
        try {
            web?.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ------------------------------------------------------------------ attach / detach core

    private fun attachSurface(c: Core) {
        if (surfaceAttached) return
        c.controller.player.setVideoSurfaceView(surface)
        c.controller.player.addListener(videoListener)
        surfaceAttached = true
    }

    private fun detachSurface(c: Core) {
        if (!surfaceAttached) return
        c.controller.player.removeListener(videoListener)
        c.controller.player.clearVideoSurfaceView(surface)
        surfaceAttached = false
    }

    private fun attachCore(c: Core) {
        core = c
        attachSurface(c)
        c.controller.attachIframeHost(this)
        cats.clear()
        cats.addAll(Cats.load(c.settings.sp))
        favs.clear()
        favs.addAll(Favs.load(c.settings.sp))
        if (gridCols != c.settings.gridCols) applyGridCols(c.settings.gridCols)
        renderCats()
        if (resumeFav) { resumeFav = false; showFavorites() }
        coreJobs.add(scope.launch { c.controller.ui.collect { renderUi(it) } })
        coreJobs.add(scope.launch { c.controller.search.collect { renderSearch(it) } })
        coreJobs.add(scope.launch { c.controller.karaoke.collect { renderKaraokeBtn(it) } })
        coreJobs.add(scope.launch { c.controller.repeatMode.collect { renderRepeat(it) } })
        coreJobs.add(scope.launch { Downloads.changed.collect { onDownloadsChanged() } })
        coreJobs.add(scope.launch { c.controller.reactions.collect { react.fire(it) } })
        coreJobs.add(scope.launch { c.controller.messages.collect { msgView.show(it.first, it.second) } })
        coreJobs.add(scope.launch { c.controller.volumeEvents.collect { showVolume(it) } })
        coreJobs.add(scope.launch {
            c.controller.favsUpdated.collect {
                // Remote vua doi Yeu thich: nap lai tu bo nho TV
                favs.clear()
                favs.addAll(Favs.load(c.settings.sp))
                if (favMode) {
                    val fi = (currentFocus?.tag as? CardTag)?.index ?: 0
                    renderFavs(fi)
                } else refreshHearts()
            }
        })
        coreJobs.add(scope.launch {
            c.controller.catsChanged.collect {
                // Remote vua doi danh muc: nap lai, giu nguyen the loai dang chon (neu con)
                val curQ = cats.getOrNull(selCat)?.q
                cats.clear()
                cats.addAll(Cats.load(c.settings.sp))
                selCat = if (curQ == null) -1 else cats.indexOfFirst { it.q == curQ }
                renderCats()
            }
        })
        coreJobs.add(scope.launch {
            while (true) {
                autoFullscreenCheck(c)
                delay(200)
            }
        })
        coreJobs.add(scope.launch {
            while (true) {
                tick(c)
                delay(1000)
            }
        })
        // Tu tim the loai dau tien de man hinh co noi dung ngay khi mo app
        if (!startedSearch && c.controller.search.value.query.isEmpty() && cats.isNotEmpty()) {
            startedSearch = true
            selectCat(0)
        }
    }

    private fun detachCore() {
        coreJobs.forEach { it.cancel() }
        coreJobs.clear()
        searchJob?.cancel()
        core?.let {
            try {
                detachSurface(it)
                it.controller.attachIframeHost(null)
            } catch (e: Exception) {
            }
        }
        core = null
    }

    // ------------------------------------------------------------------ UI helpers

    private fun lin(
        w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(w, h, weight)
        lp.setMargins(p(l), p(t), p(r), p(b))
        return lp
    }

    private fun box(fill: Int, radius: Int, strokeW: Int = 0, strokeC: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = p(radius).toFloat()
        if (strokeW > 0) g.setStroke(maxOf(1, p(strokeW)), strokeC)
        return g
    }

    private fun focusable(v: View, normal: Drawable, focused: Drawable) {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), focused)
        sl.addState(intArrayOf(), normal)
        v.background = sl
        v.isFocusable = true
        v.isFocusableInTouchMode = false
    }

    private fun lbl(text: String, size: Int, color: Int = TXT, bold: Boolean = false, lines: Int = 0): TextView {
        val t = TextView(this)
        t.text = text
        t.sz(size)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        if (lines > 0) {
            t.setSingleLine(lines == 1)
            t.maxLines = lines
            t.ellipsize = TextUtils.TruncateAt.END
        }
        return t
    }

    private fun spacer(): View = View(this)

    private fun mmss(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun calcCardW(): Int {
        val n = gridCols.coerceIn(1, 4)
        return (centerWpx - p(14 * (n - 1)) - (if (n >= 3) 0 else p(8))) / n
    }

    private fun track(b: AlertDialog.Builder): AlertDialog {
        val d = b.create()
        dialogs++
        d.setOnDismissListener { dialogs-- }
        d.show()
        return d
    }

    // ------------------------------------------------------------------ UI construction

    private fun buildUi() {
        val dm = resources.displayMetrics
        sc = (dm.widthPixels / 1920f).coerceIn(0.4f, 2.5f)
        val leftW = dm.widthPixels - p(64 + 520 + 16)
        centerWpx = leftW - p(QUEUE_W + 16)
        cardW = calcCardW()

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        dash = LinearLayout(this)
        dash.orientation = LinearLayout.VERTICAL
        dash.setBackgroundColor(BG)
        dash.setPadding(p(32), p(16), p(32), p(24))
        root.addView(dash, FrameLayout.LayoutParams(-1, -1))
        buildQrCardOnce()
        dash.addView(buildTopBar(), lin(-1, p(76)))
        val body = LinearLayout(this)
        body.orientation = LinearLayout.HORIZONTAL
        dash.addView(body, lin(-1, 0, 1f, t = 8))
        body.addView(buildCenter(), lin(0, -1, 1f))
        body.addView(buildRight(), lin(p(520), -1, l = 16))

        buildVideoBox()

        react = ReactionView(this)
        root.addView(react, FrameLayout.LayoutParams(-1, -1))
        msgView = MessageView(this)
        root.addView(msgView, FrameLayout.LayoutParams(-1, -1))

        volBox = LinearLayout(this)
        volBox.orientation = LinearLayout.VERTICAL
        volBox.gravity = Gravity.CENTER_HORIZONTAL
        volBox.background = box(0xB3000000.toInt(), 16)
        volBox.setPadding(p(32), p(16), p(32), p(20))
        volText = lbl("", 28, Color.WHITE, true)
        volBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        volBar.max = 100
        volBar.progressTintList = android.content.res.ColorStateList.valueOf(RED)
        volBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66FFFFFF)
        volBox.addView(volText, LinearLayout.LayoutParams(-2, -2))
        volBox.addView(volBar, lin(p(360), p(10), t = 10))
        volBox.visibility = View.GONE
        val vlp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        vlp.bottomMargin = (dm.heightPixels * 0.15f).toInt()
        root.addView(volBox, vlp)

        videoBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        slot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoBox() }
    }


    private fun circleBtn(icon: String, bg: Int, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        focusable(f, box(bg, 28), box(bg, 28, 4, CYAN))
        f.addView(IconView(this, icon, Color.WHITE), FrameLayout.LayoutParams(p(28), p(28), Gravity.CENTER))
        f.setOnClickListener { onClick() }
        return f
    }

    private fun squareBtn(icon: String, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        focusable(f, box(PANEL, 18, 2, BORDER), box(FOCUS, 18, 3, CYAN))
        f.addView(IconView(this, icon, TXT), FrameLayout.LayoutParams(p(28), p(28), Gravity.CENTER))
        f.setOnClickListener { onClick() }
        return f
    }

    private fun buildTopBar(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        // Thu nho anh nhieu buoc (chia doi dan) de vien min, khong bi rang cua nhu khi thu nho 1 lan
        val logo = ImageView(this)
        val raw = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.logo_mark)
        var lw = p(72)
        var lh = lw * raw.height / raw.width
        if (lh > p(54)) { lh = p(54); lw = lh * raw.width / raw.height }
        var cur = raw
        while (cur.width / 2 >= lw && cur.height / 2 >= lh) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        logo.setImageBitmap(Bitmap.createScaledBitmap(cur, lw, lh, true))
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        logo.setPadding(p(4), p(4), p(4), p(4))
        focusable(logo, box(Color.TRANSPARENT, 16), box(FOCUS, 16, 3, CYAN))
        logo.contentDescription = "Trang tìm kiếm"
        logo.setOnClickListener { goHome() }
        row.addView(logo, lin(lw + p(8), lh + p(8)))
        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.addView(lbl("YOUTUBE PLAYER", 36, TXT, true), lin(-2, -2))
        titleCol.addView(lbl("Điều khiển bằng điện thoại", 17, MUTED), lin(-2, -2))
        row.addView(titleCol, lin(-2, -2, l = 14))
        val tag = lbl("MUSIC TV", 18, Color.WHITE, true)
        tag.background = box(RED, 8)
        tag.setPadding(p(12), p(4), p(12), p(4))
        row.addView(tag, lin(-2, -2, l = 14))

        remoteDot = IconView(this, "dot", 0xFF666677.toInt())
        row.addView(remoteDot, lin(p(26), p(26), l = 36, r = 6))
        remoteText = lbl("Chờ điện thoại kết nối", 22, MUTED)
        row.addView(remoteText, lin(-2, -2))

        // Nut yeu thich / thu vien da tai / tai xuong
        favBtn = LinearLayout(this)
        favBtn.orientation = LinearLayout.HORIZONTAL
        favBtn.gravity = Gravity.CENTER
        favBtn.addView(IconView(this, "heart", Color.WHITE), lin(p(28), p(28)))
        favBtn.setOnClickListener { showFavorites() }
        updateFavBtn()
        row.addView(favBtn, lin(p(56), p(56), l = 28))
        row.addView(circleBtn("folder", 0xFF2E7D32.toInt()) { showLocal() }, lin(p(56), p(56), l = 12))
        val dlWrap = circleBtn("download", 0xFF00897B.toInt()) { showDownloads() }
        dlBadge = lbl("0", 14, Color.WHITE, true)
        dlBadge.gravity = Gravity.CENTER
        dlBadge.background = box(RED, 11)
        dlBadge.visibility = View.GONE
        dlWrap.addView(dlBadge, FrameLayout.LayoutParams(p(22), p(22), Gravity.TOP or Gravity.END))
        row.addView(dlWrap, lin(p(56), p(56), l = 12))

        row.addView(spacer(), lin(0, 1, 1f))

        row.addView(squareBtn("qr") { showQr() }, lin(p(56), p(56), r = 12))
        row.addView(squareBtn("gear") { showSettings() }, lin(p(56), p(56), r = 12))
        row.addView(squareBtn("contrast") { toggleTheme() }, lin(p(56), p(56), r = 18))

        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.background = box(PANEL, 28, 2, BORDER)
        chip.setPadding(p(18), p(8), p(18), p(8))
        wifiIcon = IconView(this, "wifi", TXT)
        chip.addView(wifiIcon, lin(p(26), p(26)))
        wifiText = lbl("", 20, TXT)
        chip.addView(wifiText, lin(-2, -2, l = 8))
        row.addView(chip, lin(-2, p(56), r = 28))

        clock = lbl("", 30, TXT, true)
        row.addView(clock, lin(-2, -2))
        return row
    }



    /** The QR card (shown in a dialog from the QR button in the header). Views are updated by updateQr() every second. */
    private fun buildQrCard(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(lbl("QUÉT KẾT NỐI REMOTE", 24, TXT, true), lin(0, -2, 1f))
        portPill = lbl("", 16, GREEN, true)
        portPill.background = box(0x222ECC71, 20, 2, GREEN)
        portPill.setPadding(p(10), p(6), p(10), p(6))
        head.addView(portPill, lin(-2, -2))
        card.addView(head, lin(-1, -2))
        qrImg = ImageView(this)
        qrImg.scaleType = ImageView.ScaleType.FIT_XY
        qrImg.background = box(Color.WHITE, 16)
        val qlp = lin(p(300), p(300), t = 16)
        qlp.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(qrImg, qlp)
        qrCap = lbl("Mở camera quét để điều khiển", 20, TXT, true, 1)
        qrCap.gravity = Gravity.CENTER
        card.addView(qrCap, lin(-1, -2, t = 14))
        qrUrl = lbl("", 17, MUTED, false, 1)
        qrUrl.gravity = Gravity.CENTER
        card.addView(qrUrl, lin(-1, -2, t = 2))
        return card
    }






    private fun buildCenter(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        catBox = LinearLayout(this)
        catBox.orientation = LinearLayout.VERTICAL

        val barRow = LinearLayout(this)
        barRow.orientation = LinearLayout.HORIZONTAL
        barRow.gravity = Gravity.CENTER_VERTICAL

        val mic = FrameLayout(this)
        focusable(mic, box(RED, 34, 2, 0x55FFFFFF), box(RED, 34, 4, CYAN))
        val micIc = IconView(this, "mic", TXT)
        mic.addView(micIc, FrameLayout.LayoutParams(p(30), p(30), Gravity.CENTER))
        mic.setOnClickListener { startVoice() }
        barRow.addView(mic, lin(p(68), p(68)))

        searchField = LinearLayout(this)
        searchField.orientation = LinearLayout.HORIZONTAL
        searchField.gravity = Gravity.CENTER_VERTICAL
        searchField.background = box(PANEL2, 36, 3, RED)
        searchField.setPadding(p(22), 0, p(10), 0)
        searchField.addView(IconView(this, "search", TXT), lin(p(30), p(30)))
        searchEt = EditText(this)
        searchEt.background = null
        searchEt.setTextColor(TXT)
        searchEt.setHintTextColor(MUTED)
        searchEt.hint = "Nhập tên bài hát, ca sĩ hoặc dán link YouTube"
        searchEt.sz(26)
        searchEt.setSingleLine(true)
        searchEt.inputType = InputType.TYPE_CLASS_TEXT
        searchEt.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchEt.setPadding(p(8), 0, p(8), 0)
        searchEt.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                doTvSearch(searchEt.text.toString())
                hideIme()
                true
            } else false
        }
        searchEt.setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(searchEt, 0)
        }
        searchEt.setOnFocusChangeListener { _, f ->
            searchField.background = if (f) box(PANEL, 36, 4, CYAN) else box(PANEL2, 36, 3, RED)
        }
        searchField.addView(searchEt, lin(0, -1, 1f, l = 6))

        // Nut XOA (ben trai nut TIM): xoa het chu trong o tim kiem
        val clearBtn = lbl("XOÁ", 24, TXT, true)
        clearBtn.gravity = Gravity.CENTER
        clearBtn.setPadding(p(24), 0, p(24), 0)
        focusable(clearBtn, box(0x55FF1744, 26, 2, RED), box(RED, 26, 3, CYAN))
        clearBtn.setOnClickListener {
            searchEt.setText("")
            searchEt.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(searchEt, 0)
        }
        searchField.addView(clearBtn, lin(-2, p(52), l = 8))

        // Nut TIM
        val goBtn = lbl("TÌM", 24, TXT, true)
        goBtn.gravity = Gravity.CENTER
        goBtn.setPadding(p(28), 0, p(28), 0)
        focusable(goBtn, box(BTN, 26, 2, BTN_BORDER), box(FOCUS2, 26, 3, CYAN))
        goBtn.setOnClickListener {
            doTvSearch(searchEt.text.toString())
            hideIme()
        }
        searchField.addView(goBtn, lin(-2, p(52), l = 8))

        // Nut Danh Muc (mo danh sach the loai)
        val catBtn = LinearLayout(this)
        catBtn.orientation = LinearLayout.HORIZONTAL
        catBtn.gravity = Gravity.CENTER
        catBtn.setPadding(p(20), 0, p(16), 0)
        focusable(catBtn, box(RED, 26), box(RED, 26, 3, CYAN))
        catBtn.addView(IconView(this, "folder", TXT), lin(p(24), p(24)))
        catBtn.addView(lbl("Danh Mục", 24, TXT, true), lin(-2, -2, l = 8))
        catBtn.addView(IconView(this, "chevron", TXT), lin(p(22), p(22), l = 4))
        catBtnView = catBtn
        catBtn.setOnClickListener { showCatMenu() }
        searchField.addView(catBtn, lin(-2, p(52), l = 8))
        barRow.addView(searchField, lin(0, p(68), 1f, l = 14))

        // Nut bat/tat che do karaoke (dung chung voi remote dien thoai)
        karaokeBtn = LinearLayout(this)
        karaokeBtn.orientation = LinearLayout.HORIZONTAL
        karaokeBtn.gravity = Gravity.CENTER
        karaokeBtn.setPadding(p(22), 0, p(22), 0)
        karaokeDot = View(this)
        karaokeLbl = lbl("Karaoke", 24, TXT, true, 1)
        karaokeBtn.addView(karaokeDot, lin(p(14), p(14), r = 10))
        karaokeBtn.addView(karaokeLbl, lin(-2, -2))
        karaokeBtn.setOnClickListener { toggleKaraokeFromTv() }
        renderKaraokeBtn(false)
        barRow.addView(karaokeBtn, lin(-2, p(68), l = 14))
        col.addView(barRow, lin(-1, p(68)))

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.HORIZONTAL
        col.addView(mid, lin(-1, 0, 1f, t = 8))

        val gcol = LinearLayout(this)
        gcol.orientation = LinearLayout.VERTICAL
        status = lbl("", 20, MUTED, false, 1)
        gcol.addView(status, lin(-1, -2, l = 8, t = 6, b = 6))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.clipToPadding = false
        gridBox = LinearLayout(this)
        gridBox.orientation = LinearLayout.VERTICAL
        gridBox.setPadding(p(4), p(6), p(4), p(20))
        sv.addView(gridBox, FrameLayout.LayoutParams(-1, -2))
        gcol.addView(sv, lin(-1, 0, 1f))
        mid.addView(gcol, lin(0, -1, 1f))
        mid.addView(buildQueue(), lin(p(QUEUE_W), -1, l = 16))
        return col
    }

    private fun buildQueue(): View {
        val q = LinearLayout(this)
        q.orientation = LinearLayout.VERTICAL
        q.background = box(PANEL, 28, 2, BORDER)
        q.setPadding(p(18), p(16), p(18), p(14))
        val qh = LinearLayout(this)
        qh.orientation = LinearLayout.HORIZONTAL
        qh.gravity = Gravity.CENTER_VERTICAL
        qh.addView(lbl("HÀNG ĐỢI TIẾP THEO", 24, TXT, true), lin(0, -2, 1f))
        queueCount = lbl("0 bài", 18, TXT, true)
        queueCount.background = box(PANEL2, 18)
        queueCount.setPadding(p(14), p(4), p(14), p(4))
        qh.addView(queueCount, lin(-2, -2))
        q.addView(qh, lin(-1, -2))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        queueBox = LinearLayout(this)
        queueBox.orientation = LinearLayout.VERTICAL
        sv.addView(queueBox, FrameLayout.LayoutParams(-1, -2))
        q.addView(sv, lin(-1, 0, 1f, t = 10))
        val qf = LinearLayout(this)
        qf.orientation = LinearLayout.HORIZONTAL
        qf.gravity = Gravity.CENTER_VERTICAL
        queueTotal = lbl("", 18, MUTED, false, 1)
        qf.addView(queueTotal, lin(0, -2, 1f))
        val paste = lbl("Dán Link", 22, RED, true)
        focusable(paste, box(Color.TRANSPARENT, 14), box(FOCUS, 14, 3, CYAN))
        paste.setPadding(p(14), p(6), p(14), p(6))
        paste.setOnClickListener { showPasteLink() }
        qf.addView(paste, lin(-2, -2))
        q.addView(qf, lin(-1, -2, t = 8))
        return q
    }

    private fun qBtn(icon: String, red: Boolean, qi: QueueItem, enabled: Boolean, onClick: () -> Unit): View {
        val b = FrameLayout(this)
        focusable(
            b,
            if (red) box(RED, 14) else box(PANEL2, 14, 2, BORDER),
            box(if (red) RED else BTN2, 14, 3, CYAN)
        )
        b.addView(IconView(this, icon, if (red) Color.WHITE else TXT), FrameLayout.LayoutParams(p(24), p(24), Gravity.CENTER))
        b.alpha = if (enabled) 1f else 0.35f
        b.tag = QueueTag(qi)
        b.setOnClickListener { if (enabled) onClick() }
        return b
    }






    private fun tbtn(icon: String, red: Boolean, onClick: () -> Unit): Pair<FrameLayout, IconView> {
        val b = FrameLayout(this)
        focusable(
            b,
            if (red) box(RED, 24) else box(PANEL2, 24, 2, BORDER),
            box(if (red) RED else BTN2, 24, 4, CYAN)
        )
        val ic = IconView(this, icon, if (red) Color.WHITE else TXT)
        b.addView(ic, FrameLayout.LayoutParams(p(30), p(30), Gravity.CENTER))
        b.setOnClickListener { onClick() }
        return Pair(b, ic)
    }

    private fun volBtn(up: Boolean): View {
        val vb = FrameLayout(this)
        vb.addView(IconView(this, if (up) "vup" else "vdown", TXT), FrameLayout.LayoutParams(p(28), p(28), Gravity.CENTER))
        focusable(vb, box(PANEL2, 20, 2, BORDER), box(FOCUS, 20, 3, CYAN))
        val d = if (up) 5 else -5
        vb.setOnClickListener { core?.controller?.changeVolume(d) }
        vb.setOnLongClickListener {
            val rep = object : Runnable {
                override fun run() {
                    if (!vb.isPressed) return
                    core?.controller?.changeVolume(d)
                    vb.postDelayed(this, 150)
                }
            }
            vb.postDelayed(rep, 150)
            true
        }
        return vb
    }

    private fun renderRepeat(m: Int) {
        if (!::repeatIcon.isInitialized) return
        repeatIcon.set(if (m == 1) "repeat1" else "repeat", if (m == 0) MUTED else RED)
        focusable(
            repeatBtn,
            if (m == 0) box(PANEL2, 24, 2, BORDER) else box(0x33FF1744, 24, 2, RED),
            box(BTN2, 24, 4, CYAN)
        )
    }

    private fun buildRight(): View {
        val now = LinearLayout(this)
        now.orientation = LinearLayout.VERTICAL
        now.background = box(PANEL, 28, 2, BORDER)
        now.setPadding(p(20), p(16), p(20), p(18))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(IconView(this, "dot", RED), lin(p(24), p(24)))
        head.addView(lbl("ĐANG PHÁT", 24, TXT, true), lin(-2, -2, l = 6))
        head.addView(spacer(), lin(0, 1, 1f))
        head.addView(IconView(this, "eq", RED), lin(p(26), p(26)))
        now.addView(head, lin(-1, -2))

        slot = View(this)
        now.addView(slot, lin(-1, p(270), t = 12))

        titleTv = lbl("Chưa có bài hát", 30, TXT, true, 1)
        now.addView(titleTv, lin(-1, -2, t = 14))
        subTv = lbl("", 22, MUTED, false, 1)
        now.addView(subTv, lin(-1, -2, t = 2))

        val prog = LinearLayout(this)
        prog.orientation = LinearLayout.HORIZONTAL
        prog.gravity = Gravity.CENTER_VERTICAL
        timeL = lbl("00:00", 19, MUTED)
        timeR = lbl("00:00", 19, MUTED)
        bar = BarView(this)
        bar.trackColor = BTN
        prog.addView(timeL, lin(-2, -2))
        prog.addView(bar, lin(0, p(8), 1f, l = 12, r = 12))
        prog.addView(timeR, lin(-2, -2))
        now.addView(prog, lin(-1, -2, t = 12))

        // 6 nut: lui 10s | phat/dung | bai tiep | tua 10s | phat lai tu dau | lap lai
        val ctr = LinearLayout(this)
        ctr.orientation = LinearLayout.HORIZONTAL
        val b1 = tbtn("rew", false) { core?.controller?.seekTo(null, -10_000L) }
        val b2 = tbtn("pause", true) {
            val c = core ?: return@tbtn
            if (lastUi.state == PState.PAUSED) c.controller.resume() else c.controller.pause()
        }
        playIcon = b2.second
        val b3 = tbtn("next", false) { core?.controller?.skip() }
        val b4 = tbtn("ff", false) { core?.controller?.seekTo(null, 10_000L) }
        val b5 = tbtn("replay", false) {
            core?.controller?.restart()
            toast("Phát lại từ đầu")
        }
        val b6 = tbtn("repeat", false) {
            val m = core?.controller?.cycleRepeat() ?: 0
            toast(when (m) { 1 -> "Lặp lại: 1 BÀI"; 2 -> "Lặp lại: CẢ HÀNG ĐỢI"; else -> "Lặp lại: TẮT" })
        }
        repeatBtn = b6.first
        repeatIcon = b6.second
        val bs = listOf(b1.first, b2.first, b3.first, b4.first, b5.first, b6.first)
        bs.forEachIndexed { i, v -> ctr.addView(v, lin(0, p(76), 1f, r = if (i < bs.size - 1) 8 else 0)) }
        now.addView(ctr, lin(-1, -2, t = 16))
        renderRepeat(0)

        // Am luong: [-] ---o--- [+]
        val vr = LinearLayout(this)
        vr.orientation = LinearLayout.HORIZONTAL
        vr.gravity = Gravity.CENTER_VERTICAL
        vr.addView(volBtn(false), lin(p(60), p(60)))
        volSlider = SliderView(this)
        volSlider.trackColor = BTN
        volSlider.knobColor = TXT
        focusable(volSlider, box(Color.TRANSPARENT, 16), box(FOCUS, 16, 3, CYAN))
        volSlider.setOnKeyListener { _, k, ev ->
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (ev.action == KeyEvent.ACTION_DOWN) core?.controller?.changeVolume(if (k == KeyEvent.KEYCODE_DPAD_RIGHT) 5 else -5)
                true
            } else false
        }
        volSlider.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                core?.controller?.setVolumeAbs((ev.x / v.width.coerceAtLeast(1) * 100).toInt())
                true
            } else false
        }
        vr.addView(volSlider, lin(0, p(60), 1f, l = 12, r = 12))
        vr.addView(volBtn(true), lin(p(60), p(60)))
        now.addView(vr, lin(-1, -2, t = 16))

        qualText = lbl("", 18, MUTED, false, 1)
        qualText.gravity = Gravity.CENTER
        now.addView(qualText, lin(-1, -2, t = 14))
        return now
    }


    private fun buildVideoBox() {
        videoBox = FrameLayout(this)
        videoBox.setBackgroundColor(Color.BLACK)
        surface = SurfaceView(this)
        surface.visibility = View.INVISIBLE
        videoBox.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        idle = lbl("", 22, MUTED, true)
        idle.gravity = Gravity.CENTER
        idle.setPadding(p(20), 0, p(20), 0)
        idle.visibility = View.GONE
        videoBox.addView(idle, FrameLayout.LayoutParams(-1, -1))

        resTag = lbl("", 16, Color.WHITE, true)
        resTag.background = box(0x99000000.toInt(), 8)
        resTag.setPadding(p(10), p(3), p(10), p(3))
        val rlp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
        rlp.setMargins(p(12), p(12), 0, 0)
        videoBox.addView(resTag, rlp)

        nowBadge = lbl("ĐANG PHÁT", 16, Color.WHITE, true)
        nowBadge.background = box(RED, 8)
        nowBadge.setPadding(p(10), p(3), p(10), p(3))
        val nlp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        nlp.setMargins(0, p(12), p(12), 0)
        videoBox.addView(nowBadge, nlp)

        countdownTv = lbl("", 72, Color.WHITE, true)
        countdownTv.gravity = Gravity.CENTER
        countdownTv.background = box(0xAA000000.toInt(), 60, 4, 0xFFFF4D8D.toInt())
        countdownTv.visibility = View.GONE
        videoBox.addView(countdownTv, FrameLayout.LayoutParams(p(120), p(120), Gravity.CENTER))

        val fs = FrameLayout(this)
        focusable(fs, box(0x99000000.toInt(), 30, 2, 0x55FFFFFF), box(0xCC000000.toInt(), 30, 4, CYAN))
        fs.addView(IconView(this, "fullscreen", Color.WHITE), FrameLayout.LayoutParams(p(30), p(30), Gravity.CENTER))
        fs.setOnClickListener {
            lastControlAt = SystemClock.elapsedRealtime()
            enterFullscreen()
        }
        fsBtn = fs
        val flp = FrameLayout.LayoutParams(p(60), p(60), Gravity.BOTTOM or Gravity.END)
        flp.setMargins(0, 0, p(12), p(12))
        videoBox.addView(fs, flp)

        root.addView(videoBox, FrameLayout.LayoutParams(0, 0))
    }

    // ------------------------------------------------------------------ video box: nho <-> toan man hinh

    private fun applyVideoBox() {
        val lp = videoBox.layoutParams as FrameLayout.LayoutParams
        var w = -1
        var h = -1
        var l = 0
        var t = 0
        if (!fullscreen) {
            if (slot.width == 0 || slot.height == 0) return
            val a = IntArray(2)
            val b = IntArray(2)
            slot.getLocationInWindow(a)
            root.getLocationInWindow(b)
            w = slot.width
            h = slot.height
            l = a[0] - b[0]
            t = a[1] - b[1]
        }
        if (lp.width == w && lp.height == h && lp.leftMargin == l && lp.topMargin == t) return
        lp.width = w
        lp.height = h
        lp.leftMargin = l
        lp.topMargin = t
        lp.gravity = Gravity.TOP or Gravity.START
        videoBox.layoutParams = lp
    }

    private fun enterFullscreen() {
        if (fullscreen) return
        val st = lastUi.state
        if (st != PState.PLAYING && st != PState.PAUSED) return
        fullscreen = true
        countdownTv.visibility = View.GONE
        savedFocus = currentFocus
        dash.visibility = View.INVISIBLE
        fsBtn.visibility = View.GONE
        resTag.visibility = View.GONE
        nowBadge.visibility = View.GONE
        applyVideoBox()
        fitSurface()
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        fullscreen = false
        lastControlAt = SystemClock.elapsedRealtime()
        dash.visibility = View.VISIBLE
        fsBtn.visibility = View.VISIBLE
        resTag.visibility = View.VISIBLE
        nowBadge.visibility = View.VISIBLE
        applyVideoBox()
        fitSurface()
        val target = savedFocus?.takeIf { it.isAttachedToWindow }
            ?: cardViews.firstOrNull() ?: fsBtn
        target.post { target.requestFocus() }
    }

    private fun fitSurface() {
        val rw = videoBox.width
        val rh = videoBox.height
        if (rw == 0 || rh == 0 || vw == 0 || vh == 0) return
        val ar = vw * par / vh
        var w = rw
        var h = (rw / ar).toInt()
        if (h > rh) {
            h = rh
            w = (rh * ar).toInt()
        }
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surface.layoutParams = lp
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun renderUi(u: UiState) {
        lastUi = u
        val playing = u.state == PState.PLAYING || u.state == PState.PAUSED
        if (playing) inSession = true
        else if (u.state == PState.IDLE || u.state == PState.ERROR) inSession = false
        val showIframe = u.iframe && u.state != PState.IDLE
        val showBlank = !showIframe && !playing &&
            (u.state == PState.IDLE || u.state == PState.ERROR || (u.state == PState.RESOLVING && !inSession))
        web?.visibility = if (showIframe) View.VISIBLE else View.GONE
        surface.visibility = if (showIframe || showBlank) View.INVISIBLE else View.VISIBLE

        val msg: String? = when {
            u.message == "net" -> "Mất kết nối mạng, đang thử lại…"
            u.state == PState.ERROR -> "Không phát được bài này, đang chuyển bài…"
            u.state == PState.IDLE -> "Chưa phát bài nào\nChọn bài ở danh sách hoặc dùng điện thoại"
            u.state == PState.RESOLVING && !inSession -> "Đang tải bài hát…"
            else -> null
        }
        idle.text = msg ?: ""
        idle.visibility = if (msg != null) View.VISIBLE else View.GONE
        nowBadge.text = if (u.state == PState.PAUSED) "ĐANG TẠM DỪNG" else "ĐANG PHÁT"
        playIcon.set(if (u.state == PState.PAUSED) "play" else "pause")

        titleTv.text = u.nowTitle ?: "Chưa có bài hát"
        subTv.text = core?.controller?.currentItem()?.channel ?: ""
        if (u.state == PState.IDLE) exitFullscreen()
        refreshQueue()
    }


    private fun refreshQueue() {
        val c = core ?: return
        val items = c.controller.queueItems()
        val sig = items.joinToString("|") { it.itemId + it.title }
        queueCount.text = "${items.size} bài"
        val totalSec = items.sumOf { it.durationSec }
        val m = totalSec / 60
        queueTotal.text = when {
            items.isEmpty() -> "Hàng đợi trống"
            totalSec <= 0 -> "Tổng thời gian: —"
            m < 60 -> "Tổng thời gian: ${maxOf(1, m)} phút"
            else -> "Tổng thời gian: ${m / 60} giờ ${m % 60} phút"
        }
        if (sig == lastQueueSig) return
        lastQueueSig = sig
        // Nho nut dang giu tieu diem (hang, nut) de tra lai sau khi ve lai danh sach
        var fr = -1
        var fb = 0
        queueBtns.forEachIndexed { r, btns -> btns.forEachIndexed { b, v -> if (v.hasFocus()) { fr = r; fb = b } } }
        val want = pendingQFocus
        pendingQFocus = null
        queueBox.removeAllViews()
        queueBtns.clear()
        if (items.isEmpty()) {
            val e = lbl("Chưa có bài nào chờ.\nChọn bài ở bên trái, dán link hoặc dùng điện thoại để thêm.", 19, MUTED)
            e.setPadding(p(8), p(20), p(8), 0)
            queueBox.addView(e, lin(-1, -2))
            return
        }
        items.forEachIndexed { i, qi ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.TOP
            row.setPadding(p(8), p(8), p(8), p(8))
            row.background = box(0x00000000, 18)
            row.addView(lbl("${i + 1}", 22, MUTED, true), lin(p(30), -2, t = 4))
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundColor(THUMB)
            Img.load(iv, Util.thumb(qi.videoId))
            row.addView(iv, lin(p(100), p(56)))
            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            info.addView(lbl(qi.title, 22, TXT, true, 1), lin(-1, -2))
            info.addView(
                lbl(
                    (if (qi.addedBy.isNullOrBlank()) "Tự động đề xuất" else "Yêu cầu từ: ${qi.addedBy}") +
                        (if (qi.durationSec > 0) " · ${mmss(qi.durationSec * 1000L)}" else ""),
                    18, MUTED, false, 1
                ),
                lin(-1, -2, t = 2)
            )
            val btnRow = LinearLayout(this)
            btnRow.orientation = LinearLayout.HORIZONTAL
            val bPlay = qBtn("play", true, qi, true) { playQueueItem(qi) }
            val bUp = qBtn("up", false, qi, i > 0) {
                pendingQFocus = Pair(i - 1, 1)
                moveQueueItem(qi, i - 1)
            }
            val bDown = qBtn("down", false, qi, i < items.size - 1) {
                pendingQFocus = Pair(i + 1, 2)
                moveQueueItem(qi, i + 1)
            }
            val bDel = qBtn("trash", false, qi, true) { removeQueueItem(qi) }
            val all = listOf(bPlay, bUp, bDown, bDel)
            all.forEachIndexed { k, v -> btnRow.addView(v, lin(p(52), p(42), r = if (k < all.size - 1) 8 else 0)) }
            info.addView(btnRow, lin(-2, -2, t = 8))
            row.addView(info, lin(0, -2, 1f, l = 12))
            row.setOnClickListener { playQueueItem(qi) }
            row.isFocusable = false
            queueBox.addView(row, lin(-1, -2, b = 6))
            queueBtns.add(ArrayList(all))
        }
        val tgt = want ?: if (fr >= 0) Pair(fr, fb) else null
        if (tgt != null && queueBtns.isNotEmpty()) {
            val r = tgt.first.coerceIn(0, queueBtns.size - 1)
            queueBtns[r].getOrNull(tgt.second)?.requestFocus()
        }
    }

    private fun moveQueueItem(item: QueueItem, newIndex: Int) {
        try {
            core?.controller?.reorder(item.itemId, newIndex)
        } catch (e: ApiError) {
            pendingQFocus = null
        }
    }


    private fun playQueueItem(item: QueueItem) {
        try {
            core?.controller?.playNow(item.itemId)
        } catch (e: ApiError) {
            toast("Bài này không còn trong hàng đợi")
        }
    }

    private fun removeQueueItem(item: QueueItem) {
        try {
            core?.controller?.remove(item.itemId)
            toast("Đã xoá khỏi hàng đợi")
        } catch (e: ApiError) {
        }
    }

    // ------------------------------------------------------------------ search grid

    private fun renderSearch(sv: SearchState) {
        if (favMode) {
            // Dang xem Yeu thich: giu nguyen, tru khi co tim kiem moi (vd tu dien thoai)
            if (sv.query == favSearchQuery) return
            leaveFavMode()
        }
        val dq = SearchService.displayQuery(sv.query)
        val isPlQ = sv.query.startsWith("pl:")
        val isChQ = sv.query.startsWith("ch:")
        status.text = when {
            sv.loading && sv.results.isEmpty() -> if (isChQ) "Đang tải video của kênh…" else if (isPlQ) "Đang mở danh sách phát…" else "Đang tìm “$dq”…"
            sv.error != null && sv.results.isEmpty() -> "Không tìm được: ${sv.error}"
            sv.query.isEmpty() -> "Bấm Danh Mục hoặc gõ tên bài hát để tìm"
            sv.results.isEmpty() -> "Không có kết quả cho “$dq”"
            else -> (if (isChQ) "Tất cả video của “$dq” · BACK để quay lại" else if (isPlQ) "Danh sách phát “$dq” · BACK để quay lại" else "Kết quả cho “$dq”") +
                (if (sv.source == "remote") " · từ điện thoại" else "") +
                (if (sv.loading) " · đang tải thêm…" else "")
        }
        if (!searchEt.hasFocus() && dq != searchEt.text.toString()) searchEt.setText(dq)
        if (sv.source == "remote") syncCatToQuery(sv.query)

        val res = sv.results
        val same = sv.query == shownQuery && res.size == shownCount && res.firstOrNull()?.videoId == shownFirst
        if (same) {
            // Khong co bai moi (vd trang tai ve bi loc het / loi mang): cap nhat nut "tai them" roi tu tai tiep neu dang o cuoi
            updateMoreFooter(sv, false)
            maybeAutoLoadMore(sv)
            return
        }
        val footerHadFocus = moreView?.hasFocus() == true
        (moreView?.parent as? ViewGroup)?.removeView(moreView)
        val append = sv.query == shownQuery && shownCount > 0 && res.size > shownCount &&
            res.firstOrNull()?.videoId == shownFirst

        val fIdx = (currentFocus?.tag as? CardTag)?.index ?: -1
        val from = if (append) shownCount else 0
        val cols = gridCols
        val startRow = from / cols
        while (gridBox.childCount > startRow) gridBox.removeViewAt(gridBox.childCount - 1)
        while (cardViews.size > startRow * cols) cardViews.removeAt(cardViews.size - 1)
        var i = startRow * cols
        while (i < res.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in 0 until cols) {
                val idx = i + k
                if (idx < res.size) {
                    val cv = makeCard(res[idx], idx)
                    row.addView(cv, lin(cardW, -2, r = if (k < cols - 1) 14 else 0))
                    cardViews.add(cv)
                } else {
                    row.addView(View(this), lin(cardW, 1))
                }
            }
            gridBox.addView(row, lin(-1, -2, b = 14))
            i += cols
        }
        shownQuery = sv.query
        shownCount = res.size
        shownFirst = res.firstOrNull()?.videoId

        updateMoreFooter(sv, true)
        if (footerHadFocus && from < cardViews.size) cardViews[from].requestFocus() // dang o nut "tai them" -> nhay toi bai moi dau tien
        else if (fIdx >= 0 && fIdx < cardViews.size) cardViews[fIdx].requestFocus()
        else if (currentFocus == null && !fullscreen && cardViews.isNotEmpty()) cardViews[0].requestFocus()
        maybeAutoLoadMore(sv)
    }

    private var moreView: View? = null

    /** Nut/hang cuoi luoi ket qua: "▼ Tai them" (nhu YouTube cuon toi day la tai tiep). Cham/OK de tai, chi can focus toi cung tu tai. */
    private fun updateMoreFooter(sv: SearchState, rebuilt: Boolean) {
        val need = !favMode && sv.results.isNotEmpty() && (sv.hasMore || sv.error != null)
        val txt = when {
            sv.loading -> "Đang tải thêm…"
            sv.error != null -> "⚠ Lỗi mạng — bấm OK để thử lại"
            else -> "▼  Tải thêm video"
        }
        val cur = moreView
        if (!need) {
            (cur?.parent as? ViewGroup)?.removeView(cur)
            moreView = null
            return
        }
        if (cur != null && cur.parent === gridBox) {
            (cur.tag as? TextView)?.text = txt
            return
        }
        val f = FrameLayout(this)
        val tv = lbl(txt, 24, TXT, true, 1)
        tv.gravity = Gravity.CENTER
        f.addView(tv, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        f.setPadding(p(8), p(16), p(8), p(16))
        focusable(f, box(PANEL, 18, 2, BORDER), box(FOCUS_CARD, 18, 4, CYAN))
        f.tag = tv
        f.setOnClickListener { tvLoadMore(true) }
        f.setOnFocusChangeListener { _, hasF -> if (hasF) tvLoadMore() }
        gridBox.addView(f, lin(-1, -2, b = 14))
        moreView = f
    }

    /** Dang xem gan cuoi danh sach (hoac danh sach qua ngan de cuon) ma con bai -> tu tai tiep, khong can doi doi focus. */
    private fun maybeAutoLoadMore(sv: SearchState) {
        if (favMode || sv.loading || !sv.hasMore || sv.error != null || sv.query.isEmpty() || sv.results.isEmpty()) return
        val fIdx = (currentFocus?.tag as? CardTag)?.index ?: -1
        val nearEnd = moreView?.hasFocus() == true || (fIdx >= 0 && fIdx >= sv.results.size - 4) || sv.results.size < 12
        if (nearEnd) gridBox.post { tvLoadMore() }
    }

    /** Doi so bai tren moi hang (1/2/3) roi ve lai luoi ket qua. */
    private fun applyGridCols(n: Int) {
        gridCols = n.coerceIn(1, 4)
        cardW = calcCardW()
        shownQuery = ""; shownCount = 0; shownFirst = null
        gridBox.removeAllViews()
        cardViews.clear()
        if (favMode) renderFavs(0) else core?.let { renderSearch(it.controller.search.value) }
    }

    private fun makeCard(h: SearchHit, idx: Int): View {
        val horiz = gridCols == 1
        val c = LinearLayout(this)
        c.orientation = if (horiz) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (horiz) c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(p(8), p(8), p(8), p(10))
        focusable(c, box(PANEL, 18, 2, BORDER), box(FOCUS_CARD, 18, 4, CYAN))
        val tw = if (horiz) p(300) else cardW - p(16)
        val th = FrameLayout(this)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setBackgroundColor(THUMB)
        th.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val isPl = h.kind == "playlist"
        if (h.durationSec > 0 || isPl) {
            val d = lbl(if (isPl) (if (h.count > 0) "☰ ${h.count} video" else "☰ Playlist") else mmss(h.durationSec * 1000L), 16, Color.WHITE, true)
            d.background = box(0xCC000000.toInt(), 6)
            d.setPadding(p(8), p(2), p(8), p(2))
            val dl = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
            dl.setMargins(0, 0, p(8), p(8))
            th.addView(d, dl)
        }
        val hv = IconView(this, "heart", PINK)
        hv.tag = "heart"
        hv.visibility = if (!isPl && isFav(h.videoId)) View.VISIBLE else View.GONE
        val hlp = FrameLayout.LayoutParams(p(30), p(30), Gravity.TOP or Gravity.START)
        hlp.setMargins(p(8), p(8), 0, 0)
        th.addView(hv, hlp)
        if (isPl) {
            val tagPl = lbl("DANH SÁCH PHÁT", 15, Color.WHITE, true)
            tagPl.background = box(0xCCC62828.toInt(), 6)
            tagPl.setPadding(p(8), p(2), p(8), p(2))
            val tlp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            tlp.setMargins(p(8), p(8), 0, 0)
            th.addView(tagPl, tlp)
        }
        if (!isPl) {
            val dv = FrameLayout(this)
            dv.background = box(0xCC000000.toInt(), 20, 2, 0x55FFFFFF)
            val di = IconView(this, "download", Color.WHITE)
            di.tag = "dlicon"
            dv.addView(di, FrameLayout.LayoutParams(p(22), p(22), Gravity.CENTER))
            dv.setOnClickListener { downloadHit(h) }
            dv.isFocusable = false
            val dlp = FrameLayout.LayoutParams(p(40), p(40), Gravity.TOP or Gravity.END)
            dlp.setMargins(0, p(8), p(8), 0)
            th.addView(dv, dlp)
            markDl(di, h.videoId)
        }
        c.addView(th, LinearLayout.LayoutParams(tw, tw * 9 / 16))
        if (horiz) {
            // 1 bai/hang: thumbnail ben trai, chu ben phai (khong keo thumbnail qua to)
            val tcol = LinearLayout(this)
            tcol.orientation = LinearLayout.VERTICAL
            tcol.gravity = Gravity.CENTER_VERTICAL
            tcol.addView(lbl(h.title, 26, TXT, true, 2), lin(-1, -2))
            tcol.addView(lbl(h.channel, 21, MUTED, false, 1), lin(-1, -2, t = 6))
            c.addView(tcol, lin(0, -2, 1f, l = 18, r = 10))
        } else {
            c.addView(lbl(h.title, 21, TXT, true, 1), lin(-1, -2, t = 10))
            c.addView(lbl(h.channel, 18, MUTED, false, 1), lin(-1, -2, t = 2))
        }
        c.tag = CardTag(h, idx)
        Img.load(iv, if (h.thumb.isNotEmpty()) h.thumb else Util.thumb(h.videoId))
        c.setOnClickListener { if (isPl) openPlaylist(h) else addHit(h, false) }
        // OK 1 lan = them vao hang doi; giu OK (hoac MENU) = hop menu: Yeu thich / Tai nhac / Phat ngay / Hang doi
        c.setOnLongClickListener { if (isPl) addPlaylist(h) else showCardMenu(h); true }
        c.setOnFocusChangeListener { _, f ->
            if (f) {
                val total = core?.controller?.search?.value?.results?.size ?: 0
                if (!favMode && idx >= total - 4) tvLoadMore()
            }
        }
        return c
    }

    // ------------------------------------------------------------------ yeu thich

    private fun isFav(id: String) = favs.any { it.videoId == id }

    private fun updateFavBtn() {
        focusable(
            favBtn,
            box(if (favMode) PINK else 0xFFC2185B.toInt(), 28),
            box(PINK, 28, 4, CYAN)
        )
    }

    private fun refreshHearts() {
        for (cv in cardViews) {
            val tg = cv.tag as? CardTag ?: continue
            cv.findViewWithTag<View>("heart")?.visibility = if (isFav(tg.hit.videoId)) View.VISIBLE else View.GONE
        }
    }

    private fun addFav(h: SearchHit) {
        val c = core ?: return
        if (isFav(h.videoId)) { toast("Bài này đã có trong Yêu thích"); return }
        favs.add(0, h)
        Favs.save(c.settings.sp, favs)
        c.controller.bumpFavVersion()
        refreshHearts()
        toast("❤ Đã thêm vào Yêu thích: ${h.title}")
    }

    private fun removeFav(h: SearchHit) {
        val c = core ?: return
        val pos = favs.indexOfFirst { it.videoId == h.videoId }
        if (pos < 0) return
        favs.removeAt(pos)
        Favs.save(c.settings.sp, favs)
        c.controller.bumpFavVersion()
        toast("Đã xoá khỏi Yêu thích")
        if (favMode) renderFavs(pos)
    }

    private fun showFavorites() {
        val c = core ?: return
        searchJob?.cancel()
        favMode = true
        favSearchQuery = c.controller.search.value.query
        selCat = -1
        renderCats()
        updateFavBtn()
        renderFavs(0)
    }

    private fun leaveFavMode() {
        if (!favMode) return
        favMode = false
        updateFavBtn()
        shownQuery = ""; shownCount = 0; shownFirst = null
        gridBox.removeAllViews()
        cardViews.clear()
    }

    private fun renderFavs(focusIdx: Int) {
        gridBox.removeAllViews()
        cardViews.clear()
        shownQuery = "\u0000fav"; shownCount = -1; shownFirst = null
        status.text = if (favs.isEmpty()) "Chưa có bài yêu thích. Khi tìm kiếm, giữ phím OK trên bài hát để thêm vào đây."
        else "❤ Yêu thích · ${favs.size} bài · OK: thêm vào hàng đợi · Giữ OK: menu (xoá khỏi Yêu thích, tải…)"
        val cols = gridCols
        var i = 0
        while (i < favs.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in 0 until cols) {
                val idx = i + k
                if (idx < favs.size) {
                    val cv = makeCard(favs[idx], idx)
                    row.addView(cv, lin(cardW, -2, r = if (k < cols - 1) 14 else 0))
                    cardViews.add(cv)
                } else row.addView(View(this), lin(cardW, 1))
            }
            gridBox.addView(row, lin(-1, -2, b = 14))
            i += cols
        }
        if (cardViews.isNotEmpty() && !fullscreen) {
            val t = cardViews[focusIdx.coerceIn(0, cardViews.size - 1)]
            t.post { t.requestFocus() }
        } else if (cardViews.isEmpty()) favBtn.requestFocus()
    }

    private fun addHit(h: SearchHit, priority: Boolean) {
        val c = core ?: return
        when (c.controller.addFromTv(h.videoId, h.title, h.channel, h.durationSec, priority)) {
            0 -> toast("Đang phát: ${h.title}")
            1 -> toast(if (priority) "Đã thêm lên đầu hàng đợi" else "Đã thêm vào hàng đợi")
            2 -> toast("Bài này đã có trong hàng đợi")
            else -> toast("Hàng đợi đã đầy")
        }
    }

    private fun doTvSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        val c = core ?: return
        // Dan link YouTube -> them thang bai do
        if (q.contains("youtu") && Util.parseVideoId(q) != null) {
            addFromLink(q)
            return
        }
        syncCatToQuery(q)
        tvSearch(q)
    }

    /** Giu/bat/tat karaoke tu nut tren TV: doi trang thai chung roi tim lai tu khoa dang hien. */
    private fun toggleKaraokeFromTv() {
        val c = core ?: return
        val on = !c.controller.karaoke.value
        c.controller.setKaraoke(on)
        toast(if (on) "Karaoke: BẬT — mọi tìm kiếm tự thêm chữ karaoke" else "Karaoke: TẮT")
        val q = c.controller.search.value.query.trim()
        if (q.isNotEmpty()) tvSearch(q)
    }

    private fun renderKaraokeBtn(on: Boolean) {
        karaokeBtn.contentDescription = if (on) "Karaoke bật" else "Karaoke tắt"
        focusable(
            karaokeBtn,
            if (on) box(RED, 34, 2, RED) else box(PANEL, 34, 2, BORDER),
            if (on) box(RED, 34, 3, CYAN) else box(FOCUS, 34, 3, CYAN)
        )
        karaokeLbl.setTextColor(if (on) Color.WHITE else TXT2)
        karaokeDot.background = box(if (on) Color.WHITE else 0xFF6A6A80.toInt(), 14)
    }

    private fun tvSearch(q: String) {
        val c = core ?: return
        leaveFavMode()
        searchJob?.cancel()
        c.controller.searchBegin(q, "tv")
        val kara = c.controller.karaoke.value
        searchJob = scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { SearchService.search(q, 0, kara) }
                c.controller.searchDone(q, "tv", 0, r.first, r.second)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                c.controller.searchFail(q, "tv", (e.message ?: "lỗi mạng").take(100))
            }
        }
    }

    private fun tvLoadMore(retry: Boolean = false) {
        val c = core ?: return
        val s = c.controller.search.value
        if (s.loading || !s.hasMore || s.query.isEmpty()) return
        if (s.error != null && !retry) return // dang loi: cho nguoi dung bam OK thu lai (tranh lap vo han)
        val q = s.query
        val page = s.page + 1
        c.controller.searchLoadingMore()
        val kara = c.controller.karaoke.value
        searchJob = scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { SearchService.search(q, page, kara) }
                c.controller.searchDone(q, s.source, page, r.first, r.second)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                c.controller.searchMoreFail(q, (e.message ?: "lỗi mạng").take(100)) // giu ket qua + hasMore de thu lai
            }
        }
    }

    /** OK tren mot playlist: mo danh sach video cua playlist (cuon het thi tu tai them, nhu YouTube). */
    private fun openPlaylist(h: SearchHit) {
        val c = core ?: return
        val cur = c.controller.search.value.query
        if (!SearchService.isSubQuery(cur)) plReturn = cur.takeIf { it.isNotEmpty() }
        tvSearch("pl:" + h.videoId)
    }

    /** Menu -> "Xem video kênh này": tự tải toàn bộ video của kênh (không phải tìm theo tên kênh). BACK để quay lại. */
    private fun openChannel(h: SearchHit) {
        val c = core ?: return
        val cur = c.controller.search.value.query
        if (!SearchService.isSubQuery(cur)) plReturn = cur.takeIf { it.isNotEmpty() }
        tvSearch("ch:" + h.videoId)
    }

    /** Giu OK / MENU tren mot playlist: them toan bo video (toi da 100) vao hang doi. */
    private fun addPlaylist(h: SearchHit) {
        val c = core ?: return
        toast("Đang thêm danh sách phát…")
        scope.launch {
            try {
                val hits = withContext(Dispatchers.IO) { SearchService.playlistVideos(h.videoId, 100) }
                val n = c.controller.addMany(hits, "TV")
                toast(if (n > 0) "Đã thêm $n bài vào hàng đợi" else "Không thêm được bài nào (đã có trong hàng đợi?)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Không mở được danh sách phát")
            }
        }
    }

    // ------------------------------------------------------------------ categories

    private fun renderCats() {
        var fIdx = -1
        for (k in 0 until catBox.childCount) if (catBox.getChildAt(k).hasFocus()) fIdx = k
        catBox.removeAllViews()
        cats.forEachIndexed { i, ct ->
            val sel = i == selCat
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(p(20), 0, p(20), 0)
            focusable(
                row,
                box(if (sel) RED else Color.TRANSPARENT, 22),
                box(if (sel) RED else FOCUS, 22, 3, CYAN)
            )
            row.addView(IconView(this, "note", if (sel) Color.WHITE else MUTED), lin(p(28), p(28)))
            row.addView(lbl(ct.name, 24, if (sel) Color.WHITE else TXT2, sel, 1), lin(0, -2, 1f, l = 14))
            row.setOnClickListener { selectCat(i) }
            // Moi the loai (ke ca 5 the loai co dinh) deu co nut Xoa
            row.setOnLongClickListener { confirmDeleteCat(i); true }
            val del = lbl("Xoá", 18, TXT, true)
            del.gravity = Gravity.CENTER
            del.setPadding(p(14), p(4), p(14), p(4))
            focusable(del, box(0x55FF1744, 16, 2, RED), box(RED, 16, 3, CYAN))
            del.setOnClickListener { confirmDeleteCat(i) }
            row.addView(del, lin(-2, p(38), l = 8))
            // Remote TV: nut "Xoa" nam trong hang nen Android khong tu nhay vao duoc -> tu dieu huong
            // Phai: hang -> Xoa | Trai: Xoa -> hang | Len/Xuong: giua cac nut Xoa
            row.setOnKeyListener { _, keyCode, ev ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (ev.action == KeyEvent.ACTION_DOWN) del.requestFocus()
                    true
                } else false
            }
            del.setOnKeyListener { _, keyCode, ev ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (ev.action == KeyEvent.ACTION_DOWN) row.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val step = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                        val nextRow = catBox.getChildAt(i + step) as? ViewGroup
                        val nextDel = nextRow?.getChildAt(nextRow.childCount - 1)
                        if (nextDel != null) {
                            if (ev.action == KeyEvent.ACTION_DOWN) nextDel.requestFocus()
                            true
                        } else false
                    }
                    else -> false
                }
            }
            catBox.addView(row, lin(-1, p(58), b = 6))
        }
        if (fIdx >= 0 && catBox.childCount > 0) catBox.getChildAt(minOf(fIdx, catBox.childCount - 1)).requestFocus()
    }

    private fun selectCat(i: Int) {
        val ct = cats.getOrNull(i) ?: return
        if (catDialog?.isShowing == true) catDialog?.dismiss()
        selCat = i
        renderCats()
        searchEt.setText(ct.q)
        tvSearch(ct.q)
    }

    /** Neu tu khoa trung voi mot the loai thi to sang do, khong thi bo chon. */
    private fun syncCatToQuery(q: String) {
        val idx = cats.indexOfFirst { it.q.equals(q.trim(), ignoreCase = true) }
        if (idx != selCat) {
            selCat = idx
            renderCats()
        }
    }

    private fun createCategoryDialog() {
        val c = core ?: return
        val et = EditText(this)
        et.hint = "Ví dụ: Nhạc không lời"
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT
        val d = track(
            AlertDialog.Builder(this)
                .setTitle("Tạo thể loại mới (tên cũng là từ khoá tìm)")
                .setView(et)
                .setPositiveButton("Tạo") { _, _ ->
                    val n = et.text.toString().trim().take(40)
                    if (n.isNotEmpty() && cats.none { it.name.equals(n, true) }) {
                        cats.add(Cat(n, n, true))
                        Cats.save(c.settings.sp, cats)
                        selectCat(cats.size - 1)
                    }
                }
                .setNegativeButton("Hủy", null)
        )
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun confirmDeleteCat(i: Int) {
        val c = core ?: return
        val ct = cats.getOrNull(i) ?: return
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(p(40), p(32), p(40), p(8))
        val t1 = lbl("Xoá thể loại này?", 34, TXT, true)
        t1.gravity = Gravity.CENTER
        body.addView(t1, lin(-1, -2))
        val t2 = lbl("“${ct.name}”", 30, RED, true)
        t2.gravity = Gravity.CENTER
        body.addView(t2, lin(-1, -2, t = 14))
        val t3 = lbl("Thể loại sẽ bị xoá khỏi danh mục.\nCó thể tạo lại hoặc khôi phục trong Cài đặt.", 22, MUTED)
        t3.gravity = Gravity.CENTER
        body.addView(t3, lin(-1, -2, t = 12))
        val d = track(
            AlertDialog.Builder(this)
                .setView(body)
                .setPositiveButton("XOÁ") { _, _ ->
                    val idx = cats.indexOfFirst { it === ct }
                    if (idx >= 0) {
                        cats.removeAt(idx)
                        Cats.save(c.settings.sp, cats)
                        selCat = if (selCat == idx) -1 else if (selCat > idx) selCat - 1 else selCat
                        renderCats()
                        toast("Đã xoá thể loại “${ct.name}”")
                    }
                }
                .setNegativeButton("GIỮ LẠI", null)
        )
        d.window?.setGravity(Gravity.CENTER)
        // Mac dinh dat tieu diem vao "GIU LAI" de bam nham OK khong bi xoa
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    // ------------------------------------------------------------------ periodic tick (1s)

    private fun tick(c: Core) {
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val now = System.currentTimeMillis()
        val connected = now - c.server.lastRemoteSeen < 8000
        remoteDot.set("dot", if (connected) GREEN else 0xFF666677.toInt())
        val who = c.controller.lastActorName()
        remoteText.text = if (connected) "Remote Đã Kết Nối" + (if (who != null) " ($who)" else "") else "Chờ điện thoại kết nối"
        remoteText.setTextColor(if (connected) GREEN else MUTED)

        updateNet()
        updateQr(c)
        qualText.text = "Tối đa ${c.settings.maxHeight}p · ${c.settings.backend}"

        val pg = c.controller.progress()
        timeL.text = mmss(pg[0])
        timeR.text = mmss(pg[1])
        bar.frac = if (pg[1] > 0) pg[0].toFloat() / pg[1] else 0f
        volSlider.frac = c.controller.currentVolume() / 100f

    }

    /**
     * Tu phong to sau N giay (Cai dat) khong bam phim remote TV; N = 0 la tat.
     * 5 giay cuoi hien so dem nguoc 5 -> 0 o giua video nho.
     */
    private fun autoFullscreenCheck(c: Core) {
        val fsSec = c.settings.fullscreenSec
        val eligible = fsSec > 0 && !fullscreen && lastUi.state == PState.PLAYING && dialogs == 0 && !searchEt.hasFocus()
        if (!eligible) {
            if (countdownTv.visibility != View.GONE) countdownTv.visibility = View.GONE
            return
        }
        val remain = fsSec * 1000L - (SystemClock.elapsedRealtime() - lastControlAt)
        if (remain < -300L) {
            countdownTv.visibility = View.GONE
            enterFullscreen()
        } else if (remain <= 5000L) {
            val n = maxOf(0L, (remain + 999L) / 1000L).toInt()
            if (countdownTv.text.toString() != n.toString()) countdownTv.text = n.toString()
            if (countdownTv.visibility != View.VISIBLE) countdownTv.visibility = View.VISIBLE
        } else if (countdownTv.visibility != View.GONE) countdownTv.visibility = View.GONE
    }

    private fun updateNet() {
        var text = "Offline"
        var icon = "wifi"
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    text = "Mạng LAN"
                    icon = "lan"
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val wi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
                    val f = wi?.frequency ?: 0
                    val sp = wi?.linkSpeed ?: 0
                    val band = when {
                        f >= 5900 -> "6GHz"
                        f >= 4900 -> "5GHz"
                        f > 0 -> "2.4GHz"
                        else -> "Wi-Fi"
                    }
                    text = if (sp > 0) "$band · ${sp}Mbps" else band
                } else text = "Có mạng"
            }
        } catch (e: Exception) {
        }
        wifiText.text = text
        wifiIcon.set(icon)
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints)
        val n = m.width
        val quiet = 4
        val total = n + quiet * 2
        val k = maxOf(1, size / total)
        val side = total * k
        val px = IntArray(side * side) { Color.WHITE }
        for (y in 0 until n) for (x in 0 until n) {
            if (m.get(x, y)) {
                val ox = (x + quiet) * k
                val oy = (y + quiet) * k
                for (dy in 0 until k) {
                    val row = (oy + dy) * side + ox
                    for (dx in 0 until k) px[row + dx] = Color.BLACK
                }
            }
        }
        return Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
    }




    private fun updateQr(c: Core) {
        val ip = c.ip.value
        portPill.text = "CỔNG ${c.port}"
        if (ip == null) {
            qrImg.visibility = View.INVISIBLE
            qrCap.text = "Hãy kết nối Wi-Fi để dùng mã QR"
            qrUrl.text = ""
            lastQrUrl = null
            return
        }
        qrImg.visibility = View.VISIBLE
        qrCap.text = "Mở camera quét để điều khiển"
        val url = "http://$ip:${c.port}/?token=${c.settings.token}"
        if (url != lastQrUrl) {
            lastQrUrl = url
            qrImg.setImageBitmap(makeQr(url, 600))
            qrUrl.text = "http://$ip:${c.port}"
        }
    }

    private fun showVolume(v: Int) {
        volText.text = "Âm lượng: $v%"
        volBar.progress = v
        volSlider.frac = v / 100f
        volBox.visibility = View.VISIBLE
        volBox.removeCallbacks(volHide)
        volBox.postDelayed(volHide, 1800)
    }

    // ------------------------------------------------------------------ voice / ime

    private fun hideIme() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchEt.windowToken, 0)
    }

    private fun startVoice() {
        // Chu dong tam dung video khi tim bang giong noi roi chac chan phat lai khi xong
        val c = core
        voiceWasPlaying = c != null && lastUi.state == PState.PLAYING
        if (voiceWasPlaying) c?.controller?.pause()
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên bài hát hoặc ca sĩ…")
            startActivityForResult(i, REQ_VOICE)
        } catch (e: Exception) {
            toast("TV này chưa hỗ trợ tìm bằng giọng nói")
            resumeAfterVoice()
        }
    }

    /** Phat lai video sau giong noi: thu nhieu lan vi he thong co the con dang giu audio/man hinh. */
    private fun resumeAfterVoice() {
        if (!voiceWasPlaying) return
        voiceWasPlaying = false
        scope.launch {
            for (d in longArrayOf(0, 400, 1200, 2500)) {
                delay(d)
                core?.controller?.forceResume()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILES || requestCode == REQ_PICK_PLAY || requestCode == REQ_PICK_TREE) {
            resumeAfterVoice()
            if (resultCode == RESULT_OK && data != null) handlePick(requestCode, data)
            return
        }
        if (requestCode == REQ_VOICE) resumeAfterVoice()
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!t.isNullOrBlank()) {
                searchEt.setText(t)
                doTvSearch(t)
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    // ------------------------------------------------------------------ cac hop thoai moi (Danh Muc, QR, Dan Link, Tai xuong, Thu vien)

    private fun buildQrCardOnce() {
        qrCard = buildQrCard()
    }

    private fun showSheet(content: View, widthDp: Int): AlertDialog {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(p(28), p(24), p(28), p(22))
        wrap.addView(content, lin(-1, -2))
        val d = track(AlertDialog.Builder(this).setView(wrap))
        d.window?.setBackgroundDrawable(box(PANEL, 28, 2, BORDER))
        d.window?.setLayout(p(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.window?.setGravity(Gravity.CENTER)
        return d
    }

    private fun sheetBtn(text: String, red: Boolean, onClick: () -> Unit): TextView {
        val b = lbl(text, 22, if (red) Color.WHITE else TXT, true)
        b.gravity = Gravity.CENTER
        focusable(
            b,
            if (red) box(RED, 20) else box(BTN, 20, 2, BTN_BORDER),
            box(if (red) RED else FOCUS2, 20, 3, CYAN)
        )
        b.setOnClickListener { onClick() }
        return b
    }

    private fun showQr() {
        (qrCard.parent as? ViewGroup)?.removeView(qrCard)
        val c = core
        if (c != null) updateQr(c)
        showSheet(qrCard, 480)
    }

    /** Menu cua mot bai (giu OK / MENU tren the bai). */
    private fun showCardMenu(h: SearchHit) {
        if (h.videoId.startsWith("local:")) { addHit(h, false); return }
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.addView(lbl(h.title, 22, MUTED, false, 2), lin(-1, -2, b = 14))
        var dlg: AlertDialog? = null
        fun item(text: String, red: Boolean = false, act: () -> Unit) {
            val b = sheetBtn(text, red) { dlg?.dismiss(); act() }
            body.addView(b, lin(-1, p(56), t = 10))
        }
        val fav = isFav(h.videoId)
        item(if (fav) "💔  Xoá khỏi Yêu thích" else "❤  Thêm Yêu thích") { if (fav) removeFav(h) else addFav(h) }
        item("⬇  Tải nhạc") { downloadHit(h) }
        item("▶  Phát ngay", true) { playNowItem(h.videoId, h.title, h.channel, h.durationSec) }
        item("＋  Thêm vào hàng đợi") { addHit(h, false) }
        item("📺  Xem video kênh này") { openChannel(h) }
        dlg = showSheet(body, 560)
        (body.getChildAt(1) as? View)?.post { body.getChildAt(1).requestFocus() }
    }

    private fun showCatMenu() {
        (catBox.parent as? ViewGroup)?.removeView(catBox)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val add = LinearLayout(this)
        add.orientation = LinearLayout.HORIZONTAL
        add.gravity = Gravity.CENTER
        val dashed = GradientDrawable()
        dashed.setColor(Color.TRANSPARENT)
        dashed.cornerRadius = p(22).toFloat()
        dashed.setStroke(maxOf(1, p(2)), BORDER, p(8).toFloat(), p(6).toFloat())
        focusable(add, dashed, box(FOCUS, 22, 3, CYAN))
        add.addView(IconView(this, "plus", TXT), lin(p(26), p(26)))
        add.addView(lbl("Tạo Thể Loại Mới", 22, TXT), lin(-2, -2, l = 10))
        add.setOnClickListener {
            catDialog?.dismiss()
            createCategoryDialog()
        }
        body.addView(add, lin(-1, p(58)))
        body.addView(sectionLabel("DANH MỤC ÂM NHẠC"), lin(-1, -2, l = 8, t = 14, b = 8))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(catBox, FrameLayout.LayoutParams(-1, -2))
        body.addView(sv, lin(-1, p(430)))
        val d = showSheet(body, 560)
        catDialog = d
        // Hien ngay duoi nut Danh Muc nhu menu tha xuong
        try {
            val loc = IntArray(2)
            catBtnView.getLocationOnScreen(loc)
            val w = d.window
            if (w != null) {
                val lp = w.attributes
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = (loc[0] + catBtnView.width - p(560)).coerceAtLeast(0)
                lp.y = loc[1] + catBtnView.height + p(8)
                lp.dimAmount = 0.35f
                w.attributes = lp
            }
        } catch (e: Exception) {
        }
        renderCats()
        val tgt = catBox.getChildAt(selCat.coerceAtLeast(0).coerceAtMost(maxOf(0, catBox.childCount - 1)))
        tgt?.requestFocus()
    }

    private fun showPasteLink() {
        val et = EditText(this)
        et.hint = "Dán link YouTube (https://youtu.be/...)"
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.text?.toString()
            if (!t.isNullOrBlank() && t.contains("youtu")) et.setText(t)
        } catch (e: Exception) {
        }
        val d = track(
            AlertDialog.Builder(this)
                .setTitle("Dán link YouTube")
                .setView(et)
                .setPositiveButton("Thêm") { _, _ -> addFromLink(et.text.toString()) }
                .setNegativeButton("Hủy", null)
        )
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun addFromLink(raw: String) {
        val c = core ?: return
        val q = raw.trim()
        val id = if (q.contains("youtu")) Util.parseVideoId(q) else null
        if (id == null) {
            toast("Link YouTube không hợp lệ")
            return
        }
        scope.launch {
            val m = withContext(Dispatchers.IO) { Resolver.meta(id) }
            c.controller.addFromTv(
                id, m?.title?.takeIf { it.isNotEmpty() } ?: "YouTube · $id",
                m?.channel ?: "", m?.durationSec ?: 0, false
            )
            toast("Đã thêm bài từ link")
        }
    }

    // ---- tai xuong ----

    private fun markDl(di: IconView, videoId: String) {
        val job = Downloads.jobs[videoId]
        when {
            Downloads.localFile(videoId) != null -> di.set("check", TEAL)
            job != null && job.error == null && !job.done -> di.set("dot", CYAN)
            else -> di.set("download", Color.WHITE)
        }
    }

    private fun refreshDlMarks() {
        for (cv in cardViews) {
            val tg = cv.tag as? CardTag ?: continue
            val di = cv.findViewWithTag<View>("dlicon") as? IconView ?: continue
            markDl(di, tg.hit.videoId)
        }
    }



    private fun downloadCurrent() {
        val cur = core?.controller?.currentItem()
        if (cur == null) {
            toast("Chưa có bài đang phát")
            return
        }
        downloadHit(SearchHit(cur.videoId, cur.title, cur.channel, cur.durationSec))
    }




    private fun filterEdit(hint: String, onChange: (String) -> Unit): EditText {
        val et = EditText(this)
        et.hint = hint
        et.setHintTextColor(MUTED)
        et.setTextColor(TXT)
        et.sz(22)
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.background = box(PANEL2, 18, 2, BORDER)
        et.setPadding(p(18), p(10), p(18), p(10))
        et.setOnFocusChangeListener { _, f -> et.background = if (f) box(PANEL2, 18, 3, CYAN) else box(PANEL2, 18, 2, BORDER) }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { onChange(e?.toString() ?: "") }
        })
        return et
    }

    private fun sectionLabel(t: String): TextView = lbl(t, 18, MUTED, true)

    private fun playNowItem(id: String, title: String, channel: String, dur: Int) {
        val c = core ?: return
        try {
            val q = c.controller.add(id, title, channel, dur, "TV", "next", true)
            try { c.controller.playNow(q.itemId) } catch (e: ApiError) { }
        } catch (e: ApiError) {
            toast("Không thêm được bài này")
        }
    }

    // ---- Tai san ve may ----

    private fun downloadHit(h: SearchHit) {
        val c = core ?: return
        if (h.videoId.startsWith("local:")) return
        if (Downloads.localFile(h.videoId) != null) {
            toast("Bài này đã tải về rồi")
            return
        }
        val j = Downloads.jobs[h.videoId]
        if (j != null && !j.done && j.error == null) {
            toast("Bài này đang được tải")
            return
        }
        val audio = c.settings.dlMode == "audio"
        toast("Đang tải: ${h.title}")
        scope.launch {
            val err = withContext(Dispatchers.IO) { Downloads.download(h.videoId, h.title, h.channel, h.durationSec, audio, c.settings.dlHeight) }
            toast(if (err == null) "Đã tải xong: ${h.title}" else "Tải lỗi: $err")
        }
    }

    private fun downloadMany(list: List<SearchHit>) {
        val c = core ?: return
        val todo = list.filter { it.kind == "video" && !it.videoId.startsWith("local:") && Downloads.localFile(it.videoId) == null }
        if (todo.isEmpty()) {
            toast("Không có bài nào cần tải")
            return
        }
        val audio = c.settings.dlMode == "audio"
        val gen = Downloads.cancelGen
        toast("Bắt đầu tải ${todo.size} bài")
        scope.launch {
            for (h in todo) {
                if (Downloads.cancelGen != gen) break
                withContext(Dispatchers.IO) { Downloads.download(h.videoId, h.title, h.channel, h.durationSec, audio, c.settings.dlHeight) }
            }
            toast("Đã xong tác vụ tải")
        }
    }

    private fun onDownloadsChanged() {
        val n = Downloads.activeCount()
        dlBadge.text = n.toString()
        dlBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
        refreshDlMarks()
        if (dlDialog?.isShowing == true) {
            renderDlProgress()
            val cnt = Downloads.list().size
            if (cnt != dlLastCount) {
                dlLastCount = cnt
                renderDlLibrary()
                renderDlScopes()
            }
        }
    }

    private fun showDownloads() {
        val c = core ?: return
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(lbl("Tải sẵn về máy", 32, TXT, true), lin(-1, -2))
        root.addView(
            lbl("Bài đã tải sẽ phát từ file trong máy, không cần mạng. Bài nào mất file thì app tự phát trực tuyến.", 18, MUTED, false, 2),
            lin(-1, -2, t = 6)
        )
        // Thu muc luu
        val fr = LinearLayout(this)
        fr.orientation = LinearLayout.HORIZONTAL
        fr.gravity = Gravity.CENTER_VERTICAL
        val pathBox = LinearLayout(this)
        pathBox.orientation = LinearLayout.HORIZONTAL
        pathBox.gravity = Gravity.CENTER_VERTICAL
        pathBox.background = box(PANEL2, 18, 2, BORDER)
        pathBox.setPadding(p(16), 0, p(16), 0)
        pathBox.addView(IconView(this, "folder", 0xFFFFC107.toInt()), lin(p(24), p(24)))
        pathBox.addView(lbl(Downloads.folderPath(), 18, TXT, true, 1), lin(0, -2, 1f, l = 10))
        fr.addView(pathBox, lin(0, p(56), 1f))
        fr.addView(sheetBtn("Quét lại", false) {
            dlLastCount = -1
            onDownloadsChanged()
            toast("Đã quét lại thư mục")
        }, lin(p(140), p(56), l = 10))
        root.addView(fr, lin(-1, -2, t = 12))

        var styleQualHook: (() -> Unit)? = null
        // Kieu tai
        val mr = LinearLayout(this)
        mr.orientation = LinearLayout.HORIZONTAL
        mr.gravity = Gravity.CENTER_VERTICAL
        mr.addView(lbl("Kiểu tải:", 18, MUTED), lin(-2, -2, r = 12))
        val bVideo = sheetBtn("Hình + tiếng", false) {}
        val bAudio = sheetBtn("Chỉ tiếng (m4a)", false) {}
        fun styleMode() {
            val vid = c.settings.dlMode != "audio"
            for ((b, on) in listOf(bVideo to vid, bAudio to !vid)) {
                focusable(
                    b,
                    if (on) box(0xFF00838F.toInt(), 20) else box(BTN, 20, 2, BTN_BORDER),
                    box(if (on) 0xFF00838F.toInt() else FOCUS2, 20, 3, CYAN)
                )
            }
        }
        bVideo.setOnClickListener { c.settings.dlMode = "video"; styleMode(); styleQualHook?.invoke() }
        bAudio.setOnClickListener { c.settings.dlMode = "audio"; styleMode(); styleQualHook?.invoke() }
        styleMode()
        mr.addView(bVideo, lin(p(190), p(50), r = 10))
        mr.addView(bAudio, lin(p(230), p(50)))
        root.addView(mr, lin(-1, -2, t = 12))

        // Chat luong tai (chi ap dung khi tai hinh + tieng)
        val qr = LinearLayout(this)
        qr.orientation = LinearLayout.HORIZONTAL
        qr.gravity = Gravity.CENTER_VERTICAL
        qr.addView(lbl("Chất lượng:", 18, MUTED), lin(-2, -2, r = 12))
        val qOpts = listOf(360 to "360p", 720 to "720p", 1080 to "1080p", 9999 to "Cao nhất")
        val qBtns = qOpts.map { (h, name) -> sheetBtn(name, false) { } }
        fun styleQual() {
            val enabled = c.settings.dlMode != "audio"
            for ((i, b) in qBtns.withIndex()) {
                val on = enabled && c.settings.dlHeight == qOpts[i].first
                focusable(
                    b,
                    if (on) box(0xFF00838F.toInt(), 20) else box(BTN, 20, 2, BTN_BORDER),
                    box(if (on) 0xFF00838F.toInt() else FOCUS2, 20, 3, CYAN)
                )
                b.alpha = if (enabled) 1f else 0.45f
            }
        }
        for ((i, b) in qBtns.withIndex()) {
            b.setOnClickListener {
                if (c.settings.dlMode == "audio") { toast("Đang chọn Chỉ tiếng — chọn Hình + tiếng để đổi chất lượng") }
                else { c.settings.dlHeight = qOpts[i].first; styleQual() }
            }
            qr.addView(b, lin(p(if (i == 3) 160 else 120), p(50), r = 10))
        }
        styleQual()
        styleQualHook = { styleQual() }
        root.addView(qr, lin(-1, -2, t = 10))
        root.addView(
            lbl("Trên 720p app tải hình và tiếng riêng rồi ghép lại: file nặng hơn, tải lâu hơn. Video không có chất lượng đã chọn sẽ tự lấy mức gần nhất.", 16, MUTED, false, 2),
            lin(-1, -2, t = 6)
        )

        // Pham vi tai
        root.addView(sectionLabel("PHẠM VI TẢI"), lin(-1, -2, t = 18, b = 6))
        val sb = LinearLayout(this)
        sb.orientation = LinearLayout.VERTICAL
        dlScopeBox = sb
        root.addView(sb, lin(-1, -2))

        // Bai da tai san
        val lh = LinearLayout(this)
        lh.orientation = LinearLayout.HORIZONTAL
        lh.gravity = Gravity.CENTER_VERTICAL
        val lt = sectionLabel("")
        dlLibTitle = lt
        lh.addView(lt, lin(0, -2, 1f))
        lh.addView(sheetBtn("+ Thêm tất cả vào hàng đợi", false) {
            val l = Downloads.list().map { SearchHit(it.videoId, it.title, it.channel, it.durationSec) }
            val n = core?.controller?.addMany(l, "TV") ?: 0
            toast(if (n > 0) "Đã thêm $n bài vào hàng đợi" else "Không thêm được bài nào")
        }, lin(-2, p(46)))
        root.addView(lh, lin(-1, -2, t = 18, b = 8))
        root.addView(filterEdit("Lọc theo tên bài đã tải…") { dlFilter = it; renderDlLibrary() }, lin(-1, p(58)))
        val lb = LinearLayout(this)
        lb.orientation = LinearLayout.VERTICAL
        dlLibBox = lb
        val lsv = ScrollView(this)
        lsv.isVerticalScrollBarEnabled = false
        lsv.addView(lb, FrameLayout.LayoutParams(-1, -2))
        root.addView(lsv, lin(-1, p(230), t = 8))

        // Tien do
        val ph = LinearLayout(this)
        ph.orientation = LinearLayout.HORIZONTAL
        ph.gravity = Gravity.CENTER_VERTICAL
        ph.addView(sectionLabel("TIẾN ĐỘ"), lin(0, -2, 1f))
        ph.addView(sheetBtn("Huỷ tất cả", false) {
            Downloads.cancelAll()
            toast("Đã huỷ các tác vụ tải")
        }, lin(p(170), p(46), r = 8))
        ph.addView(sheetBtn("Xoá dòng đã xong", false) { Downloads.clearFinished() }, lin(p(230), p(46)))
        root.addView(ph, lin(-1, -2, t = 16, b = 6))
        val pb = LinearLayout(this)
        pb.orientation = LinearLayout.VERTICAL
        dlProgBox = pb
        root.addView(pb, lin(-1, -2))

        val foot = lbl("", 18, MUTED, false, 1)
        dlFoot = foot
        root.addView(foot, lin(-1, -2, t = 14))

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(root, FrameLayout.LayoutParams(-1, -2))
        val holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        holder.addView(sv, lin(-1, p(760)))
        var dlg: AlertDialog? = null
        val closeRow = LinearLayout(this)
        closeRow.orientation = LinearLayout.HORIZONTAL
        closeRow.addView(spacer(), lin(0, 1, 1f))
        closeRow.addView(sheetBtn("ĐÓNG", true) { dlg?.dismiss() }, lin(p(160), p(56)))
        holder.addView(closeRow, lin(-1, -2, t = 12))
        dlLastCount = Downloads.list().size
        renderDlScopes()
        renderDlLibrary()
        renderDlProgress()
        dlg = showSheet(holder, 1100)
        dlDialog = dlg
    }

    private fun renderDlScopes() {
        val b = dlScopeBox ?: return
        val c = core ?: return
        b.removeAllViews()
        val favL = favs.map { SearchHit(it.videoId, it.title, it.channel, it.durationSec) }
        val qL = ArrayList<SearchHit>()
        c.controller.currentItem()?.let { qL.add(SearchHit(it.videoId, it.title, it.channel, it.durationSec)) }
        c.controller.queueItems().forEach { qL.add(SearchHit(it.videoId, it.title, it.channel, it.durationSec)) }
        for ((name, list) in listOf("Yêu thích" to favL, "Hàng đợi hiện tại" to qL)) {
            val ok = list.filter { !it.videoId.startsWith("local:") }
            val have = ok.count { Downloads.localFile(it.videoId) != null }
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(p(8), p(6), p(8), p(6))
            r.addView(lbl(name, 24, TXT, true), lin(0, -2, 1f))
            val chip = lbl("đã tải $have/${ok.size}", 17, MUTED, true)
            chip.background = box(PANEL2, 16)
            chip.setPadding(p(12), p(4), p(12), p(4))
            r.addView(chip, lin(-2, -2, r = 12))
            r.addView(sheetBtn("Tải về", true) { downloadMany(ok) }, lin(p(120), p(46)))
            b.addView(r, lin(-1, -2))
        }
    }

    private fun renderDlLibrary() {
        val lb = dlLibBox ?: return
        val all = Downloads.list()
        dlLibTitle?.text = "BÀI ĐÃ TẢI SẴN · ${all.size} bài"
        dlFoot?.text = "${all.size} bài đã tải sẵn trong thư mục này"
        val f = dlFilter.trim().lowercase()
        val list = if (f.isEmpty()) all else all.filter { it.title.lowercase().contains(f) || it.channel.lowercase().contains(f) }
        lb.removeAllViews()
        if (list.isEmpty()) {
            lb.addView(lbl(if (all.isEmpty()) "Chưa có bài nào được tải sẵn." else "Không có bài khớp bộ lọc.", 19, MUTED), lin(-1, -2, l = 8, t = 8))
            return
        }
        for (e in list.take(100)) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(p(8), p(6), p(8), p(6))
            r.addView(IconView(this, "note", PINK), lin(p(26), p(26)))
            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            info.addView(lbl(e.title, 22, TXT, true, 1), lin(-1, -2))
            val kind = if (e.file.extension == "m4a") "Chỉ tiếng" else "Hình + tiếng"
            val mb = String.format(Locale.US, "%d MB", maxOf(1L, e.file.length() / 1048576L))
            info.addView(lbl("$kind · $mb", 16, MUTED, false, 1), lin(-1, -2, t = 2))
            r.addView(info, lin(0, -2, 1f, l = 12))
            r.addView(sheetBtn("Phát ngay", true) { playNowItem(e.videoId, e.title, e.channel, e.durationSec) }, lin(p(130), p(44), l = 8))
            r.addView(sheetBtn("+ Hàng đợi", false) { addHit(SearchHit(e.videoId, e.title, e.channel, e.durationSec), false) }, lin(p(140), p(44), l = 8))
            r.addView(sheetBtn("Xoá", false) {
                Downloads.delete(e.videoId)
                toast("Đã xoá: ${e.title}")
            }, lin(p(80), p(44), l = 8))
            lb.addView(r, lin(-1, -2))
        }
    }

    private fun renderDlProgress() {
        val pb = dlProgBox ?: return
        pb.removeAllViews()
        val jobs = Downloads.jobs.values.toList()
        if (jobs.isEmpty()) {
            pb.addView(lbl("Chưa có bài nào đang tải. Bấm \"Tải về\" ở một phạm vi phía trên.", 19, MUTED), lin(-1, -2, l = 8, t = 4))
            return
        }
        for (j in jobs) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(p(8), p(6), p(8), p(6))
            r.addView(lbl(j.title.ifBlank { j.videoId }, 21, TXT, false, 1), lin(0, -2, 1f))
            val st = when {
                j.error != null -> lbl("Lỗi: ${j.error}", 17, RED, true, 1)
                j.done -> lbl("Xong", 19, GREEN, true)
                else -> lbl("${j.pct}%", 19, CYAN, true)
            }
            r.addView(st, lin(-2, -2, l = 12))
            pb.addView(r, lin(-1, -2))
        }
    }

    // ---- Nhac & video trong may ----

    private fun showLocal() {
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.addView(lbl("Nhạc & video trong máy", 32, TXT, true), lin(-1, -2))
        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        br.addView(sheetBtn("Chọn & phát ngay…", true) { pickFiles(true) }, lin(p(290), p(58), r = 10))
        br.addView(sheetBtn("+ Thêm file…", false) { pickFiles(false) }, lin(p(190), p(58), r = 10))
        br.addView(sheetBtn("+ Thêm thư mục…", false) { pickTree() }, lin(p(240), p(58)))
        body.addView(br, lin(-1, -2, t = 14))
        body.addView(
            lbl("Phát được: mp3, m4a, wav, flac, ogg, opus, aac · mp4, mkv, webm, mov, m4v", 17, MUTED, false, 2),
            lin(-1, -2, t = 8)
        )
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        localChipBox = chips
        val hs = android.widget.HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chips, FrameLayout.LayoutParams(-2, -2))
        body.addView(hs, lin(-1, -2, t = 10))
        body.addView(filterEdit("Lọc theo tên bài hoặc tên thư mục") { localFilter = it; renderLocalList() }, lin(-1, p(58), t = 10))
        val st = lbl("", 17, MUTED, false, 1)
        localStatus = st
        body.addView(st, lin(-1, -2, l = 6, t = 6))
        val lb = LinearLayout(this)
        lb.orientation = LinearLayout.VERTICAL
        localListBox = lb
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(lb, FrameLayout.LayoutParams(-1, -2))
        body.addView(sv, lin(-1, p(400), t = 4))
        var dlg: AlertDialog? = null
        val fr = LinearLayout(this)
        fr.orientation = LinearLayout.HORIZONTAL
        fr.gravity = Gravity.END
        val addAll = sheetBtn("THÊM TẤT CẢ VÀO HÀNG ĐỢI (0)", true) {
            val l = filteredLocal().map { SearchHit(it.id, it.title, it.folder, 0) }
            val n = core?.controller?.addMany(l, "TV") ?: 0
            toast(if (n > 0) "Đã thêm $n bài vào hàng đợi" else "Không thêm được bài nào")
        }
        localAddAll = addAll
        fr.addView(addAll, lin(p(520), p(60), r = 12))
        fr.addView(sheetBtn("ĐÓNG", false) { dlg?.dismiss() }, lin(p(150), p(60)))
        body.addView(fr, lin(-1, -2, t = 14))
        dlg = showSheet(body, 1060)
        localDlg = dlg
        rescanLocal()
    }

    private fun filteredLocal(): List<LocalItem> {
        val f = localFilter.trim().lowercase()
        return if (f.isEmpty()) localItems else localItems.filter { it.title.lowercase().contains(f) || it.folder.lowercase().contains(f) }
    }

    private fun rescanLocal() {
        localStatus?.text = "Đang quét…"
        scope.launch {
            val items = withContext(Dispatchers.IO) { LocalLib.scan() }
            localItems = items
            renderLocalChips()
            renderLocalList()
            val ids = pendingPlayIds
            pendingPlayIds = emptyList()
            if (ids.isNotEmpty()) {
                val got = ids.mapNotNull { id -> items.firstOrNull { it.id == id } }
                got.firstOrNull()?.let { playNowItem(it.id, it.title, it.folder, 0) }
                got.drop(1).forEach { addHit(SearchHit(it.id, it.title, it.folder, 0), false) }
            }
        }
    }

    private fun renderLocalChips() {
        val cb = localChipBox ?: return
        cb.removeAllViews()
        fun chip(label: String, onRemove: () -> Unit) {
            val ch = LinearLayout(this)
            ch.orientation = LinearLayout.HORIZONTAL
            ch.gravity = Gravity.CENTER_VERTICAL
            ch.background = box(PANEL2, 22, 2, BORDER)
            ch.setPadding(p(14), p(4), p(6), p(4))
            ch.addView(IconView(this, "folder", 0xFFFFC107.toInt()), lin(p(22), p(22)))
            ch.addView(lbl(label, 19, TXT, false, 1), lin(-2, -2, l = 8, r = 8))
            val x = FrameLayout(this)
            focusable(x, box(BTN, 18), box(FOCUS2, 18, 3, CYAN))
            x.addView(IconView(this, "close", TXT), FrameLayout.LayoutParams(p(18), p(18), Gravity.CENTER))
            x.setOnClickListener { onRemove() }
            ch.addView(x, lin(p(34), p(34)))
            cb.addView(ch, lin(-2, -2, r = 10))
        }
        for (t in LocalLib.trees()) chip(LocalLib.treeLabel(t)) {
            LocalLib.removeTree(t)
            rescanLocal()
        }
        val nf = LocalLib.files().size
        if (nf > 0) chip("$nf file lẻ") {
            LocalLib.clearFiles()
            rescanLocal()
        }
    }

    private fun renderLocalList() {
        val lb = localListBox ?: return
        val list = filteredLocal()
        localAddAll?.text = "THÊM TẤT CẢ VÀO HÀNG ĐỢI (${list.size})"
        localStatus?.text = when {
            localItems.isEmpty() -> "Chưa có nhạc/video nào. Bấm “+ Thêm thư mục…” hoặc “+ Thêm file…”."
            list.size > 150 -> "Hiển thị 150/${list.size} bài, gõ để lọc"
            else -> "${list.size} bài"
        }
        lb.removeAllViews()
        for (it in list.take(150)) {
            val item = it
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(p(8), p(6), p(8), p(6))
            r.addView(IconView(this, "note", PINK), lin(p(26), p(26)))
            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            info.addView(lbl(item.title, 22, TXT, true, 1), lin(-1, -2))
            val mb = String.format(Locale.US, "%d MB", maxOf(1L, item.sizeBytes / 1048576L))
            info.addView(lbl("${item.folder} · $mb${if (item.isVideo) " · video" else ""}", 16, MUTED, false, 1), lin(-1, -2, t = 2))
            r.addView(info, lin(0, -2, 1f, l = 12))
            r.addView(sheetBtn("Phát ngay", true) { playNowItem(item.id, item.title, item.folder, 0) }, lin(p(140), p(46), l = 8))
            r.addView(sheetBtn("+ Hàng đợi", false) { addHit(SearchHit(item.id, item.title, item.folder, 0), false) }, lin(p(150), p(46), l = 8))
            lb.addView(r, lin(-1, -2))
        }
    }

    private fun pauseForPicker() {
        val c = core
        voiceWasPlaying = c != null && lastUi.state == PState.PLAYING
        if (voiceWasPlaying) c?.controller?.pause()
    }

    private fun pickFiles(playNow: Boolean) {
        pauseForPicker()
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            startActivityForResult(i, if (playNow) REQ_PICK_PLAY else REQ_PICK_FILES)
        } catch (e: Exception) {
            toast("TV này chưa hỗ trợ chọn file")
            resumeAfterVoice()
        }
    }

    private fun pickTree() {
        pauseForPicker()
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            startActivityForResult(i, REQ_PICK_TREE)
        } catch (e: Exception) {
            toast("TV này chưa hỗ trợ chọn thư mục")
            resumeAfterVoice()
        }
    }

    private fun handlePick(req: Int, data: Intent) {
        if (req == REQ_PICK_TREE) {
            val u = data.data ?: return
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
            LocalLib.addTree(u.toString())
        } else {
            val uris = ArrayList<android.net.Uri>()
            data.data?.let { uris.add(it) }
            val cd = data.clipData
            if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) }
            if (uris.isEmpty()) return
            for (u in uris) {
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
            }
            val strs = uris.map { it.toString() }.distinct()
            LocalLib.addFiles(strs)
            if (req == REQ_PICK_PLAY) pendingPlayIds = strs.map { LocalLib.idOf(it) }
        }
        rescanLocal()
    }

    private fun showSettings() {
        val c = core ?: return
        val s = c.settings
        val items = arrayOf(
            "Trình phát: ${s.backend}",
            "Độ phân giải tối đa: ${s.maxHeight}p",
            "Tự phát bài gợi ý khi hàng đợi trống: ${if (s.seedAutoplay) "BẬT" else "TẮT"}",
            "Số bài mỗi hàng kết quả: ${s.gridCols}",
            "Tự phóng lớn video sau: ${if (s.fullscreenSec == 0) "TẮT" else "${s.fullscreenSec} giây"}",
            "Khôi phục 5 thể loại mặc định",
            "Đặt lại ghép nối (ngắt mọi điện thoại)",
            "Xong"
        )
        track(
            AlertDialog.Builder(this)
                .setTitle("Cài đặt Youtube Player")
                .setItems(items) { d, which ->
                    d.dismiss()
                    when (which) {
                        0 -> s.backend = when (s.backend) { "AUTO" -> "EXOPLAYER"; "EXOPLAYER" -> "IFRAME"; else -> "AUTO" }
                        1 -> c.controller.setMaxHeight(when (s.maxHeight) { 360 -> 480; 480 -> 720; 720 -> 1080; else -> 360 })
                        2 -> s.seedAutoplay = !s.seedAutoplay
                        3 -> { s.gridCols = when (s.gridCols) { 1 -> 2; 2 -> 3; 3 -> 4; else -> 1 }; applyGridCols(s.gridCols) }
                        4 -> {
                            val opts = intArrayOf(3, 5, 10, 15, 30, 60, 0)
                            val cur = opts.indexOf(s.fullscreenSec)
                            s.fullscreenSec = opts[(cur + 1) % opts.size]
                            lastControlAt = SystemClock.elapsedRealtime()
                        }
                        5 -> {
                            val have = cats.map { it.name }
                            Cats.defaults().filter { it.name !in have }.reversed().forEach { cats.add(0, it) }
                            Cats.save(c.settings.sp, cats)
                            selCat = -1
                            renderCats()
                            toast("Đã khôi phục các thể loại mặc định")
                        }
                        6 -> { confirmReset(); return@setItems }
                        else -> return@setItems
                    }
                    showSettings()
                }
        )
    }

    private fun confirmReset() {
        val c = core ?: return
        track(
            AlertDialog.Builder(this)
                .setTitle("Đặt lại ghép nối?")
                .setMessage("Tất cả điện thoại đã kết nối sẽ bị ngắt. Quét lại mã QR để kết nối.")
                .setPositiveButton("Đặt lại") { _, _ -> c.settings.token = Util.newToken(); updateQr(c) }
                .setNegativeButton("Hủy", null)
        )
    }

    private fun confirmExit() {
        val d = track(
            AlertDialog.Builder(this)
                .setTitle("Thoát Youtube Player?")
                .setPositiveButton("Ở lại", null)
                .setNegativeButton("Thoát") { _, _ ->
                    stopService(Intent(this, KaraokeService::class.java))
                    finishAndRemoveTask()
                }
        )
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    override fun onBackPressed() {
        // Dang xem ben trong mot playlist -> BACK quay lai ket qua tim kiem truoc do
        val q = core?.controller?.search?.value?.query ?: ""
        val back = plReturn
        if (SearchService.isSubQuery(q) && back != null) {
            plReturn = null
            tvSearch(back)
            return
        }
        confirmExit()
    }

    // ------------------------------------------------------------------ phim remote TV

    private fun isMediaKey(k: Int) = k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY ||
        k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_MEDIA_NEXT

    /** Moi phim bam tren remote TV = "co dieu khien": tinh lai 5 giay, dang toan man hinh thi thu nho ngay. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Phim VOL +/- tren remote TV: doi am luong app (= am luong he thong), hien thanh am luong, khong thu nho man hinh.
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val c = core
            if (c != null) {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    c.controller.changeVolume(if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 5 else -5)
                }
                return true
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            lastControlAt = SystemClock.elapsedRealtime()
            if (countdownTv.visibility != View.GONE) countdownTv.visibility = View.GONE
            if (fullscreen) {
                exitFullscreen()
                if (!isMediaKey(e.keyCode)) return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    /** Phim phong to video ngay lap tuc (nhan lan nua thi thu nho vi moi phim deu thu nho). */
    private fun isZoomKey(k: Int) = k == KeyEvent.KEYCODE_INFO || k == KeyEvent.KEYCODE_PROG_YELLOW ||
        k == KeyEvent.KEYCODE_CHANNEL_UP

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val c = core
        if (isZoomKey(keyCode) && event.repeatCount == 0) {
            val st = lastUi.state
            if (st == PState.PLAYING || st == PState.PAUSED) enterFullscreen()
            else toast("Chưa có video đang phát")
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_PROG_BLUE && event.repeatCount == 0) {
            val tag = currentFocus?.tag
            if (tag is CardTag && tag.hit.kind != "playlist") downloadHit(tag.hit) else downloadCurrent()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            when (val tag = currentFocus?.tag) {
                is CardTag -> if (tag.hit.kind == "playlist") addPlaylist(tag.hit) else showCardMenu(tag.hit)
                is QueueTag -> removeQueueItem(tag.item)
                else -> showSettings()
            }
            return true
        }
        if (c != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (lastUi.state == PState.PAUSED) c.controller.resume() else c.controller.pause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { c.controller.resume(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { c.controller.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { c.controller.skip(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ WebView + IframeHost (Backend B, giu nguyen)

    private fun ensureWeb(): WebView? {
        web?.let { return it }
        if (webFailed) return null
        return try {
            val w = WebView(this)
            w.visibility = View.GONE
            w.setBackgroundColor(Color.BLACK)
            val ws = w.settings
            ws.javaScriptEnabled = true
            ws.domStorageEnabled = true
            ws.mediaPlaybackRequiresUserGesture = false
            ws.allowFileAccess = false
            ws.allowContentAccess = false
            ws.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame

                override fun onPageFinished(view: WebView, url: String?) {
                    pageReady = true
                    pendingJs?.let { view.evaluateJavascript(it, null) }
                    pendingJs = null
                }
            }
            w.addJavascriptInterface(Bridge(), "KBridge")
            w.isFocusable = false
            videoBox.addView(w, 1, FrameLayout.LayoutParams(-1, -1))
            val html = assets.open("player.html").bufferedReader().use { it.readText() }
            w.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "utf-8", null)
            web = w
            w
        } catch (e: Throwable) {
            webFailed = true
            CrashLog.write(this, "WEBVIEW", e)
            null
        }
    }

    private fun js(code: String) {
        if (!pageReady) {
            pendingJs = code
            return
        }
        web?.evaluateJavascript(code, null)
    }

    override fun load(videoId: String, key: String, startSec: Int) {
        if (!Util.ID_RE.matches(videoId)) return
        val w = ensureWeb()
        if (w == null) {
            core?.controller?.iframeEvent("error", key, 0, 0)
            return
        }
        w.visibility = View.VISIBLE
        js("kLoad('$videoId','${key.filter { it.isLetterOrDigit() || it == '-' }}',$startSec)")
    }

    override fun pause() = js("kPause()")
    override fun resume() = js("kPlay()")
    override fun seek(ms: Long) = js("kSeek(${ms / 1000})")
    override fun setVolume(v: Int) = js("kVol($v)")
    override fun stop() {
        if (pageReady) web?.evaluateJavascript("kStop()", null)
        web?.visibility = View.GONE
    }

    inner class Bridge {
        @JavascriptInterface
        fun state(key: String, st: String) {
            core?.controller?.iframeEvent("ready", key, 0, 0)
        }

        @JavascriptInterface
        fun ended(key: String) {
            core?.controller?.iframeEvent("ended", key, 0, 0)
        }

        @JavascriptInterface
        fun error(key: String, code: Int) {
            core?.controller?.iframeEvent("error", key, code.toLong(), 0)
        }

        @JavascriptInterface
        fun pos(key: String, posMs: Double, durMs: Double) {
            core?.controller?.iframeEvent("pos", key, posMs.toLong(), if (durMs.isNaN()) 0L else durMs.toLong())
        }
    }
}
