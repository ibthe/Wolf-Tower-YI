// macOS: подписываем приложение "ad hoc" (без сертификата разработчика).
// Без подписи на Mac с чипом Apple приложение не запустится.
const { execFileSync } = require('child_process');
const path = require('path');

exports.default = async function afterPack(context) {
  if (context.electronPlatformName !== 'darwin') return;
  const app = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`);
  // Сначала сам ciadpi: в Resources его не подпишет --deep, а на Apple Silicon неподписанный бинарник убивается системой
  const bye = path.join(app, 'Contents', 'Resources', 'resources', 'bin', 'darwin', 'ciadpi');
  if (require('fs').existsSync(bye)) {
    require('fs').chmodSync(bye, 0o755);
    execFileSync('codesign', ['--force', '--sign', '-', bye], { stdio: 'inherit' });
  }
  console.log('ad hoc подпись:', app);
  execFileSync('codesign', ['--force', '--deep', '--sign', '-', app], { stdio: 'inherit' });
};
