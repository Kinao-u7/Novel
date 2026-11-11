// Browser Globals Polyfills & Setup
if (typeof globalThis.URL === 'undefined' && globalThis.__LIBS__?.whatwgUrl) {
  globalThis.URL = globalThis.__LIBS__.whatwgUrl.URL;
  globalThis.URLSearchParams = globalThis.__LIBS__.whatwgUrl.URLSearchParams;
}

if (typeof globalThis.atob === 'undefined') {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
  globalThis.atob = (input) => {
    let str = String(input).replace(/=+$/, ''), out = '';
    for (let bc = 0, bs = 0, buffer, i = 0; buffer = str.charAt(i++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? out += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
      buffer = chars.indexOf(buffer);
    }
    return out;
  };
  globalThis.btoa = (input) => {
    let str = String(input), out = '';
    for (let block = 0, charCode, i = 0, map = chars; str.charAt(i | 0) || (map = '=', i % 1); out += map.charAt(63 & block >> 8 - i % 1 * 8)) {
      charCode = str.charCodeAt(i += 3 / 4);
      block = block << 8 | charCode;
    }
    return out;
  };
}

if (typeof globalThis.TextEncoder === 'undefined') {
  globalThis.TextEncoder = class {
    encode(str) {
      const s = unescape(encodeURIComponent(String(str))), arr = new Uint8Array(s.length);
      for (let i = 0; i < s.length; i++) arr[i] = s.charCodeAt(i);
      return arr;
    }
  };
}

if (typeof globalThis.TextDecoder === 'undefined') {
  globalThis.TextDecoder = class {
    decode(arr) {
      if (!arr) return '';
      const b = new Uint8Array(arr.buffer || arr);
      let s = '';
      for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
      try { return decodeURIComponent(escape(s)); } catch (_) { return s; }
    }
  };
}

class AbortSignal {
  constructor() { this.aborted = false; this.reason = undefined; this._listeners = []; }
  addEventListener(t, l) { if (t === 'abort' && typeof l === 'function') this._listeners.push(l); }
  removeEventListener(t, l) { if (t === 'abort') { const i = this._listeners.indexOf(l); if (i !== -1) this._listeners.splice(i, 1); } }
  _abort(r) {
    if (this.aborted) return;
    this.aborted = true; this.reason = r;
    for (const l of this._listeners) { try { l.call(this, { type: 'abort', target: this }); } catch (_) {} }
    if (typeof this.onabort === 'function') { try { this.onabort.call(this, { type: 'abort', target: this }); } catch (_) {} }
  }
}

class AbortController {
  constructor() { this.signal = new AbortSignal(); }
  abort(reason) { this.signal._abort(reason); }
}
globalThis.AbortController = AbortController;
globalThis.AbortSignal = AbortSignal;

class Headers {
  constructor(init) {
    this._map = new Map();
    if (init instanceof Headers) init.forEach((v, k) => this.append(k, v));
    else if (Array.isArray(init)) for (const [k, v] of init) this.append(k, v);
    else if (init && typeof init === 'object') for (const [k, v] of Object.entries(init)) if (v !== undefined) this.append(k, v);
  }
  get(name) { return this._map.get(String(name).toLowerCase())?.value ?? null; }
  set(name, value) { const k = String(name); this._map.set(k.toLowerCase(), { originalKey: k, value: String(value) }); }
  append(name, value) {
    const k = String(name), lower = k.toLowerCase(), ex = this._map.get(lower);
    if (ex) ex.value += ', ' + String(value);
    else this._map.set(lower, { originalKey: k, value: String(value) });
  }
  has(name) { return this._map.has(String(name).toLowerCase()); }
  delete(name) { this._map.delete(String(name).toLowerCase()); }
  forEach(cb) { for (const e of this._map.values()) cb(e.value, e.originalKey, this); }
  entries() { return Array.from(this._map.values()).map(e => [e.originalKey, e.value])[Symbol.iterator](); }
  raw() { return Array.from(this._map.values()).map(e => [e.originalKey, e.value]); }
}
globalThis.Headers = Headers;

class FormData {
  constructor() { this._entries = []; }
  append(n, v) { this._entries.push([String(n), String(v)]); }
  set(n, v) {
    const k = String(n), val = String(v); let f = false;
    for (let i = 0; i < this._entries.length; i++) {
      if (this._entries[i][0] === k) {
        if (!f) { this._entries[i][1] = val; f = true; }
        else { this._entries.splice(i--, 1); }
      }
    }
    if (!f) this._entries.push([k, val]);
  }
  get(n) { return this._entries.find(e => e[0] === String(n))?.[1] ?? null; }
  has(n) { return this._entries.some(e => e[0] === String(n)); }
  delete(n) { this._entries = this._entries.filter(e => e[0] !== String(n)); }
  toString() { return this._entries.map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&'); }
}
globalThis.FormData = FormData;

class Blob { constructor(p, o) { this.size = 0; this.type = o?.type || ''; } }
globalThis.Blob = Blob;

class FileReader {
  readAsDataURL() {
    setTimeout(() => { if (typeof this.onerror === 'function') this.onerror(new Error('FileReader not supported')); }, 0);
  }
}
globalThis.FileReader = FileReader;

let __nextTimerId = 1;
const __timerCallbacks = new Map();
globalThis.setTimeout = function(fn, delay = 0, ...args) {
  const id = __nextTimerId++, clamped = Math.min(Math.max(0, Number(delay) || 0), 15000);
  __timerCallbacks.set(id, () => typeof fn === 'function' ? fn(...args) : eval(fn));
  if (__host_setTimeout(id, clamped) === null) {
    __timerCallbacks.delete(id);
    throw new Error('Failed to set timeout');
  }
  return id;
};
globalThis.clearTimeout = function(id) { if (id != null) { __timerCallbacks.delete(id); __host_clearTimeout(id); } };
globalThis.__dispatchTimeout = function(id) {
  const cb = __timerCallbacks.get(id);
  if (cb) { __timerCallbacks.delete(id); try { cb(); } catch (e) { console.error(e); } }
};

// Storage
class MemoryStorage {
  constructor() { this._db = {}; }
  set(k, v, exp) { this._db[k] = { created: new Date(), value: v, expires: exp instanceof Date ? exp.getTime() : exp }; }
  get(k, raw) {
    const it = this._db[k];
    if (it?.expires && Date.now() > it.expires) { delete this._db[k]; return undefined; }
    return raw ? it : it?.value;
  }
  getAllKeys() { return Object.keys(this._db); }
  delete(k) { delete this._db[k]; }
  clearAll() { this._db = {}; }
}
class WebStorageStub { constructor() { this._db = {}; } get() { return this._db; } }
const pluginStorage = new MemoryStorage(), pluginLocalStorage = new WebStorageStub(), pluginSessionStorage = new WebStorageStub();

// Fetch Implementation
async function fetchApi(url, init) {
  const headers = new Headers(init?.headers);
  let bodyText = null;
  if (init?.body != null) {
    if (init.body instanceof FormData) {
      if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/x-www-form-urlencoded');
      bodyText = init.body.toString();
    } else {
      bodyText = String(init.body);
    }
  }

  const req = {
    url: String(url),
    method: (init?.method || 'GET').toUpperCase(),
    headers: headers.raw(),
    bodyText,
    redirect: init?.redirect || 'follow',
    timeoutMs: init?.timeout || 30000,
    encoding: init?.encoding || null
  };

  try {
    const resStr = await __host_fetch(JSON.stringify(req));
    const data = JSON.parse(resStr), respHeaders = new Headers(data.headers);
    return {
      status: data.status,
      ok: data.ok,
      statusText: data.statusText,
      url: data.url,
      headers: respHeaders,
      async text() { return data.body; },
      async json() { return JSON.parse(data.body); },
      async blob() { return new Blob([], { type: respHeaders.get('Content-Type') || '' }); },
      async arrayBuffer() { return new TextEncoder().encode(data.body).buffer; }
    };
  } catch (e) {
    const msg = (e && e.message) || String(e), colon = msg.indexOf(': ');
    if (colon > 0) {
      const err = new Error(msg.slice(colon + 2));
      err.name = msg.slice(0, colon);
      throw err;
    }
    throw e;
  }
}

async function fetchText(url, init, encoding) {
  try {
    const opts = Object.assign({}, init);
    if (encoding) opts.encoding = encoding;
    const res = await fetchApi(url, opts);
    return res.ok ? await res.text() : '';
  } catch (_) {
    return '';
  }
}

async function fetchFile(url, init) {
  try {
    const res = await fetchApi(url, init);
    return res.ok ? await res.text() : '';
  } catch (_) {
    return '';
  }
}
globalThis.fetch = fetchApi;

const NovelStatus = {
  Unknown: 'Unknown', Ongoing: 'Ongoing', Completed: 'Completed', Licensed: 'Licensed',
  PublishingFinished: 'Publishing Finished', Cancelled: 'Cancelled', OnHiatus: 'On Hiatus',
  STUB: 'STUB', Inactive: 'Inactive'
};
const defaultCover = 'https://github.com/LNReader/lnreader-plugins/blob/main/icons/src/coverNotAvailable.jpg?raw=true';
const FilterTypes = { TextInput: 'Text', Picker: 'Picker', CheckboxGroup: 'Checkbox', Switch: 'Switch', ExcludableCheckboxGroup: 'XCheckbox' };

function isUrlAbsolute(url) {
  if (url) {
    if (url.indexOf('//') === 0) return true;
    if (url.indexOf('://') === -1) return false;
    if (url.indexOf('.') === -1) return false;
    if (url.indexOf('/') === -1) return false;
    if (url.indexOf(':') > url.indexOf('/')) return false;
    if (url.indexOf('://') < url.indexOf('.')) return true;
  }
  return false;
}

function require(m) {
  switch (m) {
    case 'cheerio': return globalThis.__LIBS__.cheerio;
    case 'htmlparser2': return globalThis.__LIBS__.htmlparser2;
    case 'dayjs': return globalThis.__LIBS__.dayjs;
    case 'whatwg-url': return globalThis.__LIBS__.whatwgUrl;
    case '@libs/fetch': return { fetchApi, fetchText, fetchFile };
    case '@libs/novelStatus': return { NovelStatus };
    case '@libs/filterInputs': return { FilterTypes };
    case '@libs/defaultCover': return { defaultCover };
    case '@libs/isAbsoluteUrl': return { isUrlAbsolute };
    case '@libs/storage': return { storage: pluginStorage, localStorage: pluginLocalStorage, sessionStorage: pluginSessionStorage };
    case '@libs/aes': return globalThis.__LIBS__.aes;
    case '@/types/constants': return { NovelStatus, defaultCover };
    default: {
      const err = new Error('Unsupported plugin feature: ' + m);
      err.name = 'UnsupportedPluginFeature';
      throw err;
    }
  }
}
globalThis.require = require;

const __loadedPlugins = new Map();
globalThis.__loadedPlugins = __loadedPlugins;

function __loadPlugin(id, sourceCode) {
  const cleanCode = sourceCode.replace(/^\uFEFF/, '').replace(/\0/g, '');
  const moduleObj = { exports: {} };
  const fn = new Function('module', 'exports', 'require', cleanCode);
  fn(moduleObj, moduleObj.exports, require);
  const plugin = moduleObj.exports.default || moduleObj.exports;
  __loadedPlugins.set(id, plugin);
  return { id: plugin.id || id, name: plugin.name || '', site: plugin.site || '', version: plugin.version || '', icon: plugin.icon || '' };
}
globalThis.__loadPlugin = __loadPlugin;

async function __callPlugin(id, method, argsJson) {
  const plugin = __loadedPlugins.get(id);
  if (!plugin) {
    const err = new Error('Plugin not found: ' + id);
    err.name = 'NotFoundError';
    throw err;
  }
  const target = plugin[method];
  if (typeof target !== 'function') {
    const err = new Error('Method not found: ' + method);
    err.name = 'NoSuchMethodError';
    throw err;
  }
  const args = argsJson ? JSON.parse(argsJson) : [];
  const result = await target.apply(plugin, args);
  return result === undefined ? 'null' : JSON.stringify(result);
}
globalThis.__callPlugin = __callPlugin;
