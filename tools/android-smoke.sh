#!/usr/bin/env bash
# اختبار تشغيل الـ APK على المحاكي: يتثبت ويفتح وتظهر الصفحة بدون أخطاء
set -uo pipefail
APK="$(ls apk/*.apk | head -1)"
adb install -r "$APK" || exit 1
adb logcat -c || true
adb shell am start -W -n com.ahmedhassan.hesabaty/.MainActivity
sleep 25
adb logcat -d > logcat.txt || true
adb exec-out screencap -p > screen.png || true
PID="$(adb shell pidof com.ahmedhassan.hesabaty | tr -d '\r' || true)"
echo "pid=$PID"
grep -E "HesabatyWeb|HESABATY|CONSOLE|Uncaught|FATAL EXCEPTION|AndroidRuntime" logcat.txt | head -60 || true
[ -n "$PID" ] || { echo "::error::التطبيق مش شغال"; exit 1; }
grep -q "HESABATY_READY" logcat.txt || { echo "::error::صفحة التطبيق ما اتحملتش"; exit 1; }
if grep -q "Uncaught" logcat.txt; then echo "::error::خطأ JavaScript"; exit 1; fi
if grep -q "FATAL EXCEPTION" logcat.txt; then echo "::error::التطبيق وقع"; exit 1; fi
# زر الرجوع وإعادة الفتح
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -W -n com.ahmedhassan.hesabaty/.MainActivity; sleep 5
adb logcat -d | grep -q "FATAL EXCEPTION" && { echo "::error::التطبيق وقع بعد الرجوع"; exit 1; }
echo "✔ التطبيق اشتغل والصفحة اتحملت بدون أخطاء"
