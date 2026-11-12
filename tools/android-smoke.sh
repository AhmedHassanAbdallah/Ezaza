#!/usr/bin/env bash
# اختبار تشغيل الـ APK على المحاكي: يتثبت ويفتح وتظهر الصفحة بدون أخطاء
set -uo pipefail
APK="$(ls apk/*.apk | head -1)"
adb install -r "$APK" || exit 1
adb logcat -c || true
adb shell am start -W -n com.ahmedhassan.hesabaty/.MainActivity
# انتظار تحميل الصفحة (حتى 90 ثانية — أول تشغيل للمحاكي ممكن يكون بطيء)
for i in $(seq 1 45); do
  sleep 2
  adb logcat -d > logcat.txt || true
  grep -q "HESABATY_READY" logcat.txt && break
  # بعد 40 ثانية من غير ما الصفحة تظهر: إعادة فتح التطبيق مرة واحدة
  if [ "$i" = "20" ]; then adb shell am force-stop com.ahmedhassan.hesabaty; adb shell am start -W -n com.ahmedhassan.hesabaty/.MainActivity; fi
done
sleep 3
adb logcat -d > logcat.txt || true
adb exec-out screencap -p > screen.png || true
PID=""
for i in 1 2 3 4 5; do PID="$(adb shell pidof com.ahmedhassan.hesabaty | tr -d '\r' || true)"; [ -n "$PID" ] && break; sleep 1; done
echo "pid=$PID"
grep -E "HesabatyWeb|HESABATY|CONSOLE|Uncaught|FATAL EXCEPTION|AndroidRuntime" logcat.txt | head -60 || true
grep -q "HESABATY_READY" logcat.txt || { echo "::error::صفحة التطبيق ما اتحملتش"; exit 1; }
[ -n "$PID" ] || echo "::warning::المحاكي ما رجّعش رقم العملية، بس الصفحة اتحملت"
# رسائل صفحة التطبيق فقط (مش عمليات النظام)
if grep "HesabatyWeb" logcat.txt | grep -q "Uncaught"; then echo "::error::خطأ JavaScript في الصفحة"; exit 1; fi
if grep -A4 "FATAL EXCEPTION" logcat.txt | grep -q "com.ahmedhassan.hesabaty"; then echo "::error::التطبيق وقع"; exit 1; fi
# زر الرجوع وإعادة الفتح
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -W -n com.ahmedhassan.hesabaty/.MainActivity; sleep 5
adb logcat -d | grep -A4 "FATAL EXCEPTION" | grep -q "com.ahmedhassan.hesabaty" && { echo "::error::التطبيق وقع بعد الرجوع"; exit 1; }
echo "✔ التطبيق اشتغل والصفحة اتحملت بدون أخطاء"
