package com.v2ray.ang.ui.main

import android.content.BroadcastReceiver
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
    private lateinit var connect: Button
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
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                    running = true; transitioning = false; timer.removeCallbacks(timeout)
                    render("تونل VPN فعال است")
                }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    running = false; transitioning = false; timer.removeCallbacks(timeout)
                    render("قطع اتصال")
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    running = false; transitioning = false; timer.removeCallbacks(timeout)
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
        window.statusBarColor = Color.parseColor("#0C1525")
        window.navigationBarColor = Color.parseColor("#0C1525")
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0C1525")) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(26), dp(22), dp(24))
        }
        scroll.addView(root); setContentView(scroll)
        label("DELTA VPN", 30, fg, true).apply { gravity = Gravity.CENTER; add(this) }
        label("اختصاصی مشتریان دلتا  •  نسخه آزمایشی ۱.۰.۱", 13, muted).apply {
            gravity = Gravity.CENTER; add(this, 8)
        }
        status = label("در حال آماده‌سازی موتور اتصال…", 16, fg).apply {
            gravity = Gravity.CENTER; background = shape(panel); setPadding(dp(12),dp(20),dp(12),dp(20)); add(this, 26)
        }
        connect = button("اتصال") { toggle() }; add(connect, 14)
        add(label("اشتراک دلتا", 19, fg, true), 26)
        input = EditText(this).apply {
            hint = "لینک ساب دلتا را وارد کنید"; setHintTextColor(muted); setTextColor(fg)
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textDirection = View.TEXT_DIRECTION_LTR; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(14),dp(14),dp(14),dp(14)); background = shape(panel)
            // Never display the stored bearer token on app startup.
        }
        add(input, 10)
        importButton = button("دریافت اشتراک") { load(input.text.toString()) }; add(importButton, 8)
        refreshButton = button("به‌روزرسانی اشتراک", panel) { repo.url?.let { load(it) } }; add(refreshButton, 8)
        clearButton = button("حذف اشتراک", panel) {
            android.app.AlertDialog.Builder(this).setMessage("اشتراک و سرورهای ذخیره‌شده حذف شوند؟")
                .setPositiveButton("حذف") { _, _ -> repo.clear(); input.text.clear(); render("اشتراک حذف شد.") }
                .setNegativeButton("انصراف", null).show()
        }; add(clearButton, 8)
        add(label("سرورهای اشتراک", 19, fg, true), 26)
        servers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; add(servers, 8)
        add(label("موتور متن‌باز Xray / v2rayNG · GPL-3.0", 12, muted), 28)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), ContextCompat.RECEIVER_NOT_EXPORTED)
        render("در حال آماده‌سازی موتور اتصال…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { SettingsManager.initAssets(this@MainActivity, assets) }
                ready = true; render("قطع اتصال"); queryState()
            } catch (_: Exception) { render("آماده‌سازی موتور اتصال ناموفق بود.") }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 12)
        }
    }

    override fun onResume() { super.onResume(); if (ready) queryState() }
    override fun onDestroy() {
        timer.removeCallbacksAndMessages(null)
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_UNREGISTER_CLIENT, "")
        unregisterReceiver(receiver); super.onDestroy()
    }
    private fun queryState() {
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(15).toFloat() }
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
        message?.let { status.text = it }
        connect.text = if (running) "قطع اتصال" else "اتصال"
        connect.isEnabled = ready && !busy && !transitioning && (running || repo.canConnect())
        val editable = ready && !busy && !transitioning && !running
        importButton.isEnabled = editable; input.isEnabled = editable
        refreshButton.isEnabled = editable && repo.url != null
        clearButton.isEnabled = editable && repo.url != null
        servers.removeAllViews()
        val selected = MmkvManager.getSelectServer()
        if (repo.servers.isEmpty()) servers.addView(label("هنوز اشتراکی وارد نشده است.", 14, muted))
        repo.servers.forEach { id ->
            val profile = MmkvManager.decodeServerConfig(id) ?: return@forEach
            val title = profile.remarks.ifBlank { profile.configType.name }
            val b = button((if (selected == id) "●  " else "○  ") + title + "  ·  " + profile.configType.name, if (selected == id) blue else panel) {
                MmkvManager.setSelectServer(id); render("سرور انتخاب شد.")
            }
            b.isEnabled = editable
            servers.addView(b, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })
        }
    }
    private fun load(value: String) {
        if (busy || running || transitioning) return
        busy = true; render("در حال دریافت اشتراک…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.refresh(value) } }
            busy = false
            result.onSuccess { input.text.clear(); render("$it سرور دریافت شد.") }
                .onFailure { e ->
                    // Only show controlled errors; network exceptions can contain the secret URL.
                    render(if (e is IllegalArgumentException || e is IllegalStateException)
                        e.message?.takeIf { it.any { ch -> ch in '\u0600'..'\u06ff' } } ?: "دریافت اشتراک ناموفق بود."
                    else "ارتباط با پنل برقرار نشد؛ اینترنت و لینک را بررسی کنید.")
                }
        }
    }
    private fun toggle() {
        if (running) {
            transitioning = true; render("در حال قطع اتصال…")
            LauncherManager.stopService(this); timer.postDelayed(timeout, 15000)
        } else if (repo.canConnect()) {
            val request = VpnService.prepare(this)
            if (request == null) startTunnel() else permission.launch(request)
        }
    }
    private fun startTunnel() {
        if (!repo.canConnect()) return
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        transitioning = true; render("در حال اتصال…")
        if (!LauncherManager.startServiceFromToggle(this)) {
            transitioning = false; render("شروع سرویس ناموفق بود.")
        } else timer.postDelayed(timeout, 20000)
    }
}
