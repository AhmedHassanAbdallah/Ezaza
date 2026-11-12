#!/usr/bin/env bash
# تجهيز ملفات تطبيق الويب داخل مشروع أندرويد (تعمل بدون إنترنت) + خط Cairo
# © 2026 أحمد حسن عبدالله - جميع الحقوق محفوظة.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A="$ROOT/android/app/src/main/assets"
rm -rf "$A"
mkdir -p "$A/www/icons" "$A/fonts"
cp "$ROOT/index.html" "$ROOT/manifest.json" "$ROOT/privacy.html" "$ROOT/version.json" "$A/www/"
cp "$ROOT"/icons/*.png "$ROOT"/icons/*.jpg "$A/www/icons/"
UA="Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
if curl -fsSL -A "$UA" "https://fonts.googleapis.com/css2?family=Cairo:wght@500;600;700;800;900&display=swap" -o "$A/fonts/cairo.css"; then
  grep -o 'https://fonts.gstatic.com/[^)]*' "$A/fonts/cairo.css" | sort -u | while read -r u; do
    curl -fsSL "$u" -o "$A/fonts/$(basename "$u")"
  done
else
  echo "تحذير: تعذر تنزيل خط Cairo — التطبيق هيستخدم خط النظام بدون إنترنت"
  rm -f "$A/fonts/cairo.css"
fi
ls -la "$A/www" "$A/fonts"
