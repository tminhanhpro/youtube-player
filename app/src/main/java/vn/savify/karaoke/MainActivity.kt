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
        const val BG = 0xFF08080C.toInt()
        const val PANEL = 0xFF111119.toInt()
        const val PANEL2 = 0xFF1A1A26.toInt()
        const val BORDER = 0xFF262634.toInt()
        const val RED = 0xFFFF1744.toInt()
        const val MUTED = 0xFF9A9AB0.toInt()
        const val CYAN = 0xFF29B6F6.toInt()
        const val PINK = 0xFFFF4D8D.toInt()
        const val GREEN = 0xFF2ECC71.toInt()
        const val REQ_VOICE = 77
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
    private lateinit var qrBody: LinearLayout
    private lateinit var qrToggle: TextView
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
        applyQrHidden(c.settings.qrHidden)
        if (gridCols != c.settings.gridCols) applyGridCols(c.settings.gridCols)
        renderCats()
        coreJobs.add(scope.launch { c.controller.ui.collect { renderUi(it) } })
        coreJobs.add(scope.launch { c.controller.search.collect { renderSearch(it) } })
        coreJobs.add(scope.launch { c.controller.karaoke.collect { renderKaraokeBtn(it) } })
        coreJobs.add(scope.launch { c.controller.reactions.collect { react.fire(it) } })
        coreJobs.add(scope.launch { c.controller.messages.collect { msgView.show(it.first, it.second) } })
        coreJobs.add(scope.launch { c.controller.volumeEvents.collect { showVolume(it) } })
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

    private fun lbl(text: String, size: Int, color: Int = Color.WHITE, bold: Boolean = false, lines: Int = 0): TextView {
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
        val n = gridCols.coerceIn(1, 3)
        return (centerWpx - p(14 * (n - 1)) - (if (n == 3) 0 else p(8))) / n
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
        val centerW = dm.widthPixels - p(64 + 400 + 520 + 32)
        centerWpx = centerW
        cardW = calcCardW()

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        dash = LinearLayout(this)
        dash.orientation = LinearLayout.VERTICAL
        dash.setBackgroundColor(BG)
        dash.setPadding(p(32), p(16), p(32), p(24))
        root.addView(dash, FrameLayout.LayoutParams(-1, -1))
        dash.addView(buildTopBar(), lin(-1, p(64)))
        val body = LinearLayout(this)
        body.orientation = LinearLayout.HORIZONTAL
        dash.addView(body, lin(-1, 0, 1f, t = 12))
        body.addView(buildLeft(), lin(p(400), -1))
        body.addView(buildCenter(), lin(0, -1, 1f, l = 16, r = 16))
        body.addView(buildRight(), lin(p(520), -1))

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

    private fun buildTopBar(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo_mark)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        row.addView(logo, lin(p(64), p(48)))
        row.addView(lbl("Youtube Player", 30, Color.WHITE, true), lin(-2, -2, l = 14))
        val tag = lbl("MUSIC TV", 18, Color.WHITE, true)
        tag.background = box(RED, 8)
        tag.setPadding(p(12), p(4), p(12), p(4))
        row.addView(tag, lin(-2, -2, l = 14))

        remoteDot = IconView(this, "dot", 0xFF666677.toInt())
        row.addView(remoteDot, lin(p(26), p(26), l = 40, r = 6))
        remoteText = lbl("Chờ điện thoại kết nối", 22, MUTED)
        row.addView(remoteText, lin(-2, -2))

        row.addView(spacer(), lin(0, 1, 1f))

        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.background = box(PANEL, 24, 2, BORDER)
        chip.setPadding(p(18), p(8), p(18), p(8))
        wifiIcon = IconView(this, "wifi", Color.WHITE)
        chip.addView(wifiIcon, lin(p(26), p(26)))
        wifiText = lbl("", 20, Color.WHITE)
        chip.addView(wifiText, lin(-2, -2, l = 8))
        row.addView(chip, lin(-2, -2, r = 28))

        clock = lbl("", 28, Color.WHITE, true)
        row.addView(clock, lin(-2, -2))
        return row
    }

    private fun buildLeft(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = box(PANEL, 28, 2, BORDER)
        card.setPadding(p(20), p(18), p(20), p(16))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(lbl("QUÉT KẾT NỐI\nREMOTE", 20, Color.WHITE, true), lin(0, -2, 1f))
        portPill = lbl("", 16, GREEN, true)
        portPill.background = box(0x222ECC71, 20, 2, GREEN)
        portPill.setPadding(p(10), p(6), p(10), p(6))
        head.addView(portPill, lin(-2, -2))
        // Nut An/Hien ma QR nam ben phai o hien thi cong
        qrToggle = lbl("Ẩn", 18, Color.WHITE, true)
        qrToggle.gravity = Gravity.CENTER
        qrToggle.setPadding(p(14), p(6), p(14), p(6))
        focusable(qrToggle, box(0xFF2A2A38.toInt(), 20, 2, 0xFF44445A.toInt()), box(0x33FFFFFF, 20, 3, CYAN))
        qrToggle.setOnClickListener {
            val c = core ?: return@setOnClickListener
            c.settings.qrHidden = !c.settings.qrHidden
            applyQrHidden(c.settings.qrHidden)
        }
        head.addView(qrToggle, lin(-2, -2, l = 8))
        card.addView(head, lin(-1, -2))
        qrBody = LinearLayout(this)
        qrBody.orientation = LinearLayout.VERTICAL
        card.addView(qrBody, lin(-1, -2))
        qrImg = ImageView(this)
        qrImg.scaleType = ImageView.ScaleType.FIT_XY
        qrImg.background = box(Color.WHITE, 16)
        val qlp = lin(p(270), p(270), t = 12)
        qlp.gravity = Gravity.CENTER_HORIZONTAL
        qrBody.addView(qrImg, qlp)
        qrCap = lbl("Mở camera quét để điều khiển", 19, Color.WHITE, true, 1)
        qrCap.gravity = Gravity.CENTER
        qrBody.addView(qrCap, lin(-1, -2, t = 12))
        qrUrl = lbl("", 16, MUTED, false, 1)
        qrUrl.gravity = Gravity.CENTER
        qrBody.addView(qrUrl, lin(-1, -2, t = 2))
        col.addView(card, lin(-1, -2))

        val add = LinearLayout(this)
        add.orientation = LinearLayout.HORIZONTAL
        add.gravity = Gravity.CENTER
        val dashed = GradientDrawable()
        dashed.setColor(Color.TRANSPARENT)
        dashed.cornerRadius = p(22).toFloat()
        dashed.setStroke(maxOf(1, p(2)), 0xFF3A3A4C.toInt(), p(8).toFloat(), p(6).toFloat())
        focusable(add, dashed, box(0x22FFFFFF, 22, 3, CYAN))
        add.addView(IconView(this, "plus", Color.WHITE), lin(p(28), p(28)))
        add.addView(lbl("Tạo Thể Loại Mới", 24, Color.WHITE), lin(-2, -2, l = 10))
        add.setOnClickListener { createCategoryDialog() }
        col.addView(add, lin(-1, p(64), t = 16))

        favBtn = LinearLayout(this)
        favBtn.orientation = LinearLayout.HORIZONTAL
        favBtn.gravity = Gravity.CENTER
        favBtn.addView(IconView(this, "heart", Color.WHITE), lin(p(28), p(28)))
        favBtn.addView(lbl("Yêu Thích", 24, Color.WHITE, true), lin(-2, -2, l = 10))
        favBtn.setOnClickListener { showFavorites() }
        updateFavBtn()
        col.addView(favBtn, lin(-1, p(64), t = 10))

        col.addView(lbl("DANH MỤC ÂM NHẠC", 18, MUTED, true), lin(-2, -2, l = 8, t = 20, b = 8))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        catBox = LinearLayout(this)
        catBox.orientation = LinearLayout.VERTICAL
        sv.addView(catBox, FrameLayout.LayoutParams(-1, -2))
        col.addView(sv, lin(-1, 0, 1f))

        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.gravity = Gravity.CENTER_VERTICAL
        val dot = IconView(this, "dot", GREEN)
        foot.addView(dot, lin(p(18), p(18)))
        qualText = lbl("", 18, MUTED, false, 1)
        foot.addView(qualText, lin(0, -2, 1f, l = 8))
        val gear = LinearLayout(this)
        gear.orientation = LinearLayout.HORIZONTAL
        gear.gravity = Gravity.CENTER
        gear.setPadding(p(16), p(8), p(16), p(8))
        focusable(gear, box(PANEL, 16, 2, BORDER), box(0x22FFFFFF, 16, 3, CYAN))
        gear.addView(IconView(this, "gear", Color.WHITE), lin(p(24), p(24)))
        gear.addView(lbl("Cài đặt", 18, Color.WHITE), lin(-2, -2, l = 8))
        gear.setOnClickListener { showSettings() }
        foot.addView(gear, lin(-2, -2))
        col.addView(foot, lin(-1, -2, t = 10))
        return col
    }

    private fun hintChip(key: String, text: String): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val k = lbl(key, 15, Color.WHITE, true)
        k.background = box(0xFF2A2A38.toInt(), 6, 2, 0xFF44445A.toInt())
        k.setPadding(p(8), p(2), p(8), p(2))
        r.addView(k, lin(-2, -2))
        r.addView(lbl(text, 18, MUTED), lin(-2, -2, l = 6, r = 10))
        return r
    }

    private fun buildCenter(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val barRow = LinearLayout(this)
        barRow.orientation = LinearLayout.HORIZONTAL
        barRow.gravity = Gravity.CENTER_VERTICAL

        val mic = FrameLayout(this)
        focusable(mic, box(RED, 34, 2, 0x55FFFFFF), box(RED, 34, 4, CYAN))
        val micIc = IconView(this, "mic", Color.WHITE)
        mic.addView(micIc, FrameLayout.LayoutParams(p(30), p(30), Gravity.CENTER))
        mic.setOnClickListener { startVoice() }
        barRow.addView(mic, lin(p(68), p(68)))

        searchField = LinearLayout(this)
        searchField.orientation = LinearLayout.HORIZONTAL
        searchField.gravity = Gravity.CENTER_VERTICAL
        searchField.background = box(PANEL2, 36, 3, RED)
        searchField.setPadding(p(22), 0, p(16), 0)
        searchField.addView(IconView(this, "search", Color.WHITE), lin(p(30), p(30)))
        searchEt = EditText(this)
        searchEt.background = null
        searchEt.setTextColor(Color.WHITE)
        searchEt.setHintTextColor(0xFFB4B4C8.toInt())
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
        searchField.addView(hintChip("OK", "Chọn"), lin(-2, -2))
        searchField.addView(hintChip("MENU", "Ưu tiên"), lin(-2, -2))
        barRow.addView(searchField, lin(0, p(68), 1f, l = 14))

        // Nut bat/tat che do karaoke (dung chung voi remote dien thoai)
        karaokeBtn = LinearLayout(this)
        karaokeBtn.orientation = LinearLayout.HORIZONTAL
        karaokeBtn.gravity = Gravity.CENTER
        karaokeBtn.setPadding(p(22), 0, p(22), 0)
        karaokeDot = View(this)
        karaokeLbl = lbl("Karaoke", 24, Color.WHITE, true, 1)
        karaokeBtn.addView(karaokeDot, lin(p(14), p(14), r = 10))
        karaokeBtn.addView(karaokeLbl, lin(-2, -2))
        karaokeBtn.setOnClickListener { toggleKaraokeFromTv() }
        renderKaraokeBtn(false)
        barRow.addView(karaokeBtn, lin(-2, p(68), l = 14))
        col.addView(barRow, lin(-1, p(68)))

        status = lbl("", 20, MUTED, false, 1)
        col.addView(status, lin(-1, -2, l = 8, t = 12, b = 4))

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.clipToPadding = false
        gridBox = LinearLayout(this)
        gridBox.orientation = LinearLayout.VERTICAL
        gridBox.setPadding(p(4), p(6), p(4), p(20))
        sv.addView(gridBox, FrameLayout.LayoutParams(-1, -2))
        col.addView(sv, lin(-1, 0, 1f))
        return col
    }

    private fun ctrlBtn(icon: String, label: String, red: Boolean, onClick: () -> Unit): Pair<View, IconView> {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.gravity = Gravity.CENTER
        focusable(
            b,
            if (red) box(RED, 22) else box(PANEL2, 22, 2, BORDER),
            box(if (red) RED else 0xFF26263A.toInt(), 22, 4, CYAN)
        )
        val ic = IconView(this, icon, Color.WHITE)
        b.addView(ic, lin(p(34), p(34)))
        b.addView(lbl(label, 18, Color.WHITE, true, 1), lin(-2, -2, t = 6))
        b.setOnClickListener { onClick() }
        return Pair(b, ic)
    }

    private fun buildRight(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val now = LinearLayout(this)
        now.orientation = LinearLayout.VERTICAL
        now.background = box(PANEL, 28, 2, BORDER)
        now.setPadding(p(20), p(16), p(20), p(18))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(IconView(this, "dot", RED), lin(p(24), p(24)))
        head.addView(lbl("ĐANG PHÁT", 22, Color.WHITE, true), lin(-2, -2, l = 6))
        head.addView(spacer(), lin(0, 1, 1f))
        head.addView(IconView(this, "eq", RED), lin(p(26), p(26)))
        now.addView(head, lin(-1, -2))

        slot = View(this)
        now.addView(slot, lin(-1, p(270), t = 12))

        titleTv = lbl("Chưa có bài hát", 26, Color.WHITE, true, 1)
        now.addView(titleTv, lin(-1, -2, t = 12))
        subTv = lbl("", 20, MUTED, false, 1)
        now.addView(subTv, lin(-1, -2, t = 2))

        val prog = LinearLayout(this)
        prog.orientation = LinearLayout.HORIZONTAL
        prog.gravity = Gravity.CENTER_VERTICAL
        timeL = lbl("00:00", 18, MUTED)
        timeR = lbl("00:00", 18, MUTED)
        bar = BarView(this)
        prog.addView(timeL, lin(-2, -2))
        prog.addView(bar, lin(0, p(8), 1f, l = 12, r = 12))
        prog.addView(timeR, lin(-2, -2))
        now.addView(prog, lin(-1, -2, t = 12))

        val ctr = LinearLayout(this)
        ctr.orientation = LinearLayout.HORIZONTAL
        val b1 = ctrlBtn("rew", "Lùi 10s", false) { core?.controller?.seekTo(null, -10_000L) }
        val b2 = ctrlBtn("pause", "Phát / Dừng", true) {
            val c = core ?: return@ctrlBtn
            if (lastUi.state == PState.PAUSED) c.controller.resume() else c.controller.pause()
        }
        playIcon = b2.second
        val b3 = ctrlBtn("next", "Bài tiếp", false) { core?.controller?.skip() }
        val b4 = ctrlBtn("ff", "Tua 10s", false) { core?.controller?.seekTo(null, 10_000L) }
        ctr.addView(b1.first, lin(0, p(92), 1f, r = 8))
        ctr.addView(b2.first, lin(0, p(92), 1.2f, r = 8))
        ctr.addView(b3.first, lin(0, p(92), 1f, r = 8))
        ctr.addView(b4.first, lin(0, p(92), 1f))
        now.addView(ctr, lin(-1, -2, t = 14))
        col.addView(now, lin(-1, -2))

        val q = LinearLayout(this)
        q.orientation = LinearLayout.VERTICAL
        q.background = box(PANEL, 28, 2, BORDER)
        q.setPadding(p(20), p(16), p(20), p(14))
        val qh = LinearLayout(this)
        qh.orientation = LinearLayout.HORIZONTAL
        qh.gravity = Gravity.CENTER_VERTICAL
        qh.addView(lbl("HÀNG ĐỢI TIẾP THEO", 22, Color.WHITE, true), lin(0, -2, 1f))
        queueCount = lbl("0 bài", 18, Color.WHITE, true)
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
        val addSong = lbl("Thêm bài hát", 20, RED, true)
        focusable(addSong, box(Color.TRANSPARENT, 14), box(0x22FFFFFF, 14, 3, CYAN))
        addSong.setPadding(p(14), p(6), p(14), p(6))
        addSong.setOnClickListener {
            searchEt.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(searchEt, 0)
        }
        qf.addView(addSong, lin(-2, -2))
        q.addView(qf, lin(-1, -2, t = 8))
        col.addView(q, lin(-1, 0, 1f, t = 16))
        return col
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

        nowBadge = lbl("NOW PLAYING", 16, Color.WHITE, true)
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
        nowBadge.text = if (u.state == PState.PAUSED) "ĐANG TẠM DỪNG" else "NOW PLAYING"
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
        var fIdx = -1
        for (i in 0 until queueBox.childCount) if (queueBox.getChildAt(i).hasFocus()) fIdx = i
        queueBox.removeAllViews()
        if (items.isEmpty()) {
            val e = lbl("Chưa có bài nào chờ.\nChọn bài ở giữa hoặc dùng điện thoại để thêm.", 19, MUTED)
            e.setPadding(p(8), p(20), p(8), 0)
            queueBox.addView(e, lin(-1, -2))
            return
        }
        items.forEachIndexed { i, qi ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(p(12), p(8), p(12), p(8))
            focusable(row, box(0x00000000, 18), box(0xFF1A2230.toInt(), 18, 3, CYAN))
            row.addView(lbl("${i + 1}", 20, MUTED, true), lin(p(26), -2))
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundColor(0xFF1B1B26.toInt())
            Img.load(iv, Util.thumb(qi.videoId))
            row.addView(iv, lin(p(116), p(66), l = 4))
            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            info.addView(lbl(qi.title, 21, Color.WHITE, true, 1), lin(-1, -2))
            info.addView(
                lbl(if (qi.addedBy.isNullOrBlank()) "Tự động đề xuất" else "Yêu cầu từ: ${qi.addedBy}", 17, MUTED, false, 1),
                lin(-1, -2, t = 2)
            )
            row.addView(info, lin(0, -2, 1f, l = 14))
            if (qi.durationSec > 0) row.addView(lbl(mmss(qi.durationSec * 1000L), 17, MUTED), lin(-2, -2, l = 8))
            row.tag = QueueTag(qi)
            row.setOnClickListener { playQueueItem(qi) }
            row.setOnLongClickListener { removeQueueItem(qi); true }
            queueBox.addView(row, lin(-1, -2, b = 6))
        }
        if (fIdx >= 0 && queueBox.childCount > 0) {
            queueBox.getChildAt(minOf(fIdx, queueBox.childCount - 1)).requestFocus()
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
        status.text = when {
            sv.loading && sv.results.isEmpty() -> "Đang tìm “${sv.query}”…"
            sv.error != null && sv.results.isEmpty() -> "Không tìm được: ${sv.error}"
            sv.query.isEmpty() -> "Chọn thể loại bên trái hoặc gõ tên bài hát để tìm"
            sv.results.isEmpty() -> "Không có kết quả cho “${sv.query}”"
            else -> "Kết quả cho “${sv.query}”" +
                (if (sv.source == "remote") " · từ điện thoại" else "") +
                (if (sv.loading) " · đang tải thêm…" else "")
        }
        if (!searchEt.hasFocus() && sv.query != searchEt.text.toString()) searchEt.setText(sv.query)
        if (sv.source == "remote") syncCatToQuery(sv.query)

        val res = sv.results
        val same = sv.query == shownQuery && res.size == shownCount && res.firstOrNull()?.videoId == shownFirst
        if (same) return
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

        if (fIdx >= 0 && fIdx < cardViews.size) cardViews[fIdx].requestFocus()
        else if (currentFocus == null && !fullscreen && cardViews.isNotEmpty()) cardViews[0].requestFocus()
    }

    /** Doi so bai tren moi hang (1/2/3) roi ve lai luoi ket qua. */
    private fun applyGridCols(n: Int) {
        gridCols = n.coerceIn(1, 3)
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
        focusable(c, box(PANEL, 18, 2, BORDER), box(0xFF1A2230.toInt(), 18, 4, CYAN))
        val tw = if (horiz) p(300) else cardW - p(16)
        val th = FrameLayout(this)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setBackgroundColor(0xFF1B1B26.toInt())
        th.addView(iv, FrameLayout.LayoutParams(-1, -1))
        if (h.durationSec > 0) {
            val d = lbl(mmss(h.durationSec * 1000L), 16, Color.WHITE, true)
            d.background = box(0xCC000000.toInt(), 6)
            d.setPadding(p(8), p(2), p(8), p(2))
            val dl = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
            dl.setMargins(0, 0, p(8), p(8))
            th.addView(d, dl)
        }
        val hv = IconView(this, "heart", PINK)
        hv.tag = "heart"
        hv.visibility = if (isFav(h.videoId)) View.VISIBLE else View.GONE
        val hlp = FrameLayout.LayoutParams(p(30), p(30), Gravity.TOP or Gravity.START)
        hlp.setMargins(p(8), p(8), 0, 0)
        th.addView(hv, hlp)
        c.addView(th, LinearLayout.LayoutParams(tw, tw * 9 / 16))
        if (horiz) {
            // 1 bai/hang: thumbnail ben trai, chu ben phai (khong keo thumbnail qua to)
            val tcol = LinearLayout(this)
            tcol.orientation = LinearLayout.VERTICAL
            tcol.gravity = Gravity.CENTER_VERTICAL
            tcol.addView(lbl(h.title, 26, Color.WHITE, true, 2), lin(-1, -2))
            tcol.addView(lbl(h.channel, 21, MUTED, false, 1), lin(-1, -2, t = 6))
            c.addView(tcol, lin(0, -2, 1f, l = 18, r = 10))
        } else {
            c.addView(lbl(h.title, 21, Color.WHITE, true, 1), lin(-1, -2, t = 10))
            c.addView(lbl(h.channel, 18, MUTED, false, 1), lin(-1, -2, t = 2))
        }
        c.tag = CardTag(h, idx)
        Img.load(iv, Util.thumb(h.videoId))
        c.setOnClickListener { addHit(h, false) }
        // Nhan 1 lan OK = them vao danh sach phat; giu OK = them vao Yeu thich (trong muc Yeu thich: giu OK = xoa)
        c.setOnLongClickListener { if (favMode) removeFav(h) else addFav(h); true }
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
            box(if (favMode) PINK else 0xFFC2185B.toInt(), 22),
            box(PINK, 22, 4, Color.WHITE)
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
        refreshHearts()
        toast("❤ Đã thêm vào Yêu thích: ${h.title}")
    }

    private fun removeFav(h: SearchHit) {
        val c = core ?: return
        val pos = favs.indexOfFirst { it.videoId == h.videoId }
        if (pos < 0) return
        favs.removeAt(pos)
        Favs.save(c.settings.sp, favs)
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
        else "❤ Yêu thích · ${favs.size} bài · OK: thêm vào danh sách phát · Giữ OK: xoá khỏi Yêu thích"
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
            val id = Util.parseVideoId(q)!!
            scope.launch {
                val m = withContext(Dispatchers.IO) { Resolver.meta(id) }
                c.controller.addFromTv(
                    id, m?.title?.takeIf { it.isNotEmpty() } ?: "YouTube · $id",
                    m?.channel ?: "", m?.durationSec ?: 0, false
                )
                toast("Đã thêm bài từ link")
            }
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
            if (on) box(RED, 34, 3, CYAN) else box(0x22FFFFFF, 34, 3, CYAN)
        )
        karaokeLbl.setTextColor(if (on) Color.WHITE else 0xFFD6D6E2.toInt())
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

    private fun tvLoadMore() {
        val c = core ?: return
        val s = c.controller.search.value
        if (s.loading || !s.hasMore || s.query.isEmpty()) return
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
                c.controller.searchFail(q, s.source, (e.message ?: "lỗi mạng").take(100))
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
                box(if (sel) RED else 0x22FFFFFF, 22, 3, CYAN)
            )
            row.addView(IconView(this, "note", if (sel) Color.WHITE else MUTED), lin(p(28), p(28)))
            row.addView(lbl(ct.name, 24, if (sel) Color.WHITE else 0xFFD6D6E2.toInt(), sel, 1), lin(0, -2, 1f, l = 14))
            row.setOnClickListener { selectCat(i) }
            // Moi the loai (ke ca 5 the loai co dinh) deu co nut Xoa
            row.setOnLongClickListener { confirmDeleteCat(i); true }
            val del = lbl("Xoá", 18, Color.WHITE, true)
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
        val t1 = lbl("Xoá thể loại này?", 34, Color.WHITE, true)
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

    /** An: ma QR bien mat han, danh muc am nhac tu dong tro len tren. Hien: ma QR tro lai. */
    private fun applyQrHidden(h: Boolean) {
        qrBody.visibility = if (h) View.GONE else View.VISIBLE
        qrToggle.text = if (h) "Hiện" else "Ẩn"
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
                        3 -> { s.gridCols = when (s.gridCols) { 3 -> 2; 2 -> 1; else -> 3 }; applyGridCols(s.gridCols) }
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
        confirmExit()
    }

    // ------------------------------------------------------------------ phim remote TV

    private fun isMediaKey(k: Int) = k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY ||
        k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_MEDIA_NEXT

    /** Moi phim bam tren remote TV = "co dieu khien": tinh lai 5 giay, dang toan man hinh thi thu nho ngay. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
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
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            when (val tag = currentFocus?.tag) {
                is CardTag -> addHit(tag.hit, true)
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
