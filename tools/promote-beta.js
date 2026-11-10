#!/usr/bin/env node
/*
 * اعتماد النسخة التجريبية: ينقل beta/index.html ليصبح التطبيق الأساسي index.html
 *   node tools/promote-beta.js            (ثم: node tools/bump-version.js 2.2.0 "ملاحظات")
 * © 2026 أحمد حسن عبدالله - جميع الحقوق محفوظة.
 */
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const outDir = process.argv[2] ? path.resolve(process.argv[2]) : root;
let src = fs.readFileSync(path.join(root, 'beta', 'index.html'), 'utf8');
const pairs = [
  ['href="../manifest.json"', 'href="./manifest.json"'],
  ['href="../icons/', 'href="./icons/'],
  ['src="../icons/', 'src="./icons/'],
  ['href="../privacy.html"', 'href="./privacy.html"']
];
pairs.forEach(([a, b]) => {
  if (!src.includes(a)) { console.error('لم يتم العثور على: ' + a); process.exit(1); }
  src = src.split(a).join(b);
});
if (/(href|src)="\.\.\//.test(src)) { console.error('ما زالت هناك مسارات ../ في الملف'); process.exit(1); }
fs.writeFileSync(path.join(outDir, 'index.html'), src);
console.log('✔ تم نقل النسخة التجريبية إلى ' + path.join(outDir, 'index.html'));
