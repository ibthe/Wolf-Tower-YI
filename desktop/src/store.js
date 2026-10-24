const fs = require('fs');
const path = require('path');

/** Простое хранилище настроек в JSON-файле. */
class Store {
  constructor(dir) {
    this.file = path.join(dir, 'settings.json');
    this.data = {};
    try {
      this.data = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch (_) {
      this.data = {};
    }
  }

  get(key, fallback) {
    return Object.prototype.hasOwnProperty.call(this.data, key) ? this.data[key] : fallback;
  }

  set(key, value) {
    this.data[key] = value;
    this._save();
  }

  delete(key) {
    delete this.data[key];
    this._save();
  }

  _save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
    fs.renameSync(tmp, this.file);
  }
}

module.exports = { Store };
