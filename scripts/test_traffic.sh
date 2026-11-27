#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/Test.kt" <<'EOF'
import com.v2ray.ang.delta.DeltaTraffic
import com.v2ray.ang.delta.DeltaTrafficWindow

fun main() {
    check(DeltaTraffic.format(0) == "0 B/s")
    check(DeltaTraffic.format(-12) == "0 B/s")
    check(DeltaTraffic.format(532) == "532 B/s")
    check(DeltaTraffic.format(1024) == "1.0 KB/s")
    check(DeltaTraffic.format(13L * 1024 * 1024) == "13.0 MB/s")
    check(DeltaTraffic.rate(8192, 2000) == 4096L)
    check(DeltaTraffic.rate(0, 1000) == 0L)
    check(DeltaTraffic.rate(-1, 1000) == 0L)
    check(DeltaTraffic.rate(8192, 0) == 0L)
    check(DeltaTraffic.add(Long.MAX_VALUE, 1) == Long.MAX_VALUE)
    check(DeltaTraffic.add(10, -5) == 10L)

    val w = DeltaTrafficWindow()
    check(w.interval(1000, 1) == null)
    check(w.interval(2000, 1) == 1000L)
    check(w.interval(3400, 1) == 1400L)
    check(w.interval(4400, 2) == null) // core reload: discard old counter
    check(w.interval(5400, 2) == 1000L)
    check(w.interval(12000, 2) == null) // stale gap
    check(w.interval(13000, -1) == null) // disconnected
    check(w.interval(14000, 3) == null)
    check(w.interval(15000, 3) == 1000L)
    w.reset()
    check(w.interval(16000, 3) == null)

    val sample = longArrayOf(5000, 200, 100)
    check(DeltaTraffic.fresh(sample, 6000, 4900))
    check(!DeltaTraffic.fresh(sample, 8000, 4900))
    check(!DeltaTraffic.fresh(sample, 6000, 5100))
    check(!DeltaTraffic.fresh(sample, 4999, 0))
    check(!DeltaTraffic.fresh(longArrayOf(1, -1, 0), 1, 0))
    check(!DeltaTraffic.fresh(longArrayOf(1, 2), 2, 0))
    check(!DeltaTraffic.fresh(null, 2, 0))
    println("DELTA_TRAFFIC_TESTS_PASSED")
}
EOF
kotlinc delta/DeltaTraffic.kt "$tmp/Test.kt" -include-runtime -d "$tmp/traffic.jar"
java -jar "$tmp/traffic.jar"
