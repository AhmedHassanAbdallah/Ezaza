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
# رسائل صفحة التطبيق فقط (مش عمليات النظام)
if grep "HesabatyWeb" logcat.txt | grep -q "Uncaught"; then echo "::error::خطأ JavaScript في الصفحة"; exit 1; fi
if grep -A4 "FATAL EXCEPTION" logcat.txt | grep -q "com.ahmedhassan.hesabaty"; then echo "::error::التطبيق وقع"; exit 1; fi
# زر الرجوع وإعادة الفتح
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -W -n com.ahmedhassan.hesabaty/.MainActivity; sleep 5
adb logcat -d | grep -A4 "FATAL EXCEPTION" | grep -q "com.ahmedhassan.hesabaty" && { echo "::error::التطبيق وقع بعد الرجوع"; exit 1; }
echo "✔ التطبيق اشتغل والصفحة اتحملت بدون أخطاء"
