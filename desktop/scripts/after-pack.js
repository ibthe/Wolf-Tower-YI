// macOS: подписываем приложение "ad hoc" (без сертификата разработчика).
// Без подписи на Mac с чипом Apple приложение не запустится.
const { execFileSync } = require('child_process');
const path = require('path');

exports.default = async function afterPack(context) {
  if (context.electronPlatformName !== 'darwin') return;
  const app = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`);
  console.log('ad hoc подпись:', app);
  execFileSync('codesign', ['--force', '--deep', '--sign', '-', app], { stdio: 'inherit' });
};
