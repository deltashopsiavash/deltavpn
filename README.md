# Delta VPN 1.3.0 — Android preview

نسخه اولیه اختصاصی دلتا. رابط فارسی، وارد کردن یک لینک ساب، دریافت کانفیگ‌های استاندارد VLESS/VMess/Trojan/SS، انتخاب سرور، اتصال و قطع اتصال از طریق Android VpnService و Xray.

- Only HTTPS on the exact allowed host and port, with /sub/token path. No redirects, arbitrary config input or nested subscription imports.
- No customer subscription token is committed; the user's real link has not been accessed.
- One subscription at a time; manual refresh while disconnected. Text/Base64 lists, Xray JSON and WireGuard config from the approved subscription origin. Other response formats, automatic refresh, account login, bot integration, and server-side ownership proof are deferred.
- The client allowlist is a product restriction, not a tamper-proof server-side authentication mechanism. Local Android private storage is not encrypted against root; do not use real credentials in logs. No telemetry in Delta overlay.
- UI uses pinned upstream v2rayNG 2.3.15 (commit 57907cf354d9769200ed4717b4430d605abe0952). Source overlay under delta/, reproducible preparation in scripts/prepare.py. GPL-3.0 upstream license applies to derivative distribution; preserve upstream license, notices and corresponding source. See https://github.com/2dust/v2rayNG and https://github.com/2dust/AndroidLibXrayLite.
- Package com.delta.vpn. Debug build for testing only; use a stable privately held release signing key before distributing. A CI debug key can change between runs; test installs may require uninstalling old builds.
- APK build: GitHub Releases → DeltaVPN-1.3.0-arm64.apk (source archive separate). Not a release until compiled and device-tested.
- Future architecture: replace DeltaSubscriptions with authenticated backend; add login/refresh token flow without changing upstream VPN lifecycle. Keep admin credentials on server only.

## Acceptance tests (device)
1. Reject external hosts, HTTP, single configs, redirects and malformed links.
2. Import a valid Delta sub; compare available nodes against v2rayNG.
3. Grant VPN permission; select node, connect, verify an HTTPS request and public IP, disconnect.
4. Reject permission; verify no active connection shown.
5. Reopen while connected, check UI state. Refresh with invalid link, verify existing subscription remains usable.
6. Test Wi-Fi/mobile data and airplane-mode recovery. Validate DNS/IPv6 behavior. Do not claim kill switch or fail-closed behavior until verified.

## 1.0.1
Use upstream share-link and Xray custom JSON parsers for trusted panel responses, including Base64 variants. HTML and unsupported JSON have distinct errors. Do not log or commit real subscription tokens. ARM64 APK is published separately in Releases.

## 1.1.0
Two separate screens matching the Delta reference: logo and paste/import on onboarding, dashboard with real subscription quota from Subscription-Userinfo, remaining days, circular VPN control, selected server and upstream real-delay testing. Unknown data displays a dash or an unavailable message. Manual refresh while disconnected updates account data. Buy/login integration is deferred; no unknown purchase URL is invented. Device visual and connection acceptance tests still required.

## 1.1.1
Delta brand symbol replaces the placeholder launcher and onboarding icon. The VPN power control uses a vector drawable rather than a font glyph. No VPN or subscription logic changes.

## 1.2.0 owner-only account preview
Username/password login replaces direct subscription import. The confirmed production API URL is `https://bot.deltajanebishop.ir/deltabotvps/delta_app_api.php`. GitHub Actions repository variable `DELTA_APP_API_URL` can override it for a separate trusted deployment. The endpoint is public configuration, not a secret. Accounts and stock are managed on deltashopsiavash/deltabotvps (repository `deltabotvps`). Session lives in memory only; login again after restarting the app. Connect and refresh recheck session and upstream quota/expiry. Each upstream subscription must be provisioned independently with its real quota and expiry. No public purchase, server-side provisioning, running-session revocation, or production signing yet. Do not distribute before device and backend tests.

## 1.3.0 shared catalog preview
The mother bot fetches and extracts manually entered subscription URLs; every active account receives the same deduplicated share-link catalog. Account login and expiry remain server-checked. Plan GB is nominal only; real per-account traffic quota and revocation of already-issued shared credentials cannot be enforced without distinct upstream identities or a metering gateway. Raw source URLs are not sent to the app. Refresh after catalog changes while disconnected. No public sales. This requires the updated backend; old single-sub API clients are incompatible. Existing private stock is not automatically shared.

## 1.3.1
Shared catalog accepts complete Xray JSON profiles and raw WireGuard configurations as well as share links. Mixed formats append to a single catalog, using the pinned upstream parsers. Backend 1.3.1 reports safe extraction errors; no raw source response or credentials are displayed. Test on a real device before public release.

## 1.3.2
Login controls no longer wait indefinitely for a stopped VPN daemon. Ordered registration acknowledges a running daemon, and a missing receiver resolves to disconnected. Inputs can be edited while initializing; login checks service state before importing. Verify cold launch and login on device. The backend plan picker generates demo credentials automatically.
