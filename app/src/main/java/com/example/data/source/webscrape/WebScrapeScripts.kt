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
                // Check Next.js __NEXT_DATA__ first if present
                var nextScript = document.getElementById("__NEXT_DATA__");
                if (nextScript) {
                    try {
                        var nextData = JSON.parse(nextScript.textContent);
                        var pp = (nextData.props && nextData.props.pageProps) || {};
                        var serie = pp.serie || pp.novel || pp.book || (pp.series && pp.series.data) || pp.series;
                        if (serie) {
                            if (typeof serie === 'object') {
                                if (serie.title) result.title = serie.title;
                                if (serie.name && !result.title) result.title = serie.name;
                                if (serie.author) result.author = typeof serie.author === 'string' ? serie.author : (serie.author.name || '');
                                if (serie.description) result.description = serie.description.replace(/<[^>]+>/g, ' ').trim();
                                if (serie.image) result.coverUrl = serie.image;
                                if (serie.cover) result.coverUrl = serie.cover;
                                if (Array.isArray(serie.genres)) result.genres = serie.genres.map(function(g) { return typeof g === 'string' ? g : (g.name || ''); });
                            }
                        }
                        if (Array.isArray(pp.chapters)) {
                            pp.chapters.forEach(function(ch, idx) {
                                var chUrl = ch.url || (ch.id ? (window.location.origin + '/chapter/' + ch.id) : '');
                                var chTitle = ch.title || ch.name || ('Chapter ' + (idx + 1));
                                if (chUrl) result.chapters.push({ title: chTitle, url: chUrl, order: idx + 1 });
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
                    var coverImg = document.querySelector('img[class*="cover"], img[id*="cover"], img[class*="poster"], img[class*="thumb"], .novel-cover img, .book-cover img, .series-img img');
                    if (coverImg && (coverImg.src || coverImg.getAttribute('data-src'))) {
                        result.coverUrl = coverImg.src || coverImg.getAttribute('data-src') || '';
                    }
                }

                // 3. Heuristics fallback: Synopsis / Description
                if (!result.description) {
                    var descEl = document.querySelector('[class*="synopsis"], [id*="synopsis"], [class*="description"], [id*="description"], [class*="summary"], [id*="summary"], [class*="intro"], [id*="intro"], [class*="story-detail"]');
                    if (descEl && descEl.innerText && descEl.innerText.trim().length > 20) {
                        result.description = descEl.innerText.trim();
                    } else {
                        // Pick longest paragraph in upper half of page
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

                // 3. Heuristics fallback: Tags / Genres
                if (result.genres.length === 0) {
                    var tagEls = document.querySelectorAll('[class*="genre"], [class*="tag"], [rel="tag"], a[href*="/genre/"], a[href*="/tag/"], a[href*="/genres/"], a[href*="/tags/"]');
                    for (var t = 0; t < tagEls.length; t++) {
                        var tText = (tagEls[t].innerText || '').trim();
                        if (tText && tText.length >= 2 && tText.length <= 30 && result.genres.indexOf(tText) < 0) {
                            result.genres.push(tText);
                        }
                    }
                }

                // 4. Chapter List Extraction
                if (result.chapters.length === 0) {
                    var chapterContainer = document.querySelector('[class*="chapter-list"], [class*="chapters"], [id*="chapters"], [class*="toc"], .volume, ul.chapter, ol.chapter, [class*="table-of-contents"], [id*="chapter-list"], #list-chapter, .list-chapter, [class*="episode-list"]');
                    var anchorList = (chapterContainer || document).querySelectorAll('a[href]');
                    var seenUrls = {};
                    var chapterRegex = /(?:chapter|ch[-_.]?|episode|ep[-_.]?)\s*(\d+)/i;
                    var pathRegex = /\/(?:chapter|ch|episode|c|read)[-_/](\d+)/i;

                    for (var a = 0; a < anchorList.length; a++) {
                        var anchor = anchorList[a];
                        var href = anchor.href;
                        var aText = (anchor.innerText || anchor.getAttribute('title') || '').trim();

                        if (!href || href.startsWith('javascript:') || href === window.location.href || href === window.location.href + '#' || href.endsWith('#')) {
                            continue;
                        }

                        // Check if href or text suggests a chapter
                        var isChapterHref = pathRegex.test(href) || href.indexOf('/chapter-') >= 0 || href.indexOf('/chapter/') >= 0 || href.indexOf('/ch-') >= 0;
                        var isChapterText = chapterRegex.test(aText) || /^\s*\d+[\s.:-]/i.test(aText) || /^第\s*\d+\s*[章回]/i.test(aText);

                        if (chapterContainer || isChapterHref || isChapterText) {
                            // Filter out navigation boilerplate like "Next Chapter" or "Previous Chapter" or "First Chapter"
                            if (/^(?:next\s+chapter|prev(?:ious)?\s+chapter|latest\s+chapter|all\s+chapters)$/i.test(aText)) {
                                continue;
                            }

                            // Normalize URL
                            var cleanUrl = href.split('#')[0];
                            if (!seenUrls[cleanUrl]) {
                                seenUrls[cleanUrl] = true;
                                var title = aText || ('Chapter ' + (result.chapters.length + 1));
                                result.chapters.push({
                                    title: title,
                                    url: cleanUrl,
                                    order: result.chapters.length + 1
                                });
                            }
                        }
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
