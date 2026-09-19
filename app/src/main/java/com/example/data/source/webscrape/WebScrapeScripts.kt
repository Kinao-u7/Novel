package com.example.data.source.webscrape

object WebScrapeScripts {

    /**
     * JavaScript executed against the loaded webpage DOM to extract novel metadata and chapter list.
     * Follows priority:
     * 1. JSON-LD structured data (<script type="application/ld+json">)
     * 2. Open Graph & standard meta tags (og:title, og:description, og:image, author)
     * 3. DOM heuristics for title, author, cover, synopsis, tags, and chapter links.
     */
    val NOVEL_DETAILS_SCRAPE_JS = """
        (function() {
            var result = {
                title: "",
                author: "",
                coverUrl: "",
                description: "",
                genres: [],
                chapters: [],
                sourceUrl: window.location.href
            };

            try {
                // 0. Click any "TOC" / "All Chapters" / "Chapters" tab or expand button if present
                try {
                    var tocButtons = document.querySelectorAll('button, [role="tab"], [role="button"], a.nav-link, a.tab, div.tab, span.tab, .accordion-title, [data-tab*="chapter"]');
                    for (var b = 0; b < tocButtons.length; b++) {
                        var btn = tocButtons[b];
                        var bText = (btn.innerText || '').trim().toLowerCase();
                        if (bText === 'chapters' || bText === 'all chapters' || bText === 'table of contents' || bText === 'toc' || bText.indexOf('all chapters') >= 0 || bText === 'chapter list') {
                            btn.click();
                        }
                    }
                } catch(e) {}

                // Helper to search deep in JSON objects for chapters array
                function findChaptersInObject(obj, depth) {
                    if (!obj || depth > 6) return null;
                    if (Array.isArray(obj)) {
                        if (obj.length > 0 && typeof obj[0] === 'object' && obj[0] !== null) {
                            var first = obj[0];
                            var hasTitle = !!(first.title || first.name || first.chapter_name || first.chapterName || first.chapter_title);
                            var hasUrlOrId = !!(first.url || first.id || first.slug || first.chapter_id || first.chapter_slug || first.order || first.chapter_number);
                            if (hasTitle || hasUrlOrId) {
                                return obj;
                            }
                        }
                        for (var i = 0; i < Math.min(obj.length, 6); i++) {
                            var foundInArr = findChaptersInObject(obj[i], depth + 1);
                            if (foundInArr && foundInArr.length > 0) return foundInArr;
                        }
                    } else if (typeof obj === 'object') {
                        // Check keys that likely contain chapter list
                        var candidateKeys = ['chapters', 'rawChapters', 'chapterList', 'chapter_list', 'items', 'list', 'toc', 'episodes', 'data'];
                        for (var k = 0; k < candidateKeys.length; k++) {
                            var key = candidateKeys[k];
                            if (obj[key] && Array.isArray(obj[key]) && obj[key].length > 0) {
                                var sample = obj[key][0];
                                if (sample && typeof sample === 'object') {
                                    if (sample.title || sample.name || sample.url || sample.id || sample.slug || sample.chapter_name) {
                                        return obj[key];
                                    }
                                }
                            }
                        }
                        for (var prop in obj) {
                            if (typeof obj[prop] === 'object') {
                                var foundInProp = findChaptersInObject(obj[prop], depth + 1);
                                if (foundInProp && foundInProp.length > 0) return foundInProp;
                            }
                        }
                    }
                    return null;
                }

                // Helper to search deep in JSON objects for genres/tags
                function findGenresInObject(obj, depth) {
                    if (!obj || depth > 5) return [];
                    var genres = [];
                    if (typeof obj === 'object') {
                        var targetKeys = ['genres', 'tags', 'categories', 'genre', 'tag', 'category'];
                        for (var k = 0; k < targetKeys.length; k++) {
                            var tKey = targetKeys[k];
                            if (obj[tKey]) {
                                if (Array.isArray(obj[tKey])) {
                                    obj[tKey].forEach(function(item) {
                                        var gName = typeof item === 'string' ? item : (item && (item.name || item.title || item.slug || ''));
                                        if (gName && gName.trim().length > 1 && genres.indexOf(gName.trim()) < 0) {
                                            genres.push(gName.trim());
                                        }
                                    });
                                } else if (typeof obj[tKey] === 'string') {
                                    obj[tKey].split(/[,|]/).forEach(function(s) {
                                        var trimmed = s.trim();
                                        if (trimmed.length > 1 && genres.indexOf(trimmed) < 0) genres.push(trimmed);
                                    });
                                }
                            }
                        }
                        for (var p in obj) {
                            if (typeof obj[p] === 'object') {
                                var sub = findGenresInObject(obj[p], depth + 1);
                                sub.forEach(function(g) { if (genres.indexOf(g) < 0) genres.push(g); });
                            }
                        }
                    }
                    return genres;
                }

                // Check Next.js __NEXT_DATA__
                var nextScript = document.getElementById("__NEXT_DATA__");
                if (nextScript) {
                    try {
                        var nextData = JSON.parse(nextScript.textContent);
                        var pp = (nextData.props && nextData.props.pageProps) || {};
                        var serie = pp.serie || pp.novel || pp.book || (pp.series && pp.series.data) || pp.series || pp.data;
                        if (serie && typeof serie === 'object') {
                            if (serie.title) result.title = serie.title;
                            if (serie.name && !result.title) result.title = serie.name;
                            if (serie.author) result.author = typeof serie.author === 'string' ? serie.author : (serie.author.name || '');
                            if (serie.description) result.description = serie.description.replace(/<[^>]+>/g, ' ').trim();
                            if (serie.synopsis && !result.description) result.description = serie.synopsis.replace(/<[^>]+>/g, ' ').trim();
                            if (serie.image) result.coverUrl = serie.image;
                            if (serie.cover) result.coverUrl = serie.cover;
                            if (serie.poster && !result.coverUrl) result.coverUrl = serie.poster;
                        }

                        // Search for genres in Next.js props
                        var nextGenres = findGenresInObject(pp, 0);
                        nextGenres.forEach(function(g) { if (result.genres.indexOf(g) < 0) result.genres.push(g); });

                        // Search for chapters in Next.js props
                        var foundNextChapters = findChaptersInObject(pp, 0);
                        if (foundNextChapters && foundNextChapters.length > 0) {
                            foundNextChapters.forEach(function(ch, idx) {
                                var chUrl = ch.url || '';
                                if (!chUrl && (ch.slug || ch.id || ch.chapter_id || ch.order)) {
                                    var idOrSlug = ch.slug || ch.id || ch.chapter_id || ch.order;
                                    var currentBase = window.location.href.split('?')[0].replace(/\/+$/, '');
                                    chUrl = currentBase + '/chapter-' + idOrSlug;
                                }
                                var chTitle = ch.title || ch.name || ch.chapter_name || ('Chapter ' + (idx + 1));
                                if (chUrl) {
                                    result.chapters.push({
                                        title: chTitle,
                                        url: chUrl,
                                        order: idx + 1
                                    });
                                }
                            });
                        }
                    } catch(e) {}
                }

                // 1. JSON-LD Structured Data
                var jsonLdScripts = document.querySelectorAll('script[type="application/ld+json"]');
                for (var i = 0; i < jsonLdScripts.length; i++) {
                    try {
                        var ld = JSON.parse(jsonLdScripts[i].textContent);
                        var items = Array.isArray(ld) ? ld : (ld['@graph'] ? ld['@graph'] : [ld]);
                        for (var j = 0; j < items.length; j++) {
                            var item = items[j];
                            var type = (item['@type'] || '').toLowerCase();
                            var isLikelyNovel = type.indexOf('book') >= 0 ||
                                                type.indexOf('creativework') >= 0 ||
                                                type.indexOf('novel') >= 0 ||
                                                type.indexOf('series') >= 0 ||
                                                type.indexOf('article') >= 0 ||
                                                (!result.title && item.name);
                            if (isLikelyNovel) {
                                if (!result.title && (item.name || item.headline)) {
                                    result.title = item.name || item.headline;
                                }
                                if (!result.author && item.author) {
                                    if (typeof item.author === 'string') {
                                        result.author = item.author;
                                    } else if (item.author.name) {
                                        result.author = item.author.name;
                                    } else if (Array.isArray(item.author)) {
                                        result.author = item.author.map(function(a) { return typeof a === 'string' ? a : (a.name || ''); }).filter(Boolean).join(', ');
                                    }
                                }
                                if (!result.description && item.description) {
                                    result.description = item.description;
                                }
                                if (!result.coverUrl && item.image) {
                                    if (typeof item.image === 'string') result.coverUrl = item.image;
                                    else if (item.image.url) result.coverUrl = item.image.url;
                                    else if (Array.isArray(item.image) && item.image.length > 0) {
                                        result.coverUrl = typeof item.image[0] === 'string' ? item.image[0] : (item.image[0].url || '');
                                    }
                                }
                                if (item.genre) {
                                    var gList = Array.isArray(item.genre) ? item.genre : [item.genre];
                                    gList.forEach(function(g) { if (g && result.genres.indexOf(g) < 0) result.genres.push(g); });
                                }
                                if (item.keywords) {
                                    var kwList = typeof item.keywords === 'string' ? item.keywords.split(',') : (Array.isArray(item.keywords) ? item.keywords : []);
                                    kwList.forEach(function(kw) {
                                        var kwTrim = kw.trim();
                                        if (kwTrim.length > 1 && result.genres.indexOf(kwTrim) < 0) result.genres.push(kwTrim);
                                    });
                                }
                            }
                        }
                    } catch(e) {}
                }

                // 2. Open Graph & Meta Tags
                var ogTitle = document.querySelector('meta[property="og:title"]');
                var twitterTitle = document.querySelector('meta[name="twitter:title"]');
                if (!result.title) {
                    if (ogTitle && ogTitle.content) result.title = ogTitle.content.trim();
                    else if (twitterTitle && twitterTitle.content) result.title = twitterTitle.content.trim();
                }

                var ogDesc = document.querySelector('meta[property="og:description"]');
                var metaDesc = document.querySelector('meta[name="description"]');
                var twitterDesc = document.querySelector('meta[name="twitter:description"]');
                if (!result.description) {
                    if (ogDesc && ogDesc.content) result.description = ogDesc.content.trim();
                    else if (metaDesc && metaDesc.content) result.description = metaDesc.content.trim();
                    else if (twitterDesc && twitterDesc.content) result.description = twitterDesc.content.trim();
                }

                var ogImg = document.querySelector('meta[property="og:image"]');
                var twitterImg = document.querySelector('meta[name="twitter:image"]');
                if (!result.coverUrl) {
                    if (ogImg && ogImg.content) result.coverUrl = ogImg.content.trim();
                    else if (twitterImg && twitterImg.content) result.coverUrl = twitterImg.content.trim();
                }

                var metaAuthor = document.querySelector('meta[name="author"]') || document.querySelector('meta[property="book:author"]');
                if (!result.author && metaAuthor && metaAuthor.content) {
                    result.author = metaAuthor.content.trim();
                }

                // Meta tags for genres/tags
                var metaKeywords = document.querySelector('meta[name="keywords"]');
                if (metaKeywords && metaKeywords.content) {
                    metaKeywords.content.split(/[,|]/).forEach(function(kw) {
                        var kwTrim = kw.trim();
                        if (kwTrim.length > 1 && kwTrim.length < 30 && !/^(?:novel|read|online|free|light novel)$/i.test(kwTrim)) {
                            if (result.genres.indexOf(kwTrim) < 0) result.genres.push(kwTrim);
                        }
                    });
                }
                var metaTags = document.querySelectorAll('meta[property="article:tag"], meta[property="og:article:tag"], meta[name="novel:genre"]');
                for (var mt = 0; mt < metaTags.length; mt++) {
                    var mContent = (metaTags[mt].content || '').trim();
                    if (mContent && result.genres.indexOf(mContent) < 0) result.genres.push(mContent);
                }

                // 3. Heuristics fallback: Title
                if (!result.title) {
                    var h1 = document.querySelector('h1');
                    if (h1 && h1.innerText && h1.innerText.trim().length > 1) {
                        result.title = h1.innerText.trim();
                    } else {
                        var docTitle = document.title || '';
                        docTitle = docTitle.replace(/[-|—–].*${'$'}/, '').replace(/\s*(?:Read\s+(?:Light\s+)?Novel|Online\s+Free|WTR-LAB|Novel\s*Updates|Light\s*Novel\s*Pub).*${'$'}/i, '').trim();
                        if (docTitle) result.title = docTitle;
                    }
                }

                // Clean title if it contains boilerplate suffix
                if (result.title) {
                    result.title = result.title.replace(/\s*[-|—–]\s*(?:Read\s+Novel|Light\s*Novel|WTR-LAB|NovelUpdates).*${'$'}/i, '').trim();
                }

                // 3. Heuristics fallback: Author
                if (!result.author) {
                    var authorEl = document.querySelector('[class*="author"], [id*="author"], [rel="author"], [itemprop="author"]');
                    if (authorEl && authorEl.innerText) {
                        result.author = authorEl.innerText.replace(/^(?:author|written\s*by|by)\s*[:：]?\s*/i, '').trim();
                    } else {
                        var allSpansAndLinks = document.querySelectorAll('span, a, p, div');
                        for (var k = 0; k < allSpansAndLinks.length; k++) {
                            var text = allSpansAndLinks[k].innerText || '';
                            var match = text.match(/^(?:author|written\s*by|by)\s*[:：]\s*([^\n\r,;]+)/i);
                            if (match && match[1] && match[1].trim().length > 1 && match[1].trim().length < 50) {
                                result.author = match[1].trim();
                                break;
                            }
                        }
                    }
                }
                if (!result.author) result.author = "Unknown Author";

                // 3. Heuristics fallback: Cover Image
                if (!result.coverUrl) {
                    var coverImg = document.querySelector('img[class*="cover"], img[id*="cover"], img[class*="poster"], img[class*="thumb"], .novel-cover img, .book-cover img, .series-img img, .thumbnail img');
                    if (coverImg && (coverImg.src || coverImg.getAttribute('data-src') || coverImg.getAttribute('data-lazy-src'))) {
                        result.coverUrl = coverImg.src || coverImg.getAttribute('data-src') || coverImg.getAttribute('data-lazy-src') || '';
                    }
                }

                // 3. Heuristics fallback: Synopsis / Description
                if (!result.description) {
                    var descEl = document.querySelector('[class*="synopsis"], [id*="synopsis"], [class*="description"], [id*="description"], [class*="summary"], [id*="summary"], [class*="intro"], [id*="intro"], [class*="story-detail"]');
                    if (descEl && descEl.innerText && descEl.innerText.trim().length > 20) {
                        result.description = descEl.innerText.trim();
                    } else {
                        var paragraphs = document.querySelectorAll('p');
                        var longestP = "";
                        for (var p = 0; p < Math.min(paragraphs.length, 30); p++) {
                            var pText = (paragraphs[p].innerText || '').trim();
                            if (pText.length > longestP.length && pText.length > 50) {
                                longestP = pText;
                            }
                        }
                        if (longestP) result.description = longestP;
                    }
                }

                // 3. Heuristics fallback: Tags / Genres from DOM
                if (result.genres.length === 0) {
                    var tagEls = document.querySelectorAll('[class*="genre"], [class*="tag"], [rel="tag"], a[href*="/genre"], a[href*="/tag"], a[href*="/genres/"], a[href*="/tags/"], a[href*="genre="], a[href*="tag="], .badge, .chip');
                    for (var t = 0; t < tagEls.length; t++) {
                        var tText = (tagEls[t].innerText || '').trim();
                        if (tText && tText.length >= 2 && tText.length <= 30) {
                            if (!/^(?:home|read|novel|chapters|bookmark|login|sign\s*up|menu|search)$/i.test(tText)) {
                                if (result.genres.indexOf(tText) < 0) {
                                    result.genres.push(tText);
                                }
                            }
                        }
                    }
                }

                // Also inspect metadata rows / definition lists / tables for genres & tags
                var metaRows = document.querySelectorAll('tr, dt, dd, li, div, p');
                for (var mr = 0; mr < metaRows.length; mr++) {
                    var rowText = (metaRows[mr].innerText || '').trim();
                    var matchGenre = rowText.match(/^(?:genres?|tags?|categories?)\s*[:：]\s*([\s\S]+)$/i);
                    if (matchGenre && matchGenre[1]) {
                        var extractedG = matchGenre[1].split(/[,/|\n\r•]+/).map(function(s) { return s.trim(); });
                        extractedG.forEach(function(g) {
                            if (g.length >= 2 && g.length <= 30 && result.genres.indexOf(g) < 0) {
                                result.genres.push(g);
                            }
                        });
                    }
                }

                // 4. Chapter List Extraction: Comprehensive DOM Scrape
                // If Next.js didn't find chapters or only found a few, inspect DOM thoroughly!
                if (result.chapters.length < 5) {
                    var seenUrls = {};
                    // Pre-populate seenUrls from any already found
                    result.chapters.forEach(function(ch) { seenUrls[ch.url] = true; });

                    // Find all candidate chapter containers
                    var candidateContainers = document.querySelectorAll('[class*="chapter-list"], [class*="chapters"], [id*="chapters"], [class*="toc"], .volume, ul.chapter, ol.chapter, [class*="table-of-contents"], [id*="chapter-list"], #list-chapter, .list-chapter, [class*="episode-list"], [id*="episode-list"], .tab-content, .panel-body, #tab-chapters, .chapter-container');

                    // If candidate containers exist, prefer the ones with the HIGHEST count of links
                    // (prevents picking a small "latest 2-4 chapters" preview box)
                    var bestContainerLinks = [];
                    var maxLinksInContainer = 0;
                    for (var cc = 0; cc < candidateContainers.length; cc++) {
                        // Skip if container is explicitly a "latest" or "recent" preview box
                        var cClass = (candidateContainers[cc].className || '') + ' ' + (candidateContainers[cc].id || '');
                        var isPreview = /latest|recent|preview|last-release/i.test(cClass);
                        var links = candidateContainers[cc].querySelectorAll('a[href]');
                        if (links.length > maxLinksInContainer && (!isPreview || links.length > 10)) {
                            maxLinksInContainer = links.length;
                            bestContainerLinks = links;
                        }
                    }

                    // Fallback to all links in document if no large container found
                    var candidateLinkList = (bestContainerLinks.length > 5) ? bestContainerLinks : document.querySelectorAll('a[href]');

                    var chapterRegex = /(?:chapter|ch[-_.]?|episode|ep[-_.]?)\s*(\d+)/i;
                    var pathRegex = /\/(?:chapter|ch|episode|ep|c|read)[-_/](\d+)/i;
                    var numericEndingRegex = /\/(?:serie-?\d+.*?\/)?(\d+)\/?(?:[?#]|$)/i;

                    var collectedCandidates = [];

                    for (var a = 0; a < candidateLinkList.length; a++) {
                        var anchor = candidateLinkList[a];
                        var href = anchor.href;
                        var aText = (anchor.innerText || anchor.getAttribute('title') || '').trim();

                        if (!href || href.startsWith('javascript:') || href === window.location.href || href === window.location.href + '#' || href.endsWith('#')) {
                            continue;
                        }

                        // Filter out navigation boilerplate
                        if (/^(?:next\s+chapter|prev(?:ious)?\s+chapter|first\s+chapter|last\s+chapter|latest\s+chapter|all\s+chapters|read\s+now|start\s+reading|table\s+of\s+contents)$/i.test(aText)) {
                            continue;
                        }

                        var isChapterHref = pathRegex.test(href) ||
                                            href.indexOf('/chapter-') >= 0 ||
                                            href.indexOf('/chapter/') >= 0 ||
                                            href.indexOf('/ch-') >= 0 ||
                                            href.indexOf('/ch/') >= 0 ||
                                            numericEndingRegex.test(href);

                        var isChapterText = chapterRegex.test(aText) ||
                                            /^\s*(?:chapter|ch|ep)?\s*#?\s*\d+\b/i.test(aText) ||
                                            /^\s*\d+[\s.:-]/i.test(aText) ||
                                            /^第\s*\d+\s*[章回]/i.test(aText);

                        var inChapterContainer = (bestContainerLinks.length > 5);

                        if (isChapterHref || isChapterText || inChapterContainer) {
                            var cleanUrl = href.split('#')[0];
                            if (!seenUrls[cleanUrl]) {
                                seenUrls[cleanUrl] = true;
                                var title = aText || ('Chapter ' + (collectedCandidates.length + 1));
                                collectedCandidates.push({
                                    title: title,
                                    url: cleanUrl,
                                    order: collectedCandidates.length + 1
                                });
                            }
                        }
                    }

                    // If collected candidates are more than what Next.js had, replace with the complete list!
                    if (collectedCandidates.length > result.chapters.length) {
                        result.chapters = collectedCandidates;
                    }
                }

                // Clean formatting
                if (result.description) {
                    result.description = result.description.replace(/<[^>]+>/g, ' ').replace(/\s{2,}/g, ' ').trim();
                }
            } catch (err) {
                result.error = err.toString();
            }

            return JSON.stringify(result);
        })();
    """.trimIndent()

    /**
     * JavaScript executed in background WebView to scrape chapter text.
     * Extracts title and list of paragraphs from the chapter page.
     */
    val CHAPTER_CONTENT_SCRAPE_JS = """
        (function() {
            var result = {
                title: "",
                paragraphs: [],
                wordCount: 0,
                url: window.location.href
            };

            try {
                // Find candidate title
                var h1 = document.querySelector('h1') || document.querySelector('.chapter-title') || document.querySelector('#chapter-title');
                if (h1 && h1.innerText) {
                    result.title = h1.innerText.trim();
                } else {
                    result.title = document.title.replace(/[-|—–].*${'$'}/, '').trim();
                }

                // Candidate reader content selectors
                var readerSelectors = [
                    '#chapter-content',
                    '.chapter-content',
                    '.chapter-body',
                    '#chapter-body',
                    '.reading-content',
                    '.entry-content',
                    '.post-content',
                    '.text-left',
                    '.reader-content',
                    '#content',
                    '.content',
                    '.ep-content',
                    '#chapterText',
                    '.chapterText',
                    '[class*="chapter-body"]',
                    '[class*="chapter-wrap"]',
                    '[class*="read-container"]',
                    '[class*="chapter-text"]',
                    '[id*="chapter-content"]',
                    'article'
                ];

                var contentContainer = null;
                for (var s = 0; s < readerSelectors.length; s++) {
                    var el = document.querySelector(readerSelectors[s]);
                    if (el && el.innerText && el.innerText.trim().length > 100) {
                        contentContainer = el;
                        break;
                    }
                }

                // Fallback heuristic: find element with largest text volume
                if (!contentContainer) {
                    var allContainers = document.querySelectorAll('div, section, article, main');
                    var bestEl = null;
                    var maxLen = 0;
                    for (var c = 0; c < allContainers.length; c++) {
                        var cand = allContainers[c];
                        // Ignore header/nav/footer containers
                        var tag = cand.tagName.toLowerCase();
                        if (tag === 'header' || tag === 'footer' || tag === 'nav') continue;
                        var textLen = (cand.innerText || '').length;
                        if (textLen > maxLen) {
                            maxLen = textLen;
                            bestEl = cand;
                        }
                    }
                    if (bestEl && maxLen > 100) {
                        contentContainer = bestEl;
                    }
                }

                if (contentContainer) {
                    // Clone container to clean it without modifying live DOM
                    var clone = contentContainer.cloneNode(true);

                    // Remove noise, scripts, ads, comments, social buttons, navigation
                    var unwanted = clone.querySelectorAll('script, style, noscript, nav, header, footer, iframe, svg, button, form, aside, [class*="advertisement"], [class*="ad-"], [id*="ad-"], [class*="banner"], [class*="comment"], [id*="comment"], [class*="social"], [class*="share"], [class*="pagination"], [class*="chapter-nav"], [id*="nav"]');
                    for (var u = 0; u < unwanted.length; u++) {
                        unwanted[u].parentNode && unwanted[u].parentNode.removeChild(unwanted[u]);
                    }

                    // Extract paragraphs
                    var pNodes = clone.querySelectorAll('p');
                    var extracted = [];
                    if (pNodes.length >= 3) {
                        for (var p = 0; p < pNodes.length; p++) {
                            var pText = (pNodes[p].innerText || '').trim();
                            if (pText.length > 0) {
                                // Filter out common ad/navigation strings
                                if (!/^(?:previous\s+chapter|next\s+chapter|table\s+of\s+contents|load\s+comments|advertisement)$/i.test(pText)) {
                                    extracted.push(pText);
                                }
                            }
                        }
                    }

                    // If p tags were not used or insufficient, split by newlines
                    if (extracted.length < 3) {
                        var rawText = clone.innerText || '';
                        var lines = rawText.split(/\r?\n\r?\n|\r?\n/);
                        extracted = [];
                        for (var l = 0; l < lines.length; l++) {
                            var line = lines[l].trim();
                            if (line.length > 0 && !/^(?:previous\s+chapter|next\s+chapter|table\s+of\s+contents|advertisement)$/i.test(line)) {
                                extracted.push(line);
                            }
                        }
                    }

                    result.paragraphs = extracted;
                    var totalWords = 0;
                    extracted.forEach(function(str) {
                        var words = str.split(/\s+/).filter(Boolean);
                        totalWords += words.length;
                    });
                    result.wordCount = totalWords;
                }
            } catch(e) {
                result.error = e.toString();
            }

            return JSON.stringify(result);
        })();
    """.trimIndent()
}
