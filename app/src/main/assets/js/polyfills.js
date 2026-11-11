const g = typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this;

if (typeof g.TextEncoder === 'undefined') {
  g.TextEncoder = class TextEncoder {
    get encoding() { return 'utf-8'; }
    encode(str) {
      str = String(str);
      const bytes = [];
      for (let i = 0; i < str.length; i++) {
        let cp = str.codePointAt(i);
        if (cp > 0xFFFF) i++;
        if (cp <= 0x7F) bytes.push(cp);
        else if (cp <= 0x7FF) bytes.push(0xC0 | (cp >> 6), 0x80 | (cp & 0x3F));
        else if (cp <= 0xFFFF) bytes.push(0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
        else bytes.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
      }
      return new Uint8Array(bytes);
    }
  };
}

if (typeof g.TextDecoder === 'undefined') {
  g.TextDecoder = class TextDecoder {
    constructor(label, options) {
      this.encoding = 'utf-8';
      this.ignoreBOM = !!(options && options.ignoreBOM);
    }
    decode(input) {
      if (!input) return '';
      const b = input instanceof Uint8Array ? input : new Uint8Array(input.buffer || input);
      let s = '', i = 0, len = b.length;
      if (this.ignoreBOM && len >= 3 && b[0] === 0xEF && b[1] === 0xBB && b[2] === 0xBF) i = 3;
      while (i < len) {
        const b0 = b[i++];
        if (b0 < 0x80) s += String.fromCharCode(b0);
        else if (b0 >= 0xC0 && b0 < 0xE0) s += String.fromCharCode(((b0 & 0x1F) << 6) | ((i < len ? b[i++] : 0) & 0x3F));
        else if (b0 >= 0xE0 && b0 < 0xF0) s += String.fromCharCode(((b0 & 0x0F) << 12) | (((i < len ? b[i++] : 0) & 0x3F) << 6) | ((i < len ? b[i++] : 0) & 0x3F));
        else if (b0 >= 0xF0) {
          const cp = ((b0 & 0x07) << 18) | (((i < len ? b[i++] : 0) & 0x3F) << 12) | (((i < len ? b[i++] : 0) & 0x3F) << 6) | ((i < len ? b[i++] : 0) & 0x3F);
          s += String.fromCodePoint(cp);
        }
      }
      return s;
    }
  };
}

if (typeof g.Buffer === 'undefined') {
  g.Buffer = {
    isBuffer(obj) { return false; },
    from(input, enc) {
      if (typeof input === 'string') {
        const decoded = enc === 'base64' && typeof g.atob === 'function' ? g.atob(input) : input;
        return { toString: () => decoded };
      }
      const b = new Uint8Array(input?.buffer || input);
      let bin = '';
      for (let i = 0; i < b.length; i++) bin += String.fromCharCode(b[i]);
      return {
        toString(e) {
          if (e === 'base64' && typeof g.btoa === 'function') return g.btoa(bin);
          return bin;
        }
      };
    }
  };
}
