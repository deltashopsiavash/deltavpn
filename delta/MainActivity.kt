package com.v2ray.ang.ui.main

import android.content.BroadcastReceiver
import com.v2ray.ang.delta.DeltaAccountApi
import com.v2ray.ang.delta.DeltaAccount
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.RealPingResult
import java.util.Locale
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.delta.DeltaSubscriptions
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var repo: DeltaSubscriptions
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var connect: ImageButton
    private lateinit var login: LinearLayout
    private lateinit var dashboard: LinearLayout
    private lateinit var password: EditText
    private var account: DeltaAccount? = null
    private val accountApi = DeltaAccountApi()
    private var stateKnown = false
    private lateinit var loginStatus: TextView
    private lateinit var usageText: TextView
    private lateinit var expiry: TextView
    private lateinit var usageBar: ProgressBar
    private lateinit var pingButton: Button
    private var pingRequest: String? = null
    private val delays = mutableMapOf<String, Long>()
    private val green = Color.parseColor("#50D39A")
    private val yellow = Color.parseColor("#F3C95C")
    private val red = Color.parseColor("#F47C83")
    private val navy = Color.parseColor("#0D1524")
    private lateinit var importButton: Button
    private lateinit var refreshButton: Button
    private lateinit var clearButton: Button
    private lateinit var input: EditText
    private lateinit var servers: LinearLayout
    private var running = false
    private var busy = false
    private var transitioning = false
    private var ready = false
    private val timer = Handler(Looper.getMainLooper())
    private val blue = Color.parseColor("#3478F6")
    private val fg = Color.parseColor("#EAF0FC")
    private val muted = Color.parseColor("#A6B6D0")
    private val panel = Color.parseColor("#17263D")
    private val timeout = Runnable {
        transitioning = false
        render("پاسخ سرویس دریافت نشد؛ وضعیت اتصال را بررسی کنید.")
        queryState()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra("key", 0)) {
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    if (pingRequest != null && intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID) == pingRequest) {
                        @Suppress("DEPRECATION")
                        val result = intent.getSerializableExtra("content") as? RealPingResult
                        if (result != null) { delays[result.guid] = result.delayMillis; render() }
                    }
                }
                AppConfig.MSG_MEASURE_CONFIG_FINISH, AppConfig.MSG_MEASURE_CONFIG_CANCEL -> {
                    if (pingRequest != null && intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID) == pingRequest) {
                        pingRequest = null
                        render("تست سرورها پایان یافت.")
                    }
                }
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                    stateKnown = true; running = true; transitioning = false; timer.removeCallbacks(timeout)
                    render("تونل VPN فعال است")
                }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    stateKnown = true; running = false; transitioning = false; timer.removeCallbacks(timeout)
                    render("قطع اتصال")
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    stateKnown = true; running = false; transitioning = false; timer.removeCallbacks(timeout)
                    render("اتصال ناموفق بود؛ سرور دیگری را امتحان کنید.")
                }
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startTunnel() else render("مجوز VPN داده نشد.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = DeltaSubscriptions(this)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.decorView.systemUiVisibility = 0
        val scroll = ScrollView(this).apply {
            setBackgroundColor(navy)
            isFillViewport = true
            clipToPadding = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(18), dp(24), dp(24))
        }
        scroll.addView(root); setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)

        login = column()
        dashboard = column()
        add(login)
        add(dashboard)

        val logo = ImageView(this).apply {
            setImageResource(com.v2ray.ang.R.drawable.delta_icon)
            contentDescription = "Delta VPN"
        }
        put(login, logo, 38, dp(88), dp(88), Gravity.CENTER_HORIZONTAL)
        put(login, centered("DELTA VPN", 29, fg, true), 12)
        put(login, centered("به دلتا خوش آمدید", 15, muted), 18)
        put(login, centered("با نام کاربری و رمز عبور وارد شوید", 16, fg), 36)
        input = EditText(this).apply {
            hint = "نام کاربری"
            setHintTextColor(muted); setTextColor(fg)
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            textDirection = View.TEXT_DIRECTION_LTR; layoutDirection = View.LAYOUT_DIRECTION_LTR
            isSaveEnabled = false
            setPadding(dp(15),dp(16),dp(15),dp(16)); background = shape(panel, 14, Color.parseColor("#394A64"))
        }
        put(login, input, 14)
        password = EditText(this).apply {
            hint = "رمز عبور"; setHintTextColor(muted); setTextColor(fg)
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textDirection = View.TEXT_DIRECTION_LTR; layoutDirection = View.LAYOUT_DIRECTION_LTR
            isSaveEnabled = false
            setPadding(dp(15),dp(16),dp(15),dp(16)); background = shape(panel,14,Color.parseColor("#394A64"))
        }
        put(login, password, 12)
        importButton = button("ورود به حساب") { signIn() }
        put(login, importButton, 12)
        loginStatus = centered("در حال آماده‌سازی موتور اتصال…", 14, muted)
        put(login, loginStatus, 18)
        put(login, centered("اختصاصی مشتریان دلتا", 13, muted), 24)
        put(login, centered("نسخه آزمایشی ۱.۲.۰", 12, muted), 8)

        put(dashboard, centered("DELTA VPN", 25, fg, true), 4)
        val quota = column().apply {
            background = shape(panel, 18); setPadding(dp(16),dp(15),dp(16),dp(15))
        }
        put(quota, centered("اطلاعات اشتراک دلتا", 14, muted))
        usageText = centered("—", 25, fg, true).apply { textDirection = View.TEXT_DIRECTION_LTR }
        put(quota, usageText, 8)
        usageBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(blue)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#34445D"))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        put(quota, usageBar, 10, -1, dp(8))
        put(dashboard, quota, 24)
        expiry = centered("اعتبار: نامشخص", 14, green)
        put(dashboard, expiry, 13)

        connect = ImageButton(this).apply {
            setImageResource(com.v2ray.ang.R.drawable.delta_power)
            scaleType = ImageView.ScaleType.CENTER
            isFocusable = true
            contentDescription = "اتصال یا قطع اتصال"
            setPadding(0,0,0,0)
            stateListAnimator = null; elevation = dp(3).toFloat()
            setOnClickListener { toggle() }
        }
        put(dashboard, connect, 22, dp(124), dp(124), Gravity.CENTER_HORIZONTAL)
        status = centered("برای اتصال لمس کنید", 14, muted)
        put(dashboard, status, 12)

        val serverHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        serverHeader.addView(label("سرورهای اشتراک", 17, fg, true), LinearLayout.LayoutParams(0,-2,1f))
        pingButton = button("تست پینگ", panel) { testServers() }
        serverHeader.addView(pingButton, LinearLayout.LayoutParams(dp(112),dp(46)))
        put(dashboard, serverHeader, 22)
        servers = column()
        put(dashboard, servers, 10)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        refreshButton = button("به‌روزرسانی", panel) { validateAccount(false) }
        clearButton = button("خروج از حساب", panel) { signOut() }
        actions.addView(refreshButton, LinearLayout.LayoutParams(0,dp(50),1f).apply { marginEnd = dp(8) })
        actions.addView(clearButton, LinearLayout.LayoutParams(0,dp(50),1f))
        put(dashboard, actions, 16)
        put(dashboard, centered("نسخه ۱.۲.۰ · Xray / v2rayNG · GPL-3.0", 11, muted), 20)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), ContextCompat.RECEIVER_NOT_EXPORTED)
        render("در حال آماده‌سازی موتور اتصال…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { SettingsManager.initAssets(this@MainActivity, assets) }
                ready = true; render("در انتظار بررسی وضعیت اتصال…"); queryState()
            } catch (_: Exception) { render("آماده‌سازی موتور اتصال ناموفق بود.") }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 12)
        }
    }

    override fun onResume() { super.onResume(); if (ready) queryState() }
    override fun onDestroy() {
        if (pingRequest != null) MessageHelper.sendMsg2TestService(this, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
        timer.removeCallbacksAndMessages(null)
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_UNREGISTER_CLIENT, "")
        unregisterReceiver(receiver); super.onDestroy()
    }
    private fun queryState() {
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun centered(t: String, size: Int, color: Int, bold: Boolean = false) =
        label(t,size,color,bold).apply { gravity = Gravity.CENTER }
    private fun put(parent: LinearLayout, view: View, top: Int = 0, width: Int = -1, height: Int = -2, alignment: Int = Gravity.NO_GRAVITY) {
        parent.addView(view, LinearLayout.LayoutParams(width,height).apply { topMargin = dp(top); gravity = alignment })
    }
    private fun powerBackground(active: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (active) intArrayOf(Color.parseColor("#168861"),Color.parseColor("#115D49"))
        else intArrayOf(Color.parseColor("#3478F6"),Color.parseColor("#1647B5"))
    ).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(3), if (active) green else Color.parseColor("#5A9AFF"))
    }
    private fun gb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes.coerceAtLeast(0) / 1073741824.0)
    private fun testServers() {
        if (pingRequest != null || busy || transitioning || running || repo.servers.isEmpty()) return
        val id = java.util.UUID.randomUUID().toString()
        pingRequest = id; delays.clear(); render("در حال تست واقعی سرورها…")
        MessageHelper.sendMsg2TestService(this, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_START, repo.group, repo.servers), id)
        timer.postDelayed({
            if (pingRequest == id) {
                pingRequest = null
                MessageHelper.sendMsg2TestService(this, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
                render("مهلت تست پینگ پایان یافت.")
            }
        }, 180000)
    }
    private fun label(t: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun add(v: View, top: Int = 0) {
        root.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    private fun button(t: String, color: Int = blue, action: () -> Unit) = Button(this).apply {
        text = t; textSize = 15f; isAllCaps = false; setTextColor(fg)
        background = shape(color); minHeight = dp(50); setOnClickListener { action() }
    }
    private fun render(message: String? = null) {
        val hasSub = running || (account != null && repo.url != null && repo.servers.isNotEmpty())
        login.visibility = if (hasSub) View.GONE else View.VISIBLE
        dashboard.visibility = if (hasSub) View.VISIBLE else View.GONE
        message?.let { status.text = it; loginStatus.text = it }
        if (running && !transitioning && message == null) status.text = "تونل VPN فعال است"
        connect.background = powerBackground(running)
        connect.contentDescription = if (running) "قطع اتصال" else "اتصال"
        connect.isEnabled = ready && stateKnown && !busy && !transitioning && pingRequest == null && (running || (account != null && repo.canConnect()))
        connect.alpha = if (connect.isEnabled) 1f else 0.55f
        status.setTextColor(if (running) green else muted)
        val editable = ready && stateKnown && !busy && !transitioning && !running && pingRequest == null
        importButton.isEnabled = editable; input.isEnabled = editable; password.isEnabled = editable
        refreshButton.isEnabled = editable && account != null
        clearButton.isEnabled = editable && account != null
        pingButton.isEnabled = editable && hasSub && account != null
        pingButton.text = if (pingRequest != null) "در حال تست…" else "تست پینگ"

        val usage = repo.usage
        if (usage != null && usage.total > 0) {
            val used = usage.upload.toDouble() + usage.download.toDouble()
            val remaining = (usage.total.toDouble() - used).coerceAtLeast(0.0).toLong()
            usageText.text = "${gb(remaining)} GB / ${gb(usage.total)} GB"
            usageBar.visibility = View.VISIBLE
            usageBar.progress = (1000 * remaining.toDouble() / usage.total).toInt().coerceIn(0,1000)
        } else {
            usageText.text = "حجم اعلام نشده"
            usageBar.visibility = View.GONE
        }
        val upstreamExpires = usage?.expire ?: 0
        val expires = account?.let { if (upstreamExpires > 0) minOf(upstreamExpires, it.expires) else it.expires } ?: upstreamExpires
        val now = System.currentTimeMillis() / 1000
        expiry.text = when {
            expires <= 0 -> "اعتبار: اعلام نشده"
            expires <= now -> "اعتبار به پایان رسیده"
            else -> "${kotlin.math.ceil((expires-now)/86400.0).toLong()} روز اعتبار باقی‌مانده"
        }
        expiry.setTextColor(if (expires > 0 && expires <= now) red else if (expires > 0) green else muted)

        servers.removeAllViews()
        val selected = MmkvManager.getSelectServer()
        repo.servers.forEach { id ->
            val profile = MmkvManager.decodeServerConfig(id) ?: return@forEach
            val chosen = selected == id
            val latency = delays[id]
            val color: Int = when {
                latency == null -> muted
                latency <= 0 -> red
                latency < 150 -> green
                latency < 350 -> yellow
                else -> red
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12),dp(11),dp(12),dp(11))
                background = shape(panel,12, if (chosen) blue else null)
                isClickable = editable; isFocusable = editable
                setOnClickListener { if (editable) { MmkvManager.setSelectServer(id); render("سرور انتخاب شد.") } }
            }
            val dot = View(this).apply { background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(if (chosen) blue else color)
            } }
            row.addView(dot, LinearLayout.LayoutParams(dp(9),dp(9)).apply { marginEnd = dp(10) })
            val names = column()
            put(names,label(profile.remarks.ifBlank { profile.configType.name },14,fg,true).apply { maxLines = 2 })
            put(names,label(profile.configType.name,11,muted),3)
            row.addView(names,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(label(if (latency == null) "—" else if (latency <= 0) "Timeout" else "$latency ms",12,color,true))
            put(servers,row,8)
        }
    }
    private fun checkedImport(a: DeltaAccount) {
        // The upstream account must independently enforce traffic and expiry.
        repo.refresh(a.subscription)
        val u = repo.usage ?: error("اطلاعات حجم اشتراک در پنل موجود نیست.")
        check(u.total > 0 && u.total <= a.gb.toLong() * 1073741824L &&
            u.expire > System.currentTimeMillis()/1000 && u.expire <= a.expires) {
            "حجم یا تاریخ اشتراک پنل با حساب سازگار نیست؛ با پشتیبانی تماس بگیرید."
        }
        check(u.upload.toDouble()+u.download.toDouble() < u.total.toDouble()) { "حجم اشتراک به پایان رسیده است." }
    }
    private fun signIn() {
        if (!ready || !stateKnown || busy || running || transitioning) return
        val name = input.text.toString().trim()
        val secret = password.text.toString()
        if (name.isBlank() || secret.isBlank()) { render("نام کاربری و رمز عبور را وارد کنید."); return }
        password.text.clear()
        busy = true; render("در حال ورود…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                repo.clear()
                val a = accountApi.login(name, secret)
                try { checkedImport(a); a } catch (e: Exception) { repo.clear(); runCatching { accountApi.logout(a.token) }; throw e }
            } }
            busy = false
            result.onSuccess { account = it; delays.clear(); input.text.clear(); render("به دلتا خوش آمدید") }
                .onFailure { account = null; render("ورود یا دریافت اشتراک ناموفق بود؛ حساب و تنظیمات سرور را بررسی کنید.") }
        }
    }
    private fun validateAccount(connectAfter: Boolean) {
        val previous = account ?: return
        if (busy || running || transitioning || pingRequest != null) return
        busy = true; render("در حال بررسی اعتبار حساب…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val updated = accountApi.session(previous.token)
                checkedImport(updated)
                updated
            } }
            busy = false
            result.onSuccess {
                account = it; delays.clear(); render("اعتبار حساب تأیید شد.")
                if (connectAfter) {
                    val request = VpnService.prepare(this@MainActivity)
                    if (request == null) startTunnel() else permission.launch(request)
                }
            }.onFailure {
                account = null; repo.clear(); delays.clear()
                render("اعتبار حساب یا اتصال به سرور تأیید نشد؛ دوباره وارد شوید.")
            }
        }
    }
    private fun signOut() {
        if (busy || running || transitioning || pingRequest != null) return
        val previous = account
        account = null; repo.clear(); delays.clear(); input.text.clear(); password.text.clear()
        render("از حساب خارج شدید.")
        if (previous != null) lifecycleScope.launch(Dispatchers.IO) { runCatching { accountApi.logout(previous.token) } }
    }
    private fun toggle() {
        if (running) {
            transitioning = true; render("در حال قطع اتصال…")
            LauncherManager.stopService(this); timer.postDelayed(timeout, 15000)
        } else if (account != null && repo.canConnect()) {
            validateAccount(true)
        }
    }
    private fun startTunnel() {
        if (account == null || !repo.canConnect()) return
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        transitioning = true; render("در حال اتصال…")
        if (!LauncherManager.startServiceFromToggle(this)) {
            transitioning = false; render("شروع سرویس ناموفق بود.")
        } else timer.postDelayed(timeout, 20000)
    }
}
