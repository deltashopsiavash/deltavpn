#!/usr/bin/env bash
set -euo pipefail
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/Test.kt" <<'EOF'
import com.v2ray.ang.delta.SubscriptionPolicy
fun main() {
    check(SubscriptionPolicy.accepts("https://pmainp1.deltamarket2.ir:8000/sub/test-token"))
    for (s in listOf(
        "http://pmainp1.deltamarket2.ir:8000/sub/test",
        "https://pmainp1.deltamarket2.ir.evil.example:8000/sub/test",
        "https://pmainp1.deltamarket2.ir:8000@evil.example/sub/test",
        "https://pmainp1.deltamarket2.ir/sub/test",
        "https://pmainp1.deltamarket2.ir:8000/sub/../test",
        "https://pmainp1.deltamarket2.ir:8000/sub/test?url=evil",
        "vless://test")) check(!SubscriptionPolicy.accepts(s))
    println("Delta policy tests passed")
}
EOF
kotlinc delta/SubscriptionPolicy.kt "$tmp/Test.kt" -include-runtime -d "$tmp/test.jar"
java -jar "$tmp/test.jar"
