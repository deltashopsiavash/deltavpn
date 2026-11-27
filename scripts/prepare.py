from pathlib import Path
import shutil, re, sys, os, json
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

root = Path(sys.argv[1]).resolve()
overlay = Path(__file__).resolve().parents[1] / "delta"
app = root / "V2rayNG/app"
src = app / "src/main/java/com/v2ray/ang"
(src / "delta").mkdir(exist_ok=True)
for name in ("SubscriptionPolicy.kt", "DeltaSubscriptions.kt", "DeltaAccountApi.kt", "DeltaSessionStore.kt", "DeltaRefreshCursor.kt"):
    shutil.copy2(overlay / name, src / "delta" / name)
shutil.copy2(overlay / "MainActivity.kt", src / "ui/main/MainActivity.kt")
endpoint = os.environ.get("DELTA_APP_API_URL", "").strip() or "https://bot.deltajanebishop.ir/deltabotvps/delta_app_api.php"
if endpoint:
    u = urlsplit(endpoint)
    assert u.scheme == "https" and u.hostname and not u.username and not u.password and not u.query and not u.fragment and u.path.endswith("/delta_app_api.php")
    assert "$" not in endpoint and all(ord(c) >= 32 for c in endpoint)
(src / "delta" / "DeltaEndpoint.kt").write_text("package com.v2ray.ang.delta\nobject DeltaEndpoint { const val URL = " + json.dumps(endpoint) + " }\n")
if not endpoint:
    print("WARNING: DELTA_APP_API_URL is unset; login disabled in this preview.")

# Reuse the same upstream parsers as v2rayNG without enabling nested URL imports.
manager = src / "handler/AngConfigManager.kt"
code = manager.read_text()
anchor = "object AngConfigManager {"
assert anchor in code
code = code.replace(anchor, anchor + """
    fun importTrustedDeltaPayload(payload: String, subid: String, custom: Boolean, append: Boolean = false): Int {
        return if (custom) parseCustomConfigServer(payload, subid, append)
        else parseBatchConfig(payload, subid, append)
    }
""", 1)
manager.write_text(code)
# Acknowledge ordered state registration only when the daemon actually handles it.
service_manager = src / "core/CoreServiceManager.kt"
state_code = service_manager.read_text()
state_anchor = "AppConfig.MSG_REGISTER_CLIENT -> {"
assert state_code.count(state_anchor) == 1
state_code = state_code.replace(state_anchor, state_anchor + "\n                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK", 1)
assert "private fun reloadCore(): Boolean {" in state_code
state_code = state_code.replace("private fun reloadCore(): Boolean {", "fun reloadDeltaSubscription(): Boolean = reloadCore()\n\n    private fun reloadCore(): Boolean {", 1)
service_manager.write_text(state_code)

# Bind each VPN start to the validated monotonic deadline, including in the daemon process.
launcher = src / "core/LauncherManager.kt"
launch_code = launcher.read_text()
for old, new in [
    ("fun startServiceFromToggle(context: Context): Boolean", "fun startServiceFromToggle(context: Context, deltaDeadline: Long = 0L, deltaToken: String = \"\"): Boolean"),
    ("startContextService(context)", "startContextService(context, deltaDeadline, deltaToken)"),
    ("private fun startContextService(context: Context)", "private fun startContextService(context: Context, deltaDeadline: Long = 0L, deltaToken: String = \"\")"),
    ("ContextCompat.startForegroundService(context, intent)", 'ContextCompat.startForegroundService(context, intent.putExtra("deltaDeadline", deltaDeadline).putExtra("deltaToken", deltaToken))'),
]:
    assert old in launch_code, old
    launch_code = launch_code.replace(old,new,1)
launcher.write_text(launch_code)
vpn = src / "service/CoreVpnService.kt"
vpn_code = vpn.read_text()
for old,new in [
    ("private var isRunning = false", """private var isRunning = false
    private val deltaHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var deltaDeadline = 0L
    private var deltaToken = ""
    private var deltaGeneration = 0L
    private val deltaExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val deltaApi = com.v2ray.ang.delta.DeltaAccountApi()
    private val deltaRefreshCheck = object : Runnable {
        override fun run() {
            if (!isRunning || deltaToken.isEmpty()) return
            val generation=deltaGeneration
            val token=deltaToken
            deltaExecutor.execute {
                val result=runCatching {
                    val status=deltaApi.status(token)
                    val cursor=com.v2ray.ang.delta.DeltaRefreshCursor(token)
                    if(status.canConnect && cursor.due(status)) deltaApi.session(token) else status
                }
                deltaHandler.post {
                    if(!isRunning||generation!=deltaGeneration)return@post
                    result.onSuccess { a ->
                        if(!a.canConnect || a.remainingSeconds()<=0){stopAllService();return@onSuccess}
                        // Renewed accounts may extend the already-running session deadline.
                        deltaDeadline=a.deadlineElapsed
                        val cursor=com.v2ray.ang.delta.DeltaRefreshCursor(token)
                        if(a.configs.isNotEmpty() && cursor.due(a)){
                            runCatching {
                                val repo=com.v2ray.ang.delta.DeltaSubscriptions(this@CoreVpnService)
                                repo.refresh(a.configs,true)
                                val restart=cursor.reconnect(a)
                                if(restart){
                                    repo.activateLatest()
                                    if(!CoreServiceManager.reloadDeltaSubscription()){stopAllService();return@onSuccess}
                                }
                                cursor.applied(a,restart)
                            }
                        }
                    }.onFailure {
                        if(it is com.v2ray.ang.delta.DeltaApiException && it.status in listOf(401,409))stopAllService()
                    }
                    if(isRunning&&generation==deltaGeneration)deltaHandler.postDelayed(this,30000L)
                }
            }
        }
    }
    private val deltaExpiryCheck = object : Runnable {
        override fun run() {
            val left = deltaDeadline - android.os.SystemClock.elapsedRealtime()
            if (left <= 0L) stopAllService() else deltaHandler.postDelayed(this, minOf(1000L,left))
        }
    }"""),
    ("super.onDestroy()", "deltaGeneration++\n        deltaHandler.removeCallbacksAndMessages(null)\n        deltaExecutor.shutdownNow()\n        super.onDestroy()"),
    ("NotificationManager.ensureForeground()", """NotificationManager.ensureForeground()
        val deadline = intent?.getLongExtra("deltaDeadline",0L) ?: 0L
        if (deadline <= android.os.SystemClock.elapsedRealtime()) {
            stopSelf()
            return START_NOT_STICKY
        }
        deltaDeadline = deadline
        deltaToken = intent?.getStringExtra("deltaToken").orEmpty()
        if (!deltaToken.matches(Regex("[a-f0-9]{64}"))) {
            stopSelf()
            return START_NOT_STICKY
        }
        deltaGeneration++"""),
    ("        startService()\n        return START_STICKY", "        startService()\n        deltaHandler.removeCallbacksAndMessages(null)\n        deltaHandler.post(deltaExpiryCheck)\n        deltaHandler.postDelayed(deltaRefreshCheck,30000L)\n        return START_NOT_STICKY"),
    ("private fun stopAllService(isForced: Boolean = true) {", "private fun stopAllService(isForced: Boolean = true) {\n        deltaGeneration++\n        deltaHandler.removeCallbacksAndMessages(null)"),
]:
    assert old in vpn_code, old
    vpn_code=vpn_code.replace(old,new,1)
vpn.write_text(vpn_code)



gradle = app / "build.gradle.kts"
s = gradle.read_text()
for old, new in [
    ('applicationId = "com.v2ray.ang"', 'applicationId = "com.delta.vpn"'),
    ('versionCode = 755', 'versionCode = 20'),
    ('versionName = "2.3.15"', 'versionName = "1.7.3"'),
    ('applicationIdSuffix = ".fdroid"', ''),
]:
    assert old in s, old
    s = s.replace(old, new, 1)
gradle.write_text(s)

# Only the dedicated Delta screen is reachable; no generic imports, edits, backup, widgets or Tasker.
manifest = app / "src/main/AndroidManifest.xml"
a = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", a[1:-1])
ET.register_namespace("tools", "http://schemas.android.com/tools")
tree = ET.parse(manifest)
application = tree.getroot().find("application")
assert application is not None
application.set(a+"allowBackup", "false")
application.set(a+"label", "Delta VPN")
application.set(a+"icon", "@drawable/delta_icon")
application.set(a+"roundIcon", "@drawable/delta_icon")
for node in list(application):
    name = node.get(a+"name", "")
    if node.tag == "activity":
        if name != ".ui.main.MainActivity":
            application.remove(node)
        else:
            node.set(a+"label", "Delta VPN")
            node.set(a+"exported", "true")
            for child in list(node):
                if child.tag in ("intent-filter", "meta-data"):
                    node.remove(child)
            f = ET.SubElement(node,"intent-filter")
            ET.SubElement(f,"action",{a+"name":"android.intent.action.MAIN"})
            ET.SubElement(f,"category",{a+"name":"android.intent.category.LAUNCHER"})
    elif node.tag in ("activity-alias", "receiver"):
        application.remove(node)
    elif node.tag == "service" and name in (".service.QSTileService", ".service.CoreRootService", ".service.CoreProxyOnlyService"):
        application.remove(node)
    elif node.tag == "service" and name == ".service.CoreVpnService":
        for child in node.findall("meta-data"):
            if child.get(a+"name") == "android.net.VpnService.SUPPORTS_ALWAYS_ON":
                child.set(a+"value","false")
assert any(n.get(a+"name")==".ui.main.MainActivity" for n in application)
tree.write(manifest, encoding="utf-8", xml_declaration=True)

# The account API must use the system CA store, even though the upstream VPN
# application permits user-installed CAs for other networking features.
network_config = app / "src/main/res/xml/network_security_config.xml"
network_tree = ET.parse(network_config)
network_root = network_tree.getroot()
assert network_root.tag == "network-security-config"
api_config = ET.SubElement(network_root, "domain-config", {"cleartextTrafficPermitted": "false"})
ET.SubElement(api_config, "domain", {"includeSubdomains": "false"}).text = urlsplit(endpoint).hostname
anchors = ET.SubElement(api_config, "trust-anchors")
ET.SubElement(anchors, "certificates", {"src": "system"})
network_tree.write(network_config, encoding="utf-8", xml_declaration=True)

# Keep credentials out of Android cloud/device-transfer backups.
for node in (app / "src/main/res").glob("values*/strings.xml"):
    value = node.read_text()
    value = re.sub(r'(<string name="app_name"[^>]*>).*?(</string>)', r'\g<1>Delta VPN\g<2>', value)
    node.write_text(value)
icon = app / "src/main/res/drawable/delta_icon.xml"
icon.write_text((overlay / "delta_icon.xml").read_text())
(icon.parent / "delta_power.xml").write_text((overlay / "delta_power.xml").read_text())
print("Delta overlay applied.")
