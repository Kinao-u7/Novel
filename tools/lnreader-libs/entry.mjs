import * as cheerio from 'cheerio';
import * as htmlparser2 from 'htmlparser2';
import dayjs from 'dayjs';
import * as whatwgUrl from 'whatwg-url';

if (typeof globalThis !== 'undefined') {
  globalThis.__LIBS__ = {
    cheerio: cheerio.load ? cheerio : (cheerio.default || cheerio),
    htmlparser2: htmlparser2,
    dayjs: dayjs.default || dayjs,
    whatwgUrl: whatwgUrl.URL ? whatwgUrl : (whatwgUrl.default || whatwgUrl)
  };
}
