package com.v2ray.ang.ui.main

import android.content.BroadcastReceiver
import android.app.AlertDialog
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import androidx.activity.OnBackPressedCallback
import com.v2ray.ang.delta.DeltaAccountApi
import com.v2ray.ang.delta.DeltaAccount
import com.v2ray.ang.delta.DeltaApiException
import com.v2ray.ang.delta.DeltaSessionStore
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
    private lateinit var startup: LinearLayout
    private lateinit var startupStatus: TextView
    private var starting = true
    private var english = false
    private lateinit var shell: FrameLayout
    private lateinit var motion: DeltaMotionView
    private lateinit var drawer: FrameLayout
    private lateinit var accountName: TextView
    private lateinit var serverSection: LinearLayout
    private lateinit var serverPicker: Button
    private lateinit var quickButton: Button
    private lateinit var loginSpinner: ProgressBar
    private var manualLogin = false
    private var quickMode = false
    private var logoutPending = false
    private var openingContact = false
    private fun tr(fa: String, en: String) = if (english) en else fa
    override fun attachBaseContext(newBase: Context) {
        val en = newBase.getSharedPreferences("delta_ui", Context.MODE_PRIVATE).getString("language","fa") == "en"
        english = en
        val locale = Locale.forLanguageTag(if (en) "en" else "fa")
        val cfg = Configuration(newBase.resources.configuration)
        cfg.setLocale(locale); cfg.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }
    private lateinit var dashboard: LinearLayout
    private lateinit var password: EditText
    private var account: DeltaAccount? = null
    private lateinit var sessionStore: DeltaSessionStore
    private var restoreAttempted = false
    private val accountApi = DeltaAccountApi()
    private var stateKnown = false
    private var stateQueryGeneration = 0
    private lateinit var loginStatus: TextView
    private lateinit var usageText: TextView
    private lateinit var usageDetail: TextView
    private var lastPoll = 0L
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
        transitioning = false;logoutPending=false
        render(tr("پاسخ سرویس دریافت نشد؛ وضعیت اتصال را بررسی کنید.","VPN service did not respond. Check connection."))
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
                        val complete = intent.getIntExtra("key",0) == AppConfig.MSG_MEASURE_CONFIG_FINISH
                        pingRequest = null
                        if (quickMode && complete) finishQuick() else {
                            quickMode = false
                            render(if (complete) tr("تست سرورها پایان یافت.","Server test finished.") else tr("تست لغو شد.","Test cancelled."))
                        }
                    }
                }
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                    stateQueryGeneration++; stateKnown = true; running = true; transitioning = false; timer.removeCallbacks(timeout)
                    render(tr("تونل VPN فعال است","VPN is connected")); maybeRestore()
                }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    stateQueryGeneration++; stateKnown = true; running = false; transitioning = false; timer.removeCallbacks(timeout)
                    if (logoutPending) { logoutPending=false; signOut() }
                    else { render(if (account == null) tr("نام کاربری و رمز عبور را وارد کنید.","Enter username and password.") else tr("قطع اتصال","Disconnect")); maybeRestore() }
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    stateQueryGeneration++; stateKnown = true; running = false; transitioning = false; timer.removeCallbacks(timeout)
                    render(tr("اتصال ناموفق بود؛ سرور دیگری را امتحان کنید.","Connection failed. Try another server.")); maybeRestore()
                }
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startTunnel() else render(tr("مجوز VPN داده نشد.","VPN permission was denied."))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = DeltaSubscriptions(this)
        sessionStore = DeltaSessionStore(this)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.decorView.systemUiVisibility = 0
        shell = FrameLayout(this).apply { setBackgroundColor(navy); layoutDirection=if(english) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL }
        motion = DeltaMotionView(this)
        shell.addView(motion,FrameLayout.LayoutParams(-1,-1))
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
            clipToPadding = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = if(english) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(18), dp(24), dp(24))
        }
        scroll.addView(root); shell.addView(scroll,FrameLayout.LayoutParams(-1,-1)); setContentView(shell)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(shell)

        startup = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        login = column().apply { visibility = View.GONE }
        dashboard = column().apply { visibility = View.GONE }
        add(startup)
        add(login)
        add(dashboard)
        (login.layoutParams as LinearLayout.LayoutParams).weight=1f
        (dashboard.layoutParams as LinearLayout.LayoutParams).weight=1f

        put(startup, roundLogo(), 0, dp(104), dp(104), Gravity.CENTER_HORIZONTAL)
        put(startup, centered("DELTA VPN", 29, fg, true), 18)
        val loader = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(blue) }
        put(startup, loader, 28, dp(30), dp(30), Gravity.CENTER_HORIZONTAL)
        startupStatus = centered(tr("در حال آماده‌سازی…","Preparing…"), 14, muted)
        put(startup, startupStatus, 14)

        put(login, roundLogo(), 38, dp(88), dp(88), Gravity.CENTER_HORIZONTAL)
        put(login, centered("DELTA VPN", 29, fg, true), 12)
        put(login, centered(tr("به دلتا خوش آمدید","Welcome to Delta"), 15, muted), 18)
        put(login, centered(tr("با نام کاربری و رمز عبور وارد شوید","Sign in with your username and password"), 16, fg), 36)
        input = EditText(this).apply {
            hint = tr("نام کاربری","Username")
            setHintTextColor(muted); setTextColor(fg)
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            textDirection = View.TEXT_DIRECTION_LTR; layoutDirection = View.LAYOUT_DIRECTION_LTR
            isSaveEnabled = false
            setPadding(dp(15),dp(16),dp(15),dp(16)); background = shape(panel, 14, Color.parseColor("#394A64"))
        }
        put(login, input, 14)
        password = EditText(this).apply {
            hint = tr("رمز عبور","Password"); setHintTextColor(muted); setTextColor(fg)
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textDirection = View.TEXT_DIRECTION_LTR; layoutDirection = View.LAYOUT_DIRECTION_LTR
            isSaveEnabled = false
            setPadding(dp(15),dp(16),dp(15),dp(16)); background = shape(panel,14,Color.parseColor("#394A64"))
        }
        put(login, password, 12)
        importButton = button(tr("ورود به حساب","Sign in")) { signIn() }
        val signRow=FrameLayout(this)
        signRow.addView(importButton,FrameLayout.LayoutParams(-1,dp(54)))
        loginSpinner=ProgressBar(this).apply {
            indeterminateTintList=ColorStateList.valueOf(Color.WHITE);visibility=View.GONE
        }
        signRow.addView(loginSpinner,FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
        put(login,signRow,16)
        loginStatus = centered(tr("در حال آماده‌سازی موتور اتصال…","Preparing VPN engine…"), 14, muted)
        put(login, loginStatus, 18)
        login.addView(View(this),LinearLayout.LayoutParams(1,0,1f))
        put(login,button(tr("زبان: فارسی / English","Language: English / فارسی"),panel){chooseLanguage()},18)
        val contacts=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        contacts.addView(button(tr("خرید اشتراک","Buy subscription"),panel){openContact("purchase")},LinearLayout.LayoutParams(0,dp(50),1f).apply { marginEnd=dp(8) })
        contacts.addView(button(tr("پشتیبانی","Support"),panel){openContact("support")},LinearLayout.LayoutParams(0,dp(50),1f))
        put(login,contacts,10)
        put(login, centered("1.5.0", 11, muted), 20)

        val menuButton=button("☰",panel){showDrawer()}
        menuButton.contentDescription=tr("منوی حساب","Account menu")
        put(dashboard,menuButton,0,dp(52),dp(48),Gravity.START)
        dashboard.addView(View(this),LinearLayout.LayoutParams(1,0,1f))
        put(dashboard, centered("DELTA VPN", 30, fg, true), 12)
        val quota = column().apply {
            background = shape(panel, 18); setPadding(dp(16),dp(15),dp(16),dp(15))
        }
        put(quota, centered(tr("حجم باقی‌مانده اشتراک اختصاصی","Remaining subscription data"), 14, muted))
        usageText = centered("—", 25, fg, true).apply { textDirection = View.TEXT_DIRECTION_LTR }
        put(quota, usageText, 8)
        usageDetail = centered("—", 12, muted)
        put(quota, usageDetail, 6)
        usageBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(blue)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#34445D"))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        put(quota, usageBar, 10, -1, dp(8))
        expiry = centered(tr("اعتبار: نامشخص","Validity: unknown"), 13, green)
        put(quota, expiry, 10)

        connect = ImageButton(this).apply {
            setImageResource(com.v2ray.ang.R.drawable.delta_power)
            scaleType = ImageView.ScaleType.CENTER
            isFocusable = true
            contentDescription = tr("اتصال یا قطع اتصال","Connect or disconnect")
            setPadding(0,0,0,0)
            stateListAnimator = null; elevation = dp(3).toFloat()
            setOnClickListener { toggle() }
        }
        put(dashboard, connect, 30, dp(158), dp(158), Gravity.CENTER_HORIZONTAL)
        status = centered(tr("برای اتصال لمس کنید","Tap to connect"), 14, muted)
        put(dashboard, status, 12)

        serverPicker=button(tr("سرورهای قابل اتصال","Available servers")+"  ▾",panel) {
            serverSection.visibility=if(serverSection.visibility==View.VISIBLE) View.GONE else View.VISIBLE
        }
        put(dashboard,serverPicker,30)
        serverSection=column().apply { visibility=View.GONE }
        quickButton=button(tr("⚡ اتصال سریع","⚡ Quick connect")){validateAccount(false,true)}
        put(serverSection,quickButton,10)
        pingButton=button(tr("تست پینگ","Test latency"),panel){testServers()}
        put(serverSection,pingButton,8)
        servers=column();put(serverSection,servers,8)
        put(dashboard,serverSection)
        dashboard.addView(View(this),LinearLayout.LayoutParams(1,0,1f))
        put(dashboard, centered("1.5.0",11,muted),16)

        drawer=FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt());visibility=View.GONE;setOnClickListener{hideDrawer()} }
        val drawerScroll=ScrollView(this).apply { isFillViewport=true;setBackgroundColor(navy);isClickable=true }
        val width=minOf(dp(320),(resources.displayMetrics.widthPixels*0.86f).toInt())
        drawer.addView(drawerScroll,FrameLayout.LayoutParams(width,-1,Gravity.START))
        val menu=column().apply {setPadding(dp(16),dp(16),dp(16),dp(18));isClickable=true}
        drawerScroll.addView(menu)
        put(menu,centered(tr("حساب کاربری","My account"),19,fg,true))
        put(menu,quota,18)
        accountName=centered("—",14,fg,true);put(menu,accountName,16)
        put(menu,button(tr("تغییر زبان","Change language"),panel){chooseLanguage()},18)
        put(menu,button(tr("پشتیبانی","Support"),panel){openContact("support")},10)
        put(menu,button(tr("خرید اشتراک","Buy subscription"),panel){openContact("purchase")},10)
        refreshButton=button(tr("به‌روزرسانی","Refresh"),panel){validateAccount(false)}
        put(menu,refreshButton,10)
        clearButton=button(tr("خروج از حساب","Sign out"),Color.parseColor("#A52F42")){requestSignOut()}
        put(menu,clearButton,10)
        put(menu,centered("Xray / v2rayNG · GPL-3.0",10,muted),16)
        shell.addView(drawer,FrameLayout.LayoutParams(-1,-1))
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){
            override fun handleOnBackPressed(){
                if(drawer.visibility==View.VISIBLE)hideDrawer()
                else if(serverSection.visibility==View.VISIBLE)serverSection.visibility=View.GONE
                else {isEnabled=false;onBackPressedDispatcher.onBackPressed();isEnabled=true}
            }
        })
        ContextCompat.registerReceiver(this, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), ContextCompat.RECEIVER_NOT_EXPORTED)
        render(tr("در حال آماده‌سازی موتور اتصال…","Preparing VPN engine…"))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { SettingsManager.initAssets(this@MainActivity, assets) }
                ready = true; render(tr("در انتظار بررسی وضعیت اتصال…","Checking VPN status…")); queryState()
            } catch (_: Exception) { starting = false; render(tr("آماده‌سازی موتور اتصال ناموفق بود.","VPN engine initialization failed.")) }
        }
        timer.post(expiryTick)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 12)
        }
    }

    override fun onResume() { super.onResume(); motion.active=true;motion.invalidate(); if (ready) queryState() }
    override fun onPause() { motion.active=false;super.onPause() }
    private fun showDrawer() {
        drawer.visibility=View.VISIBLE
        val child=drawer.getChildAt(0)
        child.translationX=if(english)-child.width.toFloat() else child.width.toFloat()
        child.animate().translationX(0f).setDuration(220).start()
    }
    private fun hideDrawer(){drawer.visibility=View.GONE}
    private fun chooseLanguage() {
        if(busy||transitioning||pingRequest!=null){Toast.makeText(this,tr("ابتدا عملیات فعلی را تمام کنید.","Finish the current operation first."),Toast.LENGTH_SHORT).show();return}
        AlertDialog.Builder(this).setTitle(tr("انتخاب زبان","Choose language"))
            .setSingleChoiceItems(arrayOf("فارسی","English"),if(english)1 else 0){ dialog,index ->
                dialog.dismiss()
                if((index==1)!=english){
                    getSharedPreferences("delta_ui",Context.MODE_PRIVATE).edit().putString("language",if(index==1)"en" else "fa").apply()
                    recreate()
                }
            }.setNegativeButton(tr("انصراف","Cancel"),null).show()
    }
    private fun openContact(kind:String){
        if(openingContact)return
        openingContact=true
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO){runCatching{accountApi.contacts()[kind].orEmpty()}}
            openingContact=false
            result.onSuccess { name ->
                if(name.isBlank())Toast.makeText(this@MainActivity,tr("آیدی هنوز تنظیم نشده است.","This contact is not configured yet."),Toast.LENGTH_LONG).show()
                else runCatching {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://t.me/"+name)))}
                    .onFailure{Toast.makeText(this@MainActivity,tr("باز کردن تلگرام ناموفق بود.","Unable to open Telegram."),Toast.LENGTH_LONG).show()}
            }.onFailure{Toast.makeText(this@MainActivity,tr("دریافت آیدی ناموفق بود؛ دوباره تلاش کنید.","Could not load contact. Try again."),Toast.LENGTH_LONG).show()}
        }
    }
    private fun requestSignOut(){
        if(busy||transitioning||pingRequest!=null)return
        if(running){
            logoutPending=true;toggle()
        }else signOut()
    }
    override fun onDestroy() {
        if (pingRequest != null) MessageHelper.sendMsg2TestService(this, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
        timer.removeCallbacksAndMessages(null)
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_UNREGISTER_CLIENT, "")
        unregisterReceiver(receiver); super.onDestroy()
    }
    private fun queryState() {
        val generation = ++stateQueryGeneration
        // With the daemon stopped there is no dynamic receiver to answer a normal broadcast.
        // The ordered broadcast reports this explicitly instead of waiting indefinitely.
        MessageHelper.sendMsg2ServiceForResult(this, AppConfig.MSG_REGISTER_CLIENT, "") { handled ->
            if (isFinishing || isDestroyed || generation != stateQueryGeneration) return@sendMsg2ServiceForResult
            if (!handled) {
                stateKnown = true
                running = false
                transitioning = false
                timer.removeCallbacks(timeout)
                render(if (busy) null else if (account == null) tr("نام کاربری و رمز عبور را وارد کنید.","Enter username and password.") else tr("برای اتصال لمس کنید","Tap to connect")); maybeRestore()
            } else if (!stateKnown) {
                timer.postDelayed({
                    if (!isFinishing && !isDestroyed && generation == stateQueryGeneration && !stateKnown) {
                        starting = false
                        render(tr("وضعیت سرویس نامشخص است؛ یک بار دوباره تلاش کنید.","VPN state unknown. Please retry."))
                    }
                }, 3000)
            }
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }
    private fun roundLogo() = ImageView(this).apply {
        setImageResource(com.v2ray.ang.R.drawable.delta_icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        clipToOutline = true
        contentDescription = "Delta VPN"
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
    private fun testServers(quick:Boolean=false) {
        if (pingRequest != null || busy || transitioning || running || repo.servers.isEmpty()) return
        val id = java.util.UUID.randomUUID().toString()
        quickMode=quick
        pingRequest = id; delays.clear(); render(if(quick)tr("در حال یافتن سریع‌ترین سرور…","Finding the fastest server…") else tr("در حال تست واقعی سرورها…","Testing servers…"))
        MessageHelper.sendMsg2TestService(this, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_START, repo.group, repo.servers), id)
        timer.postDelayed({
            if (pingRequest == id) {
                pingRequest = null;quickMode=false
                MessageHelper.sendMsg2TestService(this, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
                render(tr("مهلت تست پینگ پایان یافت.","Server test timed out."))
            }
        }, 180000)
    }
    private fun finishQuick(){
        quickMode=false
        val best=delays.entries.filter{it.value>0&&it.key in repo.servers}.minByOrNull{it.value}
        if(best==null||account?.canConnect!=true||(account?.remainingSeconds()?:0)<=0){render(tr("هیچ سرور قابل اتصال پیدا نشد.","No reachable server found."));return}
        MmkvManager.setSelectServer(best.key)
        serverSection.visibility=View.GONE
        render(tr("سریع‌ترین سرور انتخاب شد.","Fastest server selected."))
        val request=VpnService.prepare(this)
        if(request==null)startTunnel() else permission.launch(request)
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
        val hasSub = running || account != null
        if(!hasSub)hideDrawer()
        startup.visibility = if (starting) View.VISIBLE else View.GONE
        root.gravity = if (starting) Gravity.CENTER_VERTICAL else Gravity.TOP
        login.visibility = if (!starting && !hasSub) View.VISIBLE else View.GONE
        dashboard.visibility = if (!starting && hasSub) View.VISIBLE else View.GONE
        if (starting) startupStatus.text = if (busy) tr("در حال ورود خودکار…","Signing in automatically…") else tr("در حال آماده‌سازی…","Preparing…")
        message?.let { status.text = it; loginStatus.text = it }
        if (running && !transitioning && message == null) status.text = tr("تونل VPN فعال است","VPN is connected")
        connect.background = powerBackground(running)
        connect.contentDescription = if (running) tr("قطع اتصال","Disconnect") else tr("اتصال","Connect")
        connect.isEnabled = ready && stateKnown && !busy && !transitioning && pingRequest == null && (running || (account?.canConnect == true && repo.canConnect()))
        connect.alpha = if (connect.isEnabled) 1f else 0.55f
        status.setTextColor(if (running) green else muted)
        val editable = ready && stateKnown && !busy && !transitioning && !running && pingRequest == null
        val loginEditable = !busy && !running && !transitioning
        importButton.isEnabled = loginEditable; input.isEnabled = loginEditable; password.isEnabled = loginEditable
        refreshButton.isEnabled = ready && stateKnown && !busy && !transitioning && pingRequest == null && account != null
        clearButton.isEnabled = ready && stateKnown && !busy && !transitioning && pingRequest==null && account!=null
        quickButton.isEnabled=editable && account?.canConnect==true && repo.servers.isNotEmpty()
        accountName.text=tr("نام کاربری: ","Username: ")+(account?.username?:"—")
        loginSpinner.visibility=if(manualLogin)View.VISIBLE else View.GONE
        importButton.text=if(manualLogin)"" else tr("ورود به حساب","Sign in")
        if(manualLogin){importButton.animate().scaleX(0.97f).scaleY(0.97f).setDuration(160).start()}
        else {importButton.animate().scaleX(1f).scaleY(1f).setDuration(180).start()}
        pingButton.isEnabled = editable && hasSub && account != null
        pingButton.text = if (pingRequest != null) tr("در حال تست…","Testing…") else tr("تست پینگ","Test latency")

        val current = account
        usageText.text = current?.let { "${gb(it.remainingBytes)} / ${it.gb} GB" } ?: "—"
        usageDetail.text = current?.let {
            val age = ((android.os.SystemClock.elapsedRealtime()-it.receivedElapsed).coerceAtLeast(0)/1000)
            tr("مصرف: ${gb(it.usedBytes)} GB · آخرین دریافت: ${age} ثانیه قبل","Used: ${gb(it.usedBytes)} GB · Updated ${age}s ago")
        } ?: tr("مصرف: نامشخص","Usage: unknown")
        usageBar.visibility = if (current == null) View.GONE else View.VISIBLE
        usageBar.progress = current?.let { (it.remainingBytes.toDouble()/it.limitBytes*1000).toInt().coerceIn(0,1000) } ?: 0
        updateExpiry()

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
                setOnClickListener { if (editable) { MmkvManager.setSelectServer(id); render(tr("سرور انتخاب شد.","Server selected.")) } }
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
    private fun updateExpiry() {
        val a = account
        val left = a?.remainingSeconds() ?: 0
        expiry.text = when {
            a == null -> tr("اعتبار: اعلام نشده","Validity: unknown")
            left == 0L -> tr("اعتبار به پایان رسیده","Subscription expired")
            else -> tr("${left/86400} روز و ${left%86400/3600} ساعت و ${left%3600/60} دقیقه و ${left%60} ثانیه","${left/86400}d ${left%86400/3600}h ${left%3600/60}m ${left%60}s")
        }
        expiry.setTextColor(if (a == null) muted else if (left == 0L) red else green)
    }
    private val expiryTick = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            updateExpiry()
            val a = account
            if (a != null && a.remainingSeconds() == 0L) {
                account = null; sessionStore.clear()
                if (running || transitioning) LauncherManager.stopService(this@MainActivity) else repo.clear()
                render(tr("اعتبار حساب به پایان رسیده است.","Your subscription has expired."))
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (running && account != null && !busy && !transitioning && now-lastPoll>=60000L) {
                lastPoll=now
                pollUsage()
            }
            timer.postDelayed(this,1000)
        }
    }
    private fun maybeRestore(force: Boolean = false) {
        if (!ready || !stateKnown || busy || account != null || (restoreAttempted && !force)) return
        restoreAttempted = true
        val token = sessionStore.read()
        if (token == null) {
            starting = false
            render(if (running) tr("تونل VPN فعال است","VPN is connected") else tr("نام کاربری و رمز عبور را وارد کنید.","Enter username and password."))
            return
        }
        starting = true
        busy = true; render(tr("در حال ورود خودکار…","Signing in automatically…"))
        val wasRunning = running
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val a = accountApi.session(token)
                if (!wasRunning) checkedImport(a)
                a
            } }
            busy = false
            starting = false
            result.onSuccess { account = it; if (!it.canConnect && running) LauncherManager.stopService(this@MainActivity); render(if (it.canConnect) tr("به دلتا خوش آمدید","Welcome to Delta") else tr("اشتراک فعال قابل اتصال نیست.","Subscription cannot connect.")) }.onFailure {
                val invalid = it is DeltaApiException && it.status in listOf(401, 409)
                if (invalid) sessionStore.clear()
                if (running) LauncherManager.stopService(this@MainActivity)
                else repo.clear()
                render(if (invalid) tr("نشست منقضی یا لغو شده؛ دوباره وارد شوید.","Session expired or revoked. Sign in again.") else tr("ورود خودکار ناموفق بود؛ برای تلاش دوباره ورود را بزنید.","Automatic sign-in failed. Tap Sign in to retry."))
            }
        }
    }
    private fun checkedImport(a: DeltaAccount) {
        check(a.remainingSeconds() > 0)
        repo.refresh(if (a.canConnect) a.configs else emptyList())
    }
    private fun signIn() {
        if (busy || running || transitioning) return
        if (!ready) { render(tr("موتور اتصال هنوز آماده نیست؛ دوباره تلاش کنید.","VPN engine is not ready. Try again.")); return }
        if (!stateKnown) { render(tr("در حال بررسی وضعیت سرویس…","Checking VPN service…")); queryState(); return }
        if (sessionStore.read() != null && input.text.isBlank() && password.text.isBlank()) { maybeRestore(true); return }
        val name = input.text.toString().trim()
        val secret = password.text.toString()
        if (name.isBlank() || secret.isBlank()) { render(tr("نام کاربری و رمز عبور را وارد کنید.","Enter username and password.")); return }
        password.text.clear()
        manualLogin=true;busy = true; render(tr("در حال ورود…","Signing in…"))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                repo.clear()
                val a = accountApi.login(name, secret)
                try { checkedImport(a); sessionStore.save(a.token); a } catch (e: Exception) { repo.clear(); runCatching { accountApi.logout(a.token) }; throw e }
            } }
            busy = false;manualLogin=false
            result.onSuccess { account = it; delays.clear(); input.text.clear(); render(if (repo.servers.isEmpty()) tr("کانفیگ اختصاصی فعال در دسترس نیست.","No active dedicated server available.") else tr("به دلتا خوش آمدید","Welcome to Delta")) }
                .onFailure { account = null; render(tr("ورود یا دریافت اشتراک ناموفق بود؛ حساب و تنظیمات سرور را بررسی کنید.","Sign-in or subscription loading failed. Check account and server settings.")) }
        }
    }
    private fun pollUsage() {
        val previous=account ?: return
        if (busy || transitioning) return
        busy=true
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { accountApi.status(previous.token) } }
            busy=false
            result.onSuccess { updated ->
                account=updated
                if (!updated.canConnect && running) LauncherManager.stopService(this@MainActivity)
                render(if (!updated.canConnect) tr("حجم یا اعتبار یا دسترسی اشتراک به پایان رسیده است.","Subscription data, validity or access has ended.") else null)
            }.onFailure {
                if (it is DeltaApiException && it.status==401) {
                    account=null;sessionStore.clear()
                    if(running) LauncherManager.stopService(this@MainActivity) else repo.clear()
                    render(tr("نشست لغو شده است.","Session revoked."))
                } else {
                    render(tr("دریافت مصرف از پنل ناموفق بود؛ آخرین مقدار نمایش داده می‌شود.","Usage refresh failed. Last known value shown."))
                }
            }
        }
    }
    private fun validateAccount(connectAfter: Boolean,quickAfter:Boolean=false) {
        val previous = account ?: return
        if (busy || transitioning || pingRequest != null) return
        if (running) { if (!connectAfter && !quickAfter) pollUsage(); return }
        busy = true; render(tr("در حال بررسی اعتبار حساب…","Checking subscription…"))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val updated = accountApi.session(previous.token)
                checkedImport(updated)
                updated
            } }
            busy = false
            result.onSuccess {
                account = it; delays.clear(); render(if (repo.servers.isEmpty()) tr("کانفیگ اختصاصی فعال در دسترس نیست.","No active dedicated server available.") else tr("اعتبار حساب تأیید شد.","Subscription verified."))
                if(quickAfter && it.canConnect && repo.servers.isNotEmpty())testServers(true)
                else if (connectAfter && it.canConnect && repo.canConnect()) {
                    val request = VpnService.prepare(this@MainActivity)
                    if (request == null) startTunnel() else permission.launch(request)
                }
            }.onFailure {
                account = null; repo.clear(); delays.clear()
                if (it is DeltaApiException && it.status == 401) sessionStore.clear()
                render(tr("اعتبار حساب یا اتصال به سرور تأیید نشد؛ دوباره تلاش کنید.","Account or server verification failed. Try again."))
            }
        }
    }
    private fun signOut() {
        if (busy || running || transitioning || pingRequest != null) return
        val previous = account
        account = null; sessionStore.clear(); repo.clear(); delays.clear(); input.text.clear(); password.text.clear();hideDrawer();serverSection.visibility=View.GONE
        render(tr("از حساب خارج شدید.","Signed out."))
        if (previous != null) lifecycleScope.launch(Dispatchers.IO) { runCatching { accountApi.logout(previous.token) } }
    }
    private fun toggle() {
        if (running) {
            transitioning = true; render(tr("در حال قطع اتصال…","Disconnecting…"))
            LauncherManager.stopService(this); timer.postDelayed(timeout, 15000)
        } else if (account != null && repo.canConnect()) {
            validateAccount(true)
        }
    }
    private fun startTunnel() {
        if (account?.canConnect != true || (account?.remainingSeconds() ?: 0) <= 0 || !repo.canConnect()) return
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        transitioning = true; render(tr("در حال اتصال…","Connecting…"))
        if (!LauncherManager.startServiceFromToggle(this, account?.deadlineElapsed ?: 0L)) {
            transitioning = false; render(tr("شروع سرویس ناموفق بود.","VPN service could not start."))
        } else timer.postDelayed(timeout, 20000)
    }
}

/** Lightweight animated aurora behind both screens; no network or image assets. */
private class DeltaMotionView(context:Context):View(context){
    var active=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        if(width<=0||height<=0)return
        canvas.drawColor(Color.parseColor("#0D1524"))
        val enabled=android.os.Build.VERSION.SDK_INT<26||ValueAnimator.areAnimatorsEnabled()
        val t=if(enabled)android.os.SystemClock.elapsedRealtime()/7000.0 else 0.0
        val w=width.toFloat();val h=height.toFloat()
        for(i in 0..2){
            val phase=t+i*2.1
            val x=w*(0.5f+0.35f*kotlin.math.sin(phase).toFloat())
            val y=h*(0.22f+i*0.25f+0.11f*kotlin.math.cos(phase*0.72).toFloat())
            val color=when(i){0->0x603478F6;1->0x404D39BA;else->0x3027C6BD}
            val radius=maxOf(w,h)*0.47f
            paint.shader=RadialGradient(x,y,radius,intArrayOf(color,0x000D1524),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,w,h,paint)
        }
        paint.shader=null
        if(active&&enabled&&isShown)postInvalidateDelayed(40)
    }
}
