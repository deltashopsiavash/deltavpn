package com.v2ray.ang.ui.main

import android.content.BroadcastReceiver
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import com.v2ray.ang.delta.DeltaAppUpdate
import com.v2ray.ang.delta.DeltaNewsItem
import com.v2ray.ang.delta.DeltaNewsFeed
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
import com.v2ray.ang.delta.DeltaRefreshCursor
import com.v2ray.ang.delta.DeltaTraffic
import com.v2ray.ang.delta.DeltaTrafficView
import android.os.SystemClock
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var repo: DeltaSubscriptions
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var trafficPanel: DeltaTrafficView
    private var trafficSample: LongArray? = null
    private var trafficAcceptAfter = 0L

    private fun clearTraffic() {
        trafficSample = null
        trafficAcceptAfter = SystemClock.elapsedRealtime()
        if (::trafficPanel.isInitialized) trafficPanel.show(0L, 0L)
    }

    private fun trafficAllowed(now: Long): Boolean {
        val a = account ?: return false
        return newsForeground && ready && stateKnown && running && !transitioning &&
            a.canConnect && a.remainingBytes > 0L && a.deadlineElapsed > now
    }

    private fun updateTraffic() {
        if (!::trafficPanel.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        if (!trafficAllowed(now)) { clearTraffic(); return }
        val sample = trafficSample
        if (DeltaTraffic.fresh(sample, now, trafficAcceptAfter) && sample != null) {
            trafficPanel.show(sample[1], sample[2])
        } else {
            trafficSample = null
            trafficPanel.show(0L, 0L)
        }
    }
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
    private var restoreRetryAt = 0L
    private var catalogPending = false
    private val accountApi = DeltaAccountApi()
    private var stateKnown = false
    private var stateQueryGeneration = 0
    private lateinit var loginStatus: TextView
    private lateinit var usageText: TextView
    private lateinit var usageDetail: TextView
    private lateinit var newsBell: Button
    private lateinit var newsBadge: TextView
    private var newsToken: String? = null
    private var newsFeed: DeltaNewsFeed? = null
    private var newsLoading = false
    private var newsGeneration = 0
    private var newsLastPoll = 0L
    private var newsDialog: Dialog? = null
    private var floatingDialog: Dialog? = null
    private val floatingShown = mutableSetOf<Long>()
    private var newsForeground = false

    private var usageStale=false
    private fun stableDeviceId():String? {
        val raw=runCatching{android.provider.Settings.Secure.getString(contentResolver,android.provider.Settings.Secure.ANDROID_ID)}.getOrNull()
            ?.trim()?.lowercase(Locale.US) ?: return null
        if(!raw.matches(Regex("[0-9a-f]{1,16}"))||raw.all{it=='0'}||raw=="9774d56d682e549c")return null
        val scoped="com.delta.vpn:device:v2:"+raw
        return java.security.MessageDigest.getInstance("SHA-256").digest(scoped.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(Locale.US,it.toInt() and 255)}
    }
    private fun legacyDeviceId():String? = getSharedPreferences("delta_device",Context.MODE_PRIVATE).getString("id",null)?.takeIf{it.matches(Regex("[a-f0-9]{64}"))}
    private fun deviceId():String {
        stableDeviceId()?.let{return it}
        val prefs=getSharedPreferences("delta_device",Context.MODE_PRIVATE)
        return legacyDeviceId() ?: ByteArray(32).also{java.security.SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(Locale.US,it.toInt() and 255)}.also{check(prefs.edit().putString("id",it).commit())}
    }
    private lateinit var deviceScreen:FrameLayout
    private lateinit var deviceList:LinearLayout
    private var deviceRequest=0
    private fun closeDevices(){deviceRequest++;deviceScreen.visibility=View.GONE}
    private fun showDevices(revoke:Long?=null){
        val token=account?.token?:return
        val request=++deviceRequest
        deviceScreen.visibility=View.VISIBLE
        deviceList.removeAllViews()
        put(deviceList,button(tr("بازگشت","Back"),panel){closeDevices()},0)
        put(deviceList,centered(tr("📱 دستگاه‌ها","📱 Devices"),23,fg,true),18)
        put(deviceList,centered(tr("در حال دریافت…","Loading…"),14,muted),18)
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO){runCatching{accountApi.devices(token,revoke)}}
            if(request!=deviceRequest||account?.token!=token)return@launch
            deviceList.removeAllViews()
            put(deviceList,button(tr("بازگشت","Back"),panel){closeDevices()},0)
            put(deviceList,centered(tr("📱 دستگاه‌ها","📱 Devices"),23,fg,true),18)
            result.onSuccess{r->
                val devices=r.getAsJsonArray("devices").map{it.asJsonObject}
                val current=r.get("current_id").asLong
                val limit=r.get("device_limit").asInt
                put(deviceList,centered(tr("دستگاه‌های ثبت‌شده: ","Registered devices: ")+devices.size+" / "+limit,16,fg),12)
                for(v in devices){
                    val id=v.get("id").asLong
                    val seen=java.text.DateFormat.getDateTimeInstance().format(java.util.Date(v.get("last_seen").asLong*1000))
                    val card=column().apply{background=shape(panel,16);setPadding(dp(16),dp(14),dp(16),dp(14))}
                    put(card,label(v.get("model").asString,17,fg,true))
                    if(id==current)put(card,label(tr("این دستگاه","This device"),13,green),6)
                    put(card,label(tr("آخرین فعالیت: ","Last activity: ")+seen,13,muted),8)
                    put(card,label(tr("مصرف مستقل: نامشخص","Individual usage: unavailable"),13,muted),6)
                    if(id!=current)put(card,button(tr("خروج دستگاه","Revoke device"),Color.parseColor("#A52F42")){
                        AlertDialog.Builder(this@MainActivity).setMessage(tr("نشست این دستگاه لغو شود؟","Revoke this device session?")).setPositiveButton(tr("خروج دستگاه","Revoke")){_,_->showDevices(id)}.setNegativeButton(tr("انصراف","Cancel"),null).show()
                    },12)
                    put(deviceList,card,14)
                }
                put(deviceList,button(tr("به‌روزرسانی","Refresh"),panel){showDevices()},16)
            }.onFailure{
                put(deviceList,centered(tr("دریافت دستگاه‌ها ناموفق بود.","Could not load devices."),14,muted),16)
                put(deviceList,button(tr("تلاش مجدد","Retry")){showDevices()},12)
            }
        }
    }
    private var powerPulse: ValueAnimator?=null
    private var lastPoll = 0L
    private var usagePoll: Job? = null
    private val catalogLock = Any()
    private fun cancelUsagePoll(){
        usagePoll?.cancel(); usagePoll=null
    }
    private fun clearCatalog(){ synchronized(catalogLock){repo.clear()} }
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
    private lateinit var appUpdateButton: Button
    private var appUpdate: DeltaAppUpdate? = null
    private var appUpdateLoading = false
    private var appUpdateLastPoll = -60000L
    private fun installedVersion():String = runCatching{packageManager.getPackageInfo(packageName,0).versionName.orEmpty()}.getOrDefault("")
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
                DeltaTraffic.MESSAGE -> {
                    val now = SystemClock.elapsedRealtime()
                    val sample = intent.getLongArrayExtra(DeltaTraffic.EXTRA)
                    if (trafficAllowed(now) && DeltaTraffic.fresh(sample, now, trafficAcceptAfter) &&
                        (trafficSample?.get(0) ?: -1L) <= (sample?.get(0) ?: -1L)) {
                        trafficSample = sample
                        updateTraffic()
                    }
                }
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
                    if (!running || transitioning) clearTraffic()
                    stateQueryGeneration++; stateKnown = true; running = true; transitioning = false; timer.removeCallbacks(timeout)
                    render(tr("اتصال برقرار است","Connected")); maybeRestore()
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

        put(login, centered("DELTA VPN", 29, fg, true), 16)
        put(login, roundLogo(), 14, dp(88), dp(88), Gravity.CENTER_HORIZONTAL)
        put(login, centered(tr("به دلتا خوش آمدید","Welcome to Delta"), 15, muted), 18)
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
        put(login, centered("v1.7.5", 11, muted), 20)

        val menuButton=button("☰",panel){showDrawer()}
        menuButton.contentDescription=tr("منوی حساب","Account menu")
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        header.addView(menuButton,LinearLayout.LayoutParams(dp(52),dp(48)))
        header.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        val bellFrame=FrameLayout(this).apply { clipChildren=false;clipToPadding=false }
        newsBell=button("🔔",panel){showNewsInbox()}.apply{
            contentDescription=tr("اطلاع‌رسانی","Announcements")
            // Native Button's animated Z can cover the overlay, even if it is
            // added earlier. Keep both header button and its badge at fixed Z.
            stateListAnimator=null
            elevation=0f
            translationZ=0f
        }
        bellFrame.addView(newsBell,FrameLayout.LayoutParams(dp(52),dp(48),Gravity.CENTER))
        newsBadge=centered("",12,Color.WHITE,true).apply{
            background=shape(Color.parseColor("#D93243"),12,navy)
            minWidth=dp(24)
            setPadding(dp(5),0,dp(5),0)
            includeFontPadding=false
            maxLines=1
            gravity=Gravity.CENTER
            elevation=dp(8).toFloat()
            visibility=View.GONE
            isClickable=false
            isFocusable=false
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bellFrame.addView(newsBadge,FrameLayout.LayoutParams(-2,dp(24),Gravity.TOP or Gravity.END).apply {
            topMargin=dp(1)
            marginEnd=dp(1)
        })
        newsBadge.bringToFront()
        header.addView(bellFrame,LinearLayout.LayoutParams(dp(64),dp(56)))
        put(dashboard,header)
        put(dashboard, centered("DELTA VPN", 30, fg, true), 0)
        put(dashboard, roundLogo(), 14, dp(78), dp(78), Gravity.CENTER_HORIZONTAL)
        dashboard.addView(View(this),LinearLayout.LayoutParams(1,0,0.45f))
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

        trafficPanel = DeltaTrafficView(this, english)
        dashboard.addView(trafficPanel, LinearLayout.LayoutParams(-1, dp(54)).apply {
            leftMargin = dp(17); rightMargin = dp(17); gravity = Gravity.CENTER_HORIZONTAL
        })
        connect = ImageButton(this).apply {
            setImageResource(com.v2ray.ang.R.drawable.delta_power)
            scaleType = ImageView.ScaleType.CENTER
            isFocusable = true
            contentDescription = tr("اتصال یا قطع اتصال","Connect or disconnect")
            setPadding(0,0,0,0)
            stateListAnimator = null; elevation = dp(3).toFloat()
            setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); toggle() }
        }
        put(dashboard, connect, 24, dp(158), dp(158), Gravity.CENTER_HORIZONTAL)
        status = centered(tr("برای اتصال لمس کنید","Tap to connect"), 14, muted)
        put(dashboard, status, 12)

        serverPicker=button(tr("🌐 سرورهای قابل اتصال","🌐 Available servers")+"  ▾",panel) {
            val show=serverSection.visibility!=View.VISIBLE
            serverSection.visibility=if(show)View.VISIBLE else View.GONE
            serverPicker.text=tr("🌐 سرورهای قابل اتصال","🌐 Available servers")+if(show)"  ▴" else "  ▾"
            if(show){serverSection.alpha=0f;serverSection.translationY=-dp(8).toFloat();serverSection.animate().alpha(1f).translationY(0f).setDuration(220).start()}
        }
        put(dashboard,serverPicker,30)
        serverSection=column().apply { visibility=View.GONE }
        quickButton=button(tr("⚡ اتصال سریع","⚡ Quick connect")){validateAccount(false,true)}
        pingButton=button(tr("📶 تست پینگ","📶 Ping"),panel){testServers()}.apply{textSize=12f;minWidth=0;setPadding(dp(6),0,dp(6),0)}
        val serverActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        serverActions.addView(quickButton,LinearLayout.LayoutParams(0,dp(48),1.6f).apply{marginEnd=dp(8)})
        serverActions.addView(pingButton,LinearLayout.LayoutParams(0,dp(48),1f))
        put(serverSection,serverActions,10)
        servers=column();put(serverSection,servers,8)
        put(dashboard,serverSection)
        dashboard.addView(View(this),LinearLayout.LayoutParams(1,0,1f))
        put(dashboard, centered("v"+installedVersion(),11,muted),16)

        drawer=FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt());visibility=View.GONE;setOnClickListener{hideDrawer()} }
        val drawerScroll=ScrollView(this).apply { isFillViewport=true;setBackgroundColor(navy);isClickable=true }
        val width=minOf(dp(320),(resources.displayMetrics.widthPixels*0.86f).toInt())
        drawer.addView(drawerScroll,FrameLayout.LayoutParams(width,-1,Gravity.START))
        val menu=column().apply {setPadding(dp(16),dp(16),dp(16),dp(18));isClickable=true}
        drawerScroll.addView(menu)
        put(menu,centered(tr("حساب کاربری","My account"),19,fg,true))
        put(menu,quota,18)
        accountName=centered("—",14,fg,true);put(menu,accountName,16)
        put(menu,button(tr("📱 دستگاه‌ها","📱 Devices"),panel){showDevices()},12)
        put(menu,button(tr("تغییر زبان","Change language"),panel){chooseLanguage()},18)
        put(menu,button(tr("پشتیبانی","Support"),panel){openContact("support")},10)
        put(menu,button(tr("خرید اشتراک","Buy subscription"),panel){openContact("purchase")},10)
        appUpdateButton=button(tr("⬇ دریافت نسخه جدید برنامه","⬇ Download app update"),blue){openAppUpdate()}.apply{visibility=View.GONE}
        put(menu,appUpdateButton,10)
        clearButton=button(tr("خروج از حساب","Sign out"),Color.parseColor("#A52F42")){requestSignOut()}
        put(menu,clearButton,10)
        put(menu,centered("v"+installedVersion(),11,muted),16)
        shell.addView(drawer,FrameLayout.LayoutParams(-1,-1))
        deviceScreen=FrameLayout(this).apply{setBackgroundColor(navy);visibility=View.GONE;isClickable=true}
        val deviceScroll=ScrollView(this).apply{isFillViewport=true}
        deviceList=column().apply{setPadding(dp(24),dp(18),dp(24),dp(24))}
        deviceScroll.addView(deviceList)
        deviceScreen.addView(deviceScroll,FrameLayout.LayoutParams(-1,-1))
        shell.addView(deviceScreen,FrameLayout.LayoutParams(-1,-1))
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){
            override fun handleOnBackPressed(){
                if(deviceScreen.visibility==View.VISIBLE)closeDevices()
                else if(drawer.visibility==View.VISIBLE)hideDrawer()
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

    override fun onResume() { super.onResume(); clearTraffic(); newsForeground=true;motion.active=true;motion.invalidate(); if (ready) queryState(); refreshNews(true) }
    override fun onPause() { newsForeground=false;clearTraffic();motion.active=false;super.onPause() }
    private fun showDrawer() {
        drawer.visibility=View.VISIBLE
        refreshAppUpdate(true)
        val child=drawer.getChildAt(0)
        child.translationX=if(english)-child.width.toFloat() else child.width.toFloat()
        child.animate().translationX(0f).setDuration(220).start()
    }
    private fun hideDrawer(){drawer.visibility=View.GONE;if(::deviceScreen.isInitialized)closeDevices()}
    private fun renderAppUpdate(){
        if(!::appUpdateButton.isInitialized)return
        val u=appUpdate
        appUpdateButton.visibility=if(u!=null&&account!=null)View.VISIBLE else View.GONE
        appUpdateButton.isEnabled=!appUpdateLoading
        if(u!=null)appUpdateButton.text=tr("⬇ دانلود نسخه جدید "+u.version,"⬇ Download version "+u.version)
    }
    private fun refreshAppUpdate(force:Boolean=false,open:Boolean=false){
        if(!::appUpdateButton.isInitialized||appUpdateLoading||!newsForeground||account==null)return
        val now=SystemClock.elapsedRealtime()
        if(!force&&now-appUpdateLastPoll<60000L)return
        // Prevent duplicate polling and discard a failed/disabled response.
        appUpdateLoading=true;appUpdateLastPoll=now;renderAppUpdate()
        val token=account?.token
        val installed=installedVersion()
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO){runCatching{accountApi.availableUpdate(installed)}}
            appUpdateLoading=false
            appUpdate=if(account?.token==token)result.getOrNull() else null
            renderAppUpdate()
            if(open&&newsForeground&&account?.token==token){
                val u=appUpdate
                if(u==null){Toast.makeText(this@MainActivity,tr("نسخه جدید در دسترس نیست یا بررسی ناموفق بود.","No update available or check failed."),Toast.LENGTH_LONG).show();return@launch}
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(tr("نسخه جدید "+u.version,"New version "+u.version))
                    .setMessage(tr("فایل رسمی در مرورگر دانلود می‌شود. پس از دانلود، نصب را تأیید کنید؛ نسخه قبلی را حذف نکنید.","Download the official APK in your browser, then confirm installation. Do not uninstall the existing app."))
                    .setPositiveButton(tr("دانلود","Download")){_,_->
                        if(appUpdate==u&&account?.token==token)runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(u.url)))}
                            .onFailure{Toast.makeText(this@MainActivity,tr("باز کردن لینک ناموفق بود.","Could not open download."),Toast.LENGTH_LONG).show()}
                    }.setNegativeButton(tr("بعداً","Later"),null).show()
            }
        }
    }
    private fun openAppUpdate(){refreshAppUpdate(true,true)}
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
        cancelUsagePoll();newsForeground=false;clearNews();powerPulse?.cancel();powerPulse=null
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
    private fun gb(bytes: Long): String = String.format(Locale.US, "%.3f", bytes.coerceAtLeast(0) / 1073741824.0).trimEnd('0').trimEnd('.')
    private fun testServers(quick:Boolean=false) {
        if (pingRequest != null || busy || transitioning || running || repo.servers.isEmpty()) return
        cancelUsagePoll()
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
        if (!transitioning && message == null && pingRequest==null) status.text = if(running)tr("اتصال برقرار است","Connected") else tr("برای اتصال لمس کنید","Tap to connect")
        updateTraffic()
        connect.background = powerBackground(running)
        if(transitioning && powerPulse==null && (android.os.Build.VERSION.SDK_INT<26 || ValueAnimator.areAnimatorsEnabled())){
            powerPulse=ValueAnimator.ofFloat(1f,0.94f,1f).apply{duration=1000;repeatCount=ValueAnimator.INFINITE;addUpdateListener{val v=it.animatedValue as Float;connect.scaleX=v;connect.scaleY=v};start()}
        }else if(!transitioning){powerPulse?.cancel();powerPulse=null;connect.scaleX=1f;connect.scaleY=1f}
        connect.contentDescription = if (running) tr("قطع اتصال","Disconnect") else tr("اتصال","Connect")
        connect.isEnabled = ready && stateKnown && !busy && !transitioning && pingRequest == null && (running || account?.canConnect == true || (account!=null && usageStale))
        connect.alpha = if (connect.isEnabled || (account!=null && (busy || transitioning || pingRequest!=null))) 1f else 0.55f
        status.setTextColor(if (running) green else muted)
        val editable = ready && stateKnown && !busy && !transitioning && !running && pingRequest == null
        val loginEditable = !busy && !running && !transitioning
        importButton.isEnabled = loginEditable; input.isEnabled = loginEditable; password.isEnabled = loginEditable
        clearButton.isEnabled = ready && stateKnown && !busy && !transitioning && pingRequest==null && account!=null
        quickButton.isEnabled=editable && (account?.canConnect==true || (account!=null && usageStale))
        accountName.text=tr("نام کاربری: ","Username: ")+(account?.username?:"—")
        syncNewsIdentity()
        if(account==null)appUpdate=null
        renderAppUpdate()
        loginSpinner.visibility=if(manualLogin)View.VISIBLE else View.GONE
        importButton.text=if(manualLogin)"" else tr("ورود به حساب","Sign in")
        if(manualLogin){importButton.animate().scaleX(0.97f).scaleY(0.97f).setDuration(160).start()}
        else {importButton.animate().scaleX(1f).scaleY(1f).setDuration(180).start()}
        pingButton.isEnabled = editable && hasSub && account != null
        pingButton.text = if (pingRequest != null) tr("در حال تست…","Testing…") else tr("📶 تست پینگ","📶 Ping")

        updateUsage()
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
            put(names,label(profile.remarks.ifBlank { tr("سرور","Server") },14,fg,true).apply { maxLines = 2 })
    
            row.addView(names,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(label(if (latency == null) "—" else if (latency <= 0) "Timeout" else "$latency ms",12,color,true))
            put(servers,row,8)
        }
    }
    private fun clearNews(){
        newsGeneration++;newsLoading=false;newsToken=null;newsFeed=null;newsLastPoll=0L;floatingShown.clear()
        val inbox=newsDialog;newsDialog=null;inbox?.dismiss()
        val floating=floatingDialog;floatingDialog=null;floating?.dismiss()
        if(::newsBadge.isInitialized)newsBadge.visibility=View.GONE
    }
    private fun syncNewsIdentity(){
        val token=account?.token
        if(token!=newsToken){
            clearNews()
            newsToken=token
            if(token!=null)refreshNews(true)
        }
        if(::newsBell.isInitialized)newsBell.isEnabled=token!=null
    }
    private fun updateNewsBadge(){
        if(!::newsBadge.isInitialized)return
        val n=newsFeed?.unread?:0
        newsBadge.visibility=if(n>0)View.VISIBLE else View.GONE
        newsBadge.text=if(n>99)"99+" else n.toString()
        if(n>0)newsBadge.bringToFront()
        newsBell.contentDescription=tr("اطلاع‌رسانی، $n خوانده نشده","Announcements, $n unread")
    }
    private fun refreshNews(force:Boolean=false){
        val token=account?.token?:return
        if(!newsForeground||newsLoading||newsToken!=token)return
        val now=android.os.SystemClock.elapsedRealtime()
        if(!force&&now-newsLastPoll<30000L)return
        newsLoading=true;newsLastPoll=now
        val generation=newsGeneration
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO){runCatching{accountApi.news(token)}}
            if(generation!=newsGeneration||account?.token!=token)return@launch
            newsLoading=false
            result.onSuccess{feed->newsFeed=feed;updateNewsBadge();maybeShowFloating()}
        }
    }
    private fun newsDate(t:Long):String = java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm",Locale.US).apply{
        timeZone=java.util.TimeZone.getTimeZone("Asia/Tehran")
    }.format(java.util.Date(t*1000L))
    private fun openNewsLink(item:DeltaNewsItem){
        if(item.url.isBlank())return
        // Only explicit user taps open validated HTTPS links. No APK auto-install.
        runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(item.url)))}
            .onFailure{Toast.makeText(this,tr("باز کردن لینک ناموفق بود.","Unable to open link."),Toast.LENGTH_SHORT).show()}
    }
    private fun newsCard(title:String,subtitle:String?=null):Pair<Dialog,LinearLayout>{
        val d=Dialog(this)
        val content=column().apply {
            layoutDirection=if(english)View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            background=shape(panel,24,Color.parseColor("#344A6D"))
            setPadding(dp(20),dp(20),dp(20),dp(18))
        }
        put(content,centered(title,20,fg,true))
        if(subtitle!=null)put(content,centered(subtitle,12,muted),8)
        d.setContentView(content)
        d.setCanceledOnTouchOutside(false)
        return d to content
    }
    private fun displayNewsDialog(d:Dialog){
        if(isFinishing||isDestroyed)return
        d.show()
        d.window?.apply{
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(dp(430),(resources.displayMetrics.widthPixels-dp(32)).coerceAtLeast(dp(240))),-2)
            setGravity(Gravity.CENTER)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.68f)
        }
    }
    private fun newsBody(parent:LinearLayout,body:String){
        val scroll=ScrollView(this).apply { isFillViewport=false; overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        val text=label(body,15,fg).apply{
            gravity=Gravity.START;setLineSpacing(dp(5).toFloat(),1.05f);setTextIsSelectable(true)
            setPadding(dp(14),dp(12),dp(14),dp(12));background=shape(navy,16)
        }
        scroll.addView(text)
        val max=(resources.displayMetrics.heightPixels*0.40).toInt().coerceAtLeast(dp(120))
        text.measure(View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.widthPixels-dp(84)).coerceAtLeast(dp(160)),View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        put(parent,scroll,16,-1,minOf(max,text.measuredHeight.coerceAtLeast(dp(72))))
    }
    private fun acknowledgeNews(item:DeltaNewsItem,floating:Boolean,onDone:(Boolean)->Unit){
        val token=account?.token?:return
        // Invalidate an in-flight poll so an older response cannot restore an unread badge.
        newsGeneration++;val requestGeneration=newsGeneration;newsLoading=true
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO){runCatching{accountApi.acknowledgeNews(token,item.id,floating)}}
            if(requestGeneration!=newsGeneration||account?.token!=token)return@launch
            newsLoading=false
            result.onSuccess{newsFeed=it;updateNewsBadge()}
            if(result.isFailure)Toast.makeText(this@MainActivity,tr("ثبت وضعیت خبر ناموفق بود؛ دوباره تلاش کنید.","Could not save announcement status. Try again."),Toast.LENGTH_SHORT).show()
            onDone(result.isSuccess)
        }
    }
    private fun showNewsInbox(){
        val token=account?.token?:return
        if(newsDialog?.isShowing==true||floatingDialog?.isShowing==true)return
        val (d,content)=newsCard(tr("🔔 اطلاع‌رسانی","🔔 Announcements"),tr("آخرین خبرهای دلتا","Latest Delta news"))
        newsDialog=d
        val scroll=ScrollView(this)
        val items=column()
        scroll.addView(items)
        put(content,scroll,16,-1,minOf(dp(440),(resources.displayMetrics.heightPixels*0.50).toInt()))
        val close=button(tr("بستن","Close"),navy){d.dismiss()}
        put(content,close,14)
        d.setOnDismissListener{if(newsDialog===d){newsDialog=null;maybeShowFloating()}}
        fun renderPage(feed:DeltaNewsFeed,append:Boolean){
            if(!d.isShowing||account?.token!=token)return
            if(!append)items.removeAllViews()
            if(feed.news.isEmpty()&&!append)put(items,centered(tr("هنوز خبری منتشر نشده است.","No announcements yet."),15,muted),12)
            for(item in feed.news){
                val preview=item.body.replace(Regex("\\s+")," ").take(96)
                val row=column().apply{
                    background=shape(navy,14,if(item.read)null else blue)
                    setPadding(dp(14),dp(12),dp(14),dp(12))
                    isClickable=true;isFocusable=true
                    setOnClickListener{
                        // Dismiss without allowing a pending floating news to obscure the detail.
                        newsDialog=null;d.dismiss();showNewsDetail(item)
                    }
                }
                put(row,label((if(item.read)"" else "●  ")+preview,15,fg,!item.read))
                put(row,label(newsDate(item.created),11,muted),6)
                put(items,row,8)
            }
            if(feed.nextBefore>0){
                lateinit var more:Button
                more=button(tr("خبرهای قدیمی‌تر","Older announcements"),navy){
                    more.isEnabled=false
                    lifecycleScope.launch{
                        val page=withContext(Dispatchers.IO){runCatching{accountApi.news(token,feed.nextBefore)}}
                        if(!d.isShowing||account?.token!=token)return@launch
                        page.onSuccess{items.removeView(more);renderPage(it,true)}.onFailure{more.isEnabled=true;Toast.makeText(this@MainActivity,tr("دریافت خبر ناموفق بود.","Could not load news."),Toast.LENGTH_SHORT).show()}
                    }
                }
                put(items,more,12)
            }
        }
        displayNewsDialog(d)
        newsFeed?.let{renderPage(it,false)}?:put(items,centered(tr("در حال دریافت خبرها…","Loading announcements…"),14,muted),12)
        val listGeneration=newsGeneration
        lifecycleScope.launch{
            val result=withContext(Dispatchers.IO){runCatching{accountApi.news(token)}}
            if(!d.isShowing||account?.token!=token||listGeneration!=newsGeneration)return@launch
            result.onSuccess{if(!newsLoading){newsFeed=it;updateNewsBadge()};renderPage(it,false)}.onFailure{
                if(newsFeed==null){items.removeAllViews();put(items,centered(tr("دریافت خبر ناموفق بود؛ دوباره باز کنید.","Could not load announcements. Reopen to retry."),14,muted),12)}
            }
        }
    }
    private fun showNewsDetail(item:DeltaNewsItem){
        val (d,content)=newsCard(tr("🔔 خبر دلتا","🔔 Delta news"),newsDate(item.created))
        newsDialog=d
        newsBody(content,item.body)
        if(item.url.isNotBlank())put(content,button(item.buttonLabel.ifBlank{tr("باز کردن لینک","Open link")},blue){openNewsLink(item)},16)
        put(content,button(tr("بازگشت به خبرها","Back to announcements"),navy){newsDialog=null;d.dismiss();showNewsInbox()},12)
        d.setOnDismissListener{if(newsDialog===d){newsDialog=null;maybeShowFloating()}}
        displayNewsDialog(d)
        if(!item.read)acknowledgeNews(item,false){}
    }
    private fun maybeShowFloating(){
        val item=newsFeed?.floating?:return
        if(!newsForeground||account==null||isFinishing||isDestroyed||newsDialog?.isShowing==true||floatingDialog?.isShowing==true||item.id in floatingShown)return
        floatingShown.add(item.id)
        val (d,content)=newsCard(tr("📢 اطلاعیه دلتا","📢 Delta announcement"),tr("خبر جدید","New announcement"))
        floatingDialog=d
        newsBody(content,item.body)
        if(item.url.isNotBlank())put(content,button(item.buttonLabel.ifBlank{tr("باز کردن لینک","Open link")},blue){openNewsLink(item)},16)
        lateinit var never:Button
        never=button(tr("دیگر نمایش نده","Don't show again"),navy){
            never.isEnabled=false
            acknowledgeNews(item,true){ok->if(ok)d.dismiss() else never.isEnabled=true}
        }
        put(content,never,12)
        put(content,button(tr("بستن","Close"),panel){d.dismiss()},6)
        d.setOnDismissListener{if(floatingDialog===d){floatingDialog=null}}
        displayNewsDialog(d)
    }
    private fun updateUsage(){
        val current=account
        usageText.text = current?.let { "${gb(it.remainingBytes)} / ${gb(it.limitBytes)} GB" } ?: "—"
        usageDetail.text = current?.let { tr("مصرف: ${gb(it.usedBytes)} GB","Used: ${gb(it.usedBytes)} GB") } ?: tr("مصرف: نامشخص","Usage: unknown")
        usageText.setTextColor(if(usageStale) muted else fg)
        usageBar.visibility = if (current == null) View.GONE else View.VISIBLE
        usageBar.progress = current?.let { (it.remainingBytes.toDouble()/it.limitBytes*1000).toInt().coerceIn(0,1000) } ?: 0


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
            updateUsage()
            updateTraffic()
            val a = account
            if (a != null && a.remainingSeconds() == 0L && (running || transitioning)) LauncherManager.stopService(this@MainActivity)
            val now = android.os.SystemClock.elapsedRealtime()
            if(newsForeground&&account!=null&&now-newsLastPoll>=30000L)refreshNews()
            if(newsForeground&&account!=null&&drawer.visibility==View.VISIBLE)refreshAppUpdate()
            if (restoreRetryAt>0L && now>=restoreRetryAt && newsForeground && !busy && ready && stateKnown && account==null) maybeRestore(true)
            if (ready && stateKnown && account != null && !busy && !transitioning && pingRequest==null && usagePoll?.isActive!=true && now-lastPoll>=30000L) {
                lastPoll=now
                pollUsage()
            }
            timer.postDelayed(this,1000)
        }
    }
    private fun maybeRestore(force: Boolean = false) {
        if (!ready || !stateKnown || busy || account != null || (restoreAttempted && !force)) return
        restoreAttempted = true
        restoreRetryAt=0L
        val token = sessionStore.read()
        if (token == null) {
            starting = false
            render(if (running) tr("اتصال برقرار است","Connected") else tr("نام کاربری و رمز عبور را وارد کنید.","Enter username and password."))
            return
        }
        starting = true
        busy = true; render(tr("در حال ورود خودکار…","Signing in automatically…"))
        val wasRunning = running
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val a = accountApi.session(token, stableDeviceId())
                a to (wasRunning || tryImport(a))
            } }
            busy = false
            starting = false
            result.onSuccess { (a, imported) ->
                account=a; catalogPending=!imported; usageStale=false
                if (!a.canConnect && running) LauncherManager.stopService(this@MainActivity)
                render(if (!a.canConnect) tr("اشتراک فعال قابل اتصال نیست.","Subscription cannot connect.") else if(!imported) catalogRetryMessage() else tr("به دلتا خوش آمدید","Welcome to Delta"))
            }.onFailure {
                if (it is DeltaApiException && it.sessionRevoked) {
                    invalidateSession()
                } else {
                    // A valid saved login survives temporary panel/API/import errors.
                    starting=true; restoreRetryAt=SystemClock.elapsedRealtime()+15000L
                    render(); startupStatus.text=tr("در حال تلاش مجدد برای دریافت اشتراک…","Retrying subscription retrieval…")
                }
            }
        }
    }
    private fun catalogRetryMessage()=tr("دریافت سرورها موقتاً ناموفق است؛ دوباره تلاش کنید.","Server list temporarily unavailable. Try again.")
    private fun invalidateSession(){
        account?.let{DeltaRefreshCursor(it.token).clear()}
        account=null; sessionStore.clear(); catalogPending=false; restoreRetryAt=0L; usageStale=false
        cancelUsagePoll()
        if(running) LauncherManager.stopService(this@MainActivity) else clearCatalog()
        delays.clear()
        render(tr("نشست لغو شده یا منقضی شده است؛ دوباره وارد شوید.","Session revoked or expired. Sign in again."))
    }
    private fun tryImport(a: DeltaAccount):Boolean = synchronized(catalogLock) {
        // Do not replace a previously good catalog with a partial/empty active response.
        if(a.canConnect && (!a.configsReady || a.configs.isEmpty()))return@synchronized false
        runCatching {
            repo.refresh(if (a.canConnect) a.configs else emptyList())
            DeltaRefreshCursor(a.token).applied(a,true)
            true
        }.getOrDefault(false)
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
                clearCatalog()
                val a = accountApi.login(name, secret, deviceId(), (android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL).take(25), legacyDeviceId())
                try { sessionStore.save(a.token) } catch (e: Exception) { runCatching { accountApi.logout(a.token) }; throw e }
                a to tryImport(a)
            } }
            busy = false;manualLogin=false
            result.onSuccess { (a, imported) ->
                account=a; catalogPending=!imported; usageStale=false; delays.clear(); input.text.clear()
                render(if (!imported) catalogRetryMessage() else if (repo.servers.isEmpty()) tr("کانفیگ اختصاصی فعال در دسترس نیست.","No active dedicated server available.") else tr("به دلتا خوش آمدید","Welcome to Delta"))
            }
                .onFailure { account = null; render(when {
                    it is DeltaApiException && it.status==409 && it.reason=="device_limit" -> tr("ظرفیت دستگاه‌ها تکمیل است. از ربات دلتا، /buy ← سرویس‌های من ← دستگاه‌های متصل، فقط دستگاه قبلی را خارج کنید و دوباره وارد شوید.","Device limit reached. In the Delta bot, open /buy → My services → Devices, remove the old device, and sign in again.")
                    it is DeltaApiException && it.status==409 && it.reason=="dedicated_required" -> tr("حساب هنوز آماده اتصال نیست؛ با پشتیبانی تماس بگیرید.","Account not ready; contact support.")
                    it is DeltaApiException && it.status==409 -> tr("ظرفیت دستگاه‌ها تکمیل است یا حساب آماده نیست؛ دستگاه‌ها را از ربات بررسی کنید.","Device limit reached or account unavailable. Check devices in the bot.")
                    it is DeltaApiException && it.status==401 -> tr("نام کاربری یا رمز عبور معتبر نیست یا حساب غیرفعال است.","Invalid credentials or inactive account.")
                    it is DeltaApiException && it.status==429 -> tr("تعداد تلاش‌های ورود زیاد است؛ بعداً دوباره امتحان کنید.","Too many sign-in attempts; try again later.")
                    else -> tr("ورود یا دریافت اشتراک ناموفق بود؛ حساب و تنظیمات سرور را بررسی کنید.","Sign-in or subscription loading failed. Check account and server settings.")
                }) }
        }
    }
    private fun pollUsage() {
        val previous=account ?: return
        if (busy || transitioning || pingRequest!=null || usagePoll?.isActive==true) return
        val wasRunning=running
        val needsCatalog=catalogPending || !previous.canConnect || repo.servers.isEmpty()
        // Background I/O does not own the foreground busy flag.
        usagePoll=lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching {
                val current=accountApi.status(previous.token)
                currentCoroutineContext().ensureActive()
                if(!wasRunning&&current.canConnect&&(needsCatalog || DeltaRefreshCursor(previous.token).due(current))){
                    val loaded=runCatching { accountApi.session(previous.token, stableDeviceId()) }
                    val failure=loaded.exceptionOrNull()
                    if(failure is DeltaApiException && failure.sessionRevoked)throw failure
                    currentCoroutineContext().ensureActive()
                    val updated=loaded.getOrNull()
                    if(updated==null) current to false else {
                        val job=currentCoroutineContext()[Job]
                        val imported=synchronized(catalogLock){
                            job?.ensureActive()
                            tryImport(updated)
                        }
                        updated to imported
                    }
                }else current to null
            } }
            currentCoroutineContext().ensureActive()
            if(account?.token!=previous.token || busy || transitioning || running!=wasRunning || pingRequest!=null)return@launch
            result.onSuccess { (updated, imported) ->
                account=updated;usageStale=false
                if(imported!=null)catalogPending=!imported
                if (!updated.canConnect && running) LauncherManager.stopService(this@MainActivity)
                render(if (!updated.canConnect) tr("حجم یا اعتبار یا دسترسی اشتراک به پایان رسیده است.","Subscription data, validity or access has ended.") else if(imported==false) catalogRetryMessage() else null)
            }.onFailure {
                if (it is DeltaApiException && it.sessionRevoked) invalidateSession()
                else { usageStale=true; render() }
            }
        }
    }
    private fun validateAccount(connectAfter: Boolean,quickAfter:Boolean=false) {
        val previous = account ?: return
        if (busy || transitioning || pingRequest != null) return
        if (running) { if (!connectAfter && !quickAfter) pollUsage(); return }
        cancelUsagePoll()
        busy = true; render(tr("در حال بررسی اعتبار حساب…","Checking subscription…"))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val updated = accountApi.session(previous.token, stableDeviceId())
                updated to tryImport(updated)
            } }
            busy = false
            result.onSuccess { (updated, imported) ->
                account=updated;catalogPending=!imported;usageStale=false
                if(!imported){render(catalogRetryMessage());return@onSuccess}
                delays.clear()
                render(if (!updated.canConnect || repo.servers.isEmpty()) tr("کانفیگ اختصاصی فعال در دسترس نیست.","No active dedicated server available.") else tr("اعتبار حساب تأیید شد.","Subscription verified."))
                if(quickAfter && updated.canConnect && repo.servers.isNotEmpty())testServers(true)
                else if (connectAfter && updated.canConnect && repo.canConnect()) {
                    val request = VpnService.prepare(this@MainActivity)
                    if (request == null) startTunnel() else permission.launch(request)
                }
            }.onFailure {
                if (it is DeltaApiException && it.sessionRevoked) invalidateSession()
                else {catalogPending=true;usageStale=true;render(catalogRetryMessage())}
            }
        }
    }
    private fun signOut() {
        if (busy || running || transitioning || pingRequest != null) return
        cancelUsagePoll()
        val previous = account
        previous?.let{DeltaRefreshCursor(it.token).clear()}
        account = null; sessionStore.clear(); catalogPending=false; restoreRetryAt=0L; usageStale=false; clearCatalog(); delays.clear(); input.text.clear(); password.text.clear();hideDrawer();serverSection.visibility=View.GONE
        render(tr("از حساب خارج شدید.","Signed out."))
        if (previous != null) lifecycleScope.launch(Dispatchers.IO) { runCatching { accountApi.logout(previous.token) } }
    }
    private fun toggle() {
        if (busy || transitioning || pingRequest!=null) return
        cancelUsagePoll()
        if (running) {
            transitioning = true; render(tr("در حال قطع اتصال…","Disconnecting…"))
            LauncherManager.stopService(this); timer.postDelayed(timeout, 15000)
        } else if (account != null) {
            validateAccount(true)
        }
    }
    private fun startTunnel() {
        if (account?.canConnect != true || (account?.remainingSeconds() ?: 0) <= 0 || !repo.canConnect()) return
        cancelUsagePoll()
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        transitioning = true; render(tr("در حال برقراری اتصال…","Connecting…"))
        if (!LauncherManager.startServiceFromToggle(this, account?.deadlineElapsed ?: 0L, account?.token.orEmpty())) {
            transitioning = false; render(tr("شروع سرویس ناموفق بود.","VPN service could not start."))
        } else timer.postDelayed(timeout, 20000)
    }
}


/** Animated abstract mesh: drifting vertices, traveling light pulses and luminous links. */
private class DeltaMotionView(context:Context):View(context){
    var active=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val cols=6
    private val rows=10
    private val xs=FloatArray(cols*rows)
    private val ys=FloatArray(cols*rows)
    private val base=Color.rgb(11,15,34)
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        if(width<=0||height<=0)return
        canvas.drawColor(base)
        val enabled=android.os.Build.VERSION.SDK_INT<26||ValueAnimator.areAnimatorsEnabled()
        val t=if(enabled)android.os.SystemClock.elapsedRealtime()/1000.0 else 0.0
        val w=width.toFloat();val h=height.toFloat()
        val unit=minOf(w/cols,h/rows)
        for(i in 0..1){
            val phase=t*0.22+i*3.1
            val cx=w*(0.5f+0.28f*kotlin.math.sin(phase).toFloat())
            val cy=h*(0.35f+i*0.3f)
            paint.shader=RadialGradient(cx,cy,maxOf(w,h)*0.63f, intArrayOf(if(i==0)0x504239B8 else 0x403478F6,0x000B0F22),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,w,h,paint)
        }
        paint.shader=null
        for(r in 0 until rows)for(c in 0 until cols){
            val i=r*cols+c
            xs[i]=(c+0.5f+(if(r%2==0)0.12f else -0.12f))*w/cols+unit*0.22f*kotlin.math.sin(t*0.57+i*1.73).toFloat()
            ys[i]=(r+0.5f)*h/rows+unit*0.24f*kotlin.math.cos(t*0.43+i*2.19).toFloat()
        }
        fun edge(a:Int,b:Int){
            paint.color=0x465A70EF;paint.strokeWidth=resources.displayMetrics.density*0.8f
            canvas.drawLine(xs[a],ys[a],xs[b],ys[b],paint)
            if((a*7+b)%5==0){
                val f=((t*0.23+a*0.137)%1.0).toFloat()
                val x=xs[a]+(xs[b]-xs[a])*f;val y=ys[a]+(ys[b]-ys[a])*f
                paint.color=0xBB94B8FF.toInt();canvas.drawCircle(x,y,unit*0.025f,paint)
            }
        }
        for(r in 0 until rows)for(c in 0 until cols){
            val a=r*cols+c
            if(c+1<cols)edge(a,a+1)
            if(r+1<rows){edge(a,a+cols);if(c+1<cols)edge(a,a+cols+1)}
        }
        for(i in xs.indices){
            val pulse=(0.5+0.5*kotlin.math.sin(t*1.7+i*2.4)).toFloat()
            val radius=unit*(0.055f+0.10f*pulse)
            paint.shader=RadialGradient(xs[i],ys[i],radius,intArrayOf(0x997D94FF.toInt(),0x007D94FF),null,Shader.TileMode.CLAMP)
            canvas.drawCircle(xs[i],ys[i],radius,paint)
            paint.shader=null;paint.color=Color.argb((110+130*pulse).toInt(),161,185,255)
            canvas.drawCircle(xs[i],ys[i],unit*(0.018f+0.013f*pulse),paint)
        }
        if(active&&enabled&&isShown)postInvalidateDelayed(33)
    }
}
