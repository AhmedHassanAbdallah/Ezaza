#!/usr/bin/env node
/*
 * إصدار تحديث جديد لتطبيق حساباتي بأمر واحد:
 *   node tools/bump-version.js 2.1.0 "ميزة جديدة 1" "إصلاح مشكلة 2"
 * يحدّث رقم الإصدار في: index.html و service-worker.js و version.json
 * © 2026 أحمد حسن عبدالله - جميع الحقوق محفوظة.
 */
const fs = require('fs');
const path = require('path');

const [version, ...notes] = process.argv.slice(2);
if (!/^\d+\.\d+\.\d+$/.test(version || '')) {
  console.error('الاستخدام: node tools/bump-version.js 2.1.0 "ملاحظة التحديث 1" "ملاحظة 2"');
  process.exit(1);
}
const root = path.join(__dirname, '..');
const replaceIn = (file, re, value) => {
  const p = path.join(root, file);
  const src = fs.readFileSync(p, 'utf8');
  if (!re.test(src)) { console.error('لم يتم العثور على رقم الإصدار في ' + file); process.exit(1); }
  fs.writeFileSync(p, src.replace(re, value));
};
replaceIn('index.html', /const APP_VERSION = '[^']*';/, `const APP_VERSION = '${version}';`);
replaceIn('service-worker.js', /const SW_VERSION = "[^"]*";/, `const SW_VERSION = "${version}";`);
const vPath = path.join(root, 'version.json');
const old = JSON.parse(fs.readFileSync(vPath, 'utf8'));
fs.writeFileSync(vPath, JSON.stringify({
  version,
  date: new Date().toISOString().slice(0, 10),
  notes: notes.length ? notes : old.notes
}, null, 2) + '\n');
console.log('✔ تم تجهيز الإصدار ' + version + ' — ارفع الملفات (commit + push) وسيظهر للمستخدمين خيار التحديث.');
