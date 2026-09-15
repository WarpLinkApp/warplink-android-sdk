#!/usr/bin/env bash
#
# Proves each conformance spec actually catches the defect it was written for.
#
# A green test says nothing on its own: it may be green because the code is
# correct, or because the test never touches the code. For every spec below this
# reintroduces the original defect, runs that spec's tests, and requires them to
# FAIL. It then restores the source and requires them to PASS.
#
# A spec that stays green with its defect reintroduced is a spec that would not
# have caught the bug, and this script exits non-zero on it.
#
# Usage:  warplink-android-sdk/scripts/verify-spec-red-green.sh [WL-S17 ...]
# Run from anywhere. Needs JAVA_HOME and ANDROID_HOME, same as any Gradle run.

set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
SDK_ROOT="$PWD"
MAIN="$SDK_ROOT/sdk/src/main/kotlin/app/warplink"

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
export JAVA_HOME ANDROID_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# spec | file under sdk/src/main/kotlin/app/warplink | test filter | literal to replace | replacement
#
# Each injection is the SMALLEST edit that restores the original defect, so a
# red result names that defect and not a compile error.
SPECS=(
"WL-S17|internal/JsonNullSafety.kt|app.warplink.internal.ApiClientJsonNullTest|    if (isNull(key)) return null
    return optString(key).ifEmpty { null }|    return optString(key).ifEmpty { null }"
"WL-S18|internal/InstallReferrerReader.kt|app.warplink.internal.InstallReferrerDiagnosticsTest|        logger?.log(\"\$LOG_PREFIX unavailable: \$cause\")|        // defect reintroduced: every cause is silent"
"WL-S19|WarpLink.kt|app.warplink.WarpLinkAutoHandlingTest|            if (automaticDeepLinksEnabled) autoHandler else null|            autoHandler"
"WL-S20|internal/FingerprintCollector.kt|app.warplink.internal.DegradedSignalsStillMatchTest.WL-S20 an unresolvable zone still sends the attribution request|    private fun timezoneName(): String = try {
        ZoneId.systemDefault().id
    } catch (_: Exception) {
        \"\"
    }|    private fun timezoneName(): String = ZoneId.systemDefault().id"
"WL-S21|internal/FingerprintCollector.kt|app.warplink.internal.DegradedSignalsStillMatchTest.WL-S21 a degraded check does not hand the host a raw JDK exception|    private fun timezoneName(): String = try {
        ZoneId.systemDefault().id
    } catch (_: Exception) {
        \"\"
    }|    private fun timezoneName(): String = ZoneId.systemDefault().id"
"WL-S22|internal/AutoLinkHandler.kt|app.warplink.AutoLinkHandlerClockTest|        if (key == lastUri && (at - lastAt) in 0 until DEDUP_WINDOW_MS) {|        if (key == lastUri && (at - lastAt) < DEDUP_WINDOW_MS) {"
"WL-S23|internal/AutoLinkHandler.kt|app.warplink.AutoLinkHandlerRegistrationTest|        synchronized(lock) {
            if (retired) {
                logger?.log(\"Superseded by a newer configure; not registering cold start\")
                return
            }
            if (callbacks != null) return
            callbacks = created
            app.registerActivityLifecycleCallbacks(created)
        }|        synchronized(lock) { callbacks = created }
        app.registerActivityLifecycleCallbacks(created)"
"WL-S23|internal/AutoLinkHandler.kt|app.warplink.ReentrantConfigureTest|        synchronized(lock) {
            if (retired) {
                logger?.log(\"Superseded by a newer configure; not registering cold start\")
                return
            }
            if (callbacks != null) return
            callbacks = created
            app.registerActivityLifecycleCallbacks(created)
        }|        synchronized(lock) { callbacks = created }
        app.registerActivityLifecycleCallbacks(created)"
# The three halves of WL-S27: how many attempts, which failures earn one, and
# whether the FIRST attempt is bounded at all.
#
# `maxAttempts` is armed here because BoundedRetry.nextWait now reads it. It used
# to appear only in a doc comment, with the real bound sitting unnamed in
# `delaysMs.size`, so this row was a no-op that claimed WL-S27 was protected when
# it was not. Keep it load-bearing, or move this row onto whatever bounds the
# attempts instead.
"WL-S27|internal/RetryPolicy.kt|app.warplink.internal.ResolveRetryTest|    val maxAttempts: Int = 3,|    val maxAttempts: Int = 99,"
"WL-S27|internal/RetryPolicy.kt|app.warplink.internal.ResolveRetryTest|    is WarpLinkError.ServerError -> error.statusCode >= 500|    is WarpLinkError.ServerError -> true"
# 30_000 is READ_TIMEOUT_MS, so this restores exactly the behaviour of a first
# attempt with no bound of its own: the hanging server holds attempt 1 for the
# whole of ApiClient's own read timeout, which the test sees as an elapsed time
# far past the four second bound it asserts. Slow on purpose.
"WL-S27|internal/RetryPolicy.kt|app.warplink.internal.ResolveRetryTest.WL-S27 an attempt that is accepted and never answered is bounded by its own timeout|    val firstAttemptTimeoutMs: Int = 4_000,|    val firstAttemptTimeoutMs: Int = 30_000,"
# The bound has to reach a RUNNING attempt, not only an idle one. Disarming the
# watchdog leaves connectTimeout and readTimeout, which a server answering one
# byte at a time resets for ever, so the attempt never ends and no retry runs.
"WL-S27|internal/BoundedAttempt.kt|app.warplink.internal.DrippingAttemptTest|        }.also { timer.postDelayed(it, ms.toLong()) }|        }.also { timer.postDelayed(it, ms.toLong() * 1_000) }"
# And its cancellation has to read as a timeout. A disconnect mid-body surfaces
# as a JSONException, which isRetryable maps to DecodingError and refuses, so
# without the rename the bound ENDS the run instead of buying the retry.
"WL-S27|internal/BoundedAttempt.kt|app.warplink.internal.DrippingAttemptTest|        return Result.failure(
            WarpLinkError.NetworkError(
                SocketTimeoutException(\"attempt abandoned after \${timeoutMs}ms\")
            )
        )|        return result"
# A re-tap of the SAME link past the dedupe window is a NEW claim wearing the
# same URI. Identifying a claim by its URI let the overtaken tap drop the newer
# tap's claim, which let a duplicate through and billed a second click.
"WL-S27|internal/AutoLinkHandler.kt|app.warplink.RetapPhantomClickTest|            if (token == claimToken) {|            if (key == lastUri) {"
)

WANTED=("$@")
wanted() {
  [ ${#WANTED[@]} -eq 0 ] && return 0
  for w in "${WANTED[@]}"; do [ "$w" = "$1" ] && return 0; done
  return 1
}

run_tests() {
  ./gradlew testDebugUnitTest --no-daemon --tests "$1" >/tmp/wl-spec-run.log 2>&1
}

FAILURES=0
printf '%-9s %-46s %-8s %-8s %s\n' SPEC TESTS DEFECT FIXED RESULT
printf -- '---------------------------------------------------------------------------------------\n'

for row in "${SPECS[@]}"; do
  IFS='|' read -r spec file filter find replace <<<"$row"
  wanted "$spec" || continue

  target="$MAIN/$file"
  if [ ! -f "$target" ]; then
    printf '%-9s %-46s %-8s %-8s %s\n' "$spec" "$filter" "-" "-" "MISSING SOURCE: $file"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  backup="$(mktemp)"
  cp "$target" "$backup"

  # Reintroduce the defect.
  if ! FIND="$find" REPLACE="$replace" TARGET="$target" python3 - <<'PY'
import os, sys
t, f, r = os.environ["TARGET"], os.environ["FIND"], os.environ["REPLACE"]
s = open(t).read()
if f not in s:
    sys.exit("injection literal not found; the source moved and this script is stale")
open(t, "w").write(s.replace(f, r, 1))
PY
  then
    printf '%-9s %-46s %-8s %-8s %s\n' "$spec" "$filter" "-" "-" "STALE INJECTION"
    cp "$backup" "$target"; rm -f "$backup"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  run_tests "$filter"; red_rc=$?

  # Restore before judging, so a failure here never leaves the tree dirty.
  cp "$backup" "$target"; rm -f "$backup"

  run_tests "$filter"; green_rc=$?

  defect=$([ $red_rc -ne 0 ] && echo "red" || echo "GREEN")
  fixed=$([ $green_rc -eq 0 ] && echo "green" || echo "RED")

  if [ $red_rc -ne 0 ] && [ $green_rc -eq 0 ]; then
    result="ok"
  elif [ $red_rc -eq 0 ]; then
    result="SPEC DOES NOT CATCH ITS DEFECT"
    FAILURES=$((FAILURES + 1))
  else
    result="SPEC FAILS ON FIXED CODE"
    FAILURES=$((FAILURES + 1))
  fi
  printf '%-9s %-46s %-8s %-8s %s\n' "$spec" "$filter" "$defect" "$fixed" "$result"
done

echo
if [ $FAILURES -ne 0 ]; then
  echo "$FAILURES spec(s) did not prove themselves. Last Gradle output: /tmp/wl-spec-run.log"
  exit 1
fi
echo "Every spec goes red on its own defect and green on the fix."
