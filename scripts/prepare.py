from pathlib import Path
import shutil, re, sys, os, json
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

root = Path(sys.argv[1]).resolve()
overlay = Path(__file__).resolve().parents[1] / "delta"
app = root / "V2rayNG/app"
src = app / "src/main/java/com/v2ray/ang"
(src / "delta").mkdir(exist_ok=True)
for name in ("SubscriptionPolicy.kt", "DeltaSubscriptions.kt", "DeltaAccountApi.kt"):
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
service_manager.write_text(state_code)


gradle = app / "build.gradle.kts"
s = gradle.read_text()
for old, new in [
    ('applicationId = "com.v2ray.ang"', 'applicationId = "com.delta.vpn"'),
    ('versionCode = 755', 'versionCode = 8'),
    ('versionName = "2.3.15"', 'versionName = "1.3.2"'),
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

# Keep credentials out of Android cloud/device-transfer backups.
for node in (app / "src/main/res").glob("values*/strings.xml"):
    value = node.read_text()
    value = re.sub(r'(<string name="app_name"[^>]*>).*?(</string>)', r'\g<1>Delta VPN\g<2>', value)
    node.write_text(value)
icon = app / "src/main/res/drawable/delta_icon.xml"
icon.write_text((overlay / "delta_icon.xml").read_text())
(icon.parent / "delta_power.xml").write_text((overlay / "delta_power.xml").read_text())
print("Delta overlay applied.")
