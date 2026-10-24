// Копирует общие файлы проекта в resources/ (единый источник правды для Android и компьютера).
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const res = path.join(__dirname, '..', 'resources');
fs.mkdirSync(res, { recursive: true });

const files = [
  [path.join(root, 'profiles', 'profiles.json'), 'profiles.json'],
  [path.join(root, 'android', 'app', 'src', 'main', 'assets', 'license_public_key.txt'), 'license_public_key.txt'],
  [path.join(root, 'android', 'app', 'src', 'main', 'assets', 'third_party_licenses.txt'), 'third_party_licenses.txt'],
];
for (const [src, name] of files) {
  if (fs.existsSync(src)) {
    fs.copyFileSync(src, path.join(res, name));
    console.log('copied', name);
  } else {
    console.warn('НЕТ источника:', src);
  }
}
