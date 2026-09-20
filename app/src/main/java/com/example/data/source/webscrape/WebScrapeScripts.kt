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
                sourceUrl: window.location.href,
                _diagnosticLogs: [],
                _siteStructureDiag: {}
            };

            var diagLogs = [];
            function diag(msg) {
                diagLogs.push(msg);
                try {
                    console.log('[NOVEL_SCRAPE_DIAG] ' + msg);
                } catch(e) {}
            }

            diag("================== NOVEL EXTRACTION DIAGNOSTIC START ==================");
            diag("[DIAG] Page URL: " + window.location.href);
            diag("[DIAG] Document Title: " + (document.title || "empty"));
            diag("[DIAG] ReadyState: " + document.readyState);

            // Track field extraction winners and contributing strategies
            var strategyResults = {
                title: { nextData: null, jsonLd: null, metaTags: null, domHeuristic: null, winner: "none" },
                author: { nextData: null, jsonLd: null, metaTags: null, domHeuristic: null, winner: "none" },
                synopsis: { nextData: null, jsonLd: null, metaTags: null, domHeuristic: null, winner: "none" },
                tags: { nextData: [], jsonLd: [], metaTags: [], domHeuristic: [], winners: [] }
            };

            // SITE-SPECIFIC OVERRIDE: WTR-Lab exact metadata & chapter API extraction
            var currentHost = window.location.hostname.toLowerCase();
            var isWtrLab = currentHost.indexOf('wtr-lab.com') !== -1;
            if (isWtrLab) {
                diag("[WTR-LAB] Detected WTR-Lab domain (" + currentHost + "). Initiating exact Next.js data & API extraction...");
                var nextScript = document.getElementById("__NEXT_DATA__");
                if (nextScript && nextScript.textContent) {
                    try {
                        var nextData = JSON.parse(nextScript.textContent);
                        var pp = (nextData.props && nextData.props.pageProps) || {};
                        var serie = pp.serie || {};
                        var serieData = serie.serie_data || {};
                        var innerData = (serieData && serieData.data) || {};

                        // - Title: props.pageProps.serie.serie_data.data.title
                        var title = (innerData.title || serieData.title || serie.title || "").trim();

                        // - Author: props.pageProps.serie.serie_data.data.author (or top-level serie_data.author as fallback)
                        var author = "";
                        if (innerData.author) {
                            author = typeof innerData.author === 'string' ? innerData.author : (innerData.author.name || "");
                        }
                        if (!author && serieData.author) {
                            author = typeof serieData.author === 'string' ? serieData.author : (serieData.author.name || "");
                        }
                        if (!author && serie.author) {
                            author = typeof serie.author === 'string' ? serie.author : (serie.author.name || "");
                        }
                        author = author.trim() || "Unknown Author";

                        // - Synopsis: props.pageProps.serie.serie_data.data.description
                        var description = (innerData.description || serieData.description || serie.description || "").trim();
                        description = description.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();

                        // - raw_id: props.pageProps.serie.serie_data.raw_id
                        var rawId = "";
                        if (serieData.raw_id != null) {
                            rawId = serieData.raw_id.toString().trim();
                        } else if (serie.raw_id != null) {
                            rawId = serie.raw_id.toString().trim();
                        }

                        // - chapter_count: props.pageProps.serie.serie_data.chapter_count
                        var chapterCount = 0;
                        if (serieData.chapter_count != null) {
                            chapterCount = parseInt(serieData.chapter_count, 10) || 0;
                        } else if (serie.chapter_count != null) {
                            chapterCount = parseInt(serie.chapter_count, 10) || 0;
                        }

                        // - Genre/tag IDs: props.pageProps.serie.serie_data.genres and tags
                        var genreIds = Array.isArray(serieData.genres) ? serieData.genres : [];
                        var tagIds = Array.isArray(serieData.tags) ? serieData.tags : [];

                        // - Tag name lookup: props.pageProps.tags [{id, title, category_id, category_name}, ...]
                        var tagsLookup = Array.isArray(pp.tags) ? pp.tags : [];
                        var tagMap = {};
                        for (var t = 0; t < tagsLookup.length; t++) {
                            var tObj = tagsLookup[t];
                            if (tObj && tObj.id != null) {
                                var tName = (tObj.title || tObj.name || "").trim();
                                if (tName) {
                                    tagMap[tObj.id.toString()] = tName;
                                }
                            }
                        }

                        var resolvedTags = [];
                        function addTagId(idVal) {
                            if (idVal == null) return;
                            var idStr = idVal.toString();
                            var name = tagMap[idStr];
                            if (name && resolvedTags.indexOf(name) === -1) {
                                resolvedTags.push(name);
                            }
                        }
                        for (var g = 0; g < genreIds.length; g++) addTagId(genreIds[g]);
                        for (var tg = 0; tg < tagIds.length; tg++) addTagId(tagIds[tg]);

                        // Cover
                        var coverUrl = innerData.image || innerData.cover || serieData.image || serieData.cover || serie.image || serie.cover || serie.cover_url || "";
                        if (!coverUrl) {
                            var ogMeta = document.querySelector('meta[property="og:image"], meta[name="og:image"]');
                            if (ogMeta && ogMeta.content) coverUrl = ogMeta.content;
                        }
                        if (coverUrl && coverUrl.indexOf('http') !== 0) {
                            coverUrl = 'https://wtr-lab.com' + (coverUrl.charAt(0) === '/' ? '' : '/') + coverUrl;
                        }

                        // Locale and serie_slug
                        var qObj = nextData.query || (pp && pp.query) || {};
                        var locale = (qObj.locale || pp.locale || "").trim();
                        var serieSlug = (qObj.serie_slug || serie.slug || serieData.slug || "").trim();

                        var pathTokens = window.location.pathname.split('/').filter(Boolean);
                        if (!locale && pathTokens.length > 0 && /^[a-z]{2}(-[a-z]{2})?$/i.test(pathTokens[0])) {
                            locale = pathTokens[0];
                        }
                        if (!locale) locale = "en";

                        if (!serieSlug) {
                            for (var p = pathTokens.length - 1; p >= 0; p--) {
                                var tok = pathTokens[p];
                                if (tok.indexOf('serie') !== 0 && tok !== locale && isNaN(Number(tok))) {
                                    serieSlug = tok;
                                    break;
                                }
                            }
                        }
                        if (!serieSlug && rawId) {
                            serieSlug = "serie-" + rawId;
                        }

                        diag("[WTR-LAB] Title: " + title);
                        diag("[WTR-LAB] Author: " + author);
                        diag("[WTR-LAB] Synopsis: " + (description.length > 80 ? description.substring(0, 80) + "..." : description));
                        diag("[WTR-LAB] raw_id: " + rawId + ", chapter_count: " + chapterCount);
                        diag("[WTR-LAB] locale: " + locale + ", serie_slug: " + serieSlug);
                        diag("[WTR-LAB] Resolved " + resolvedTags.length + " tags/genres: [" + resolvedTags.join(", ") + "]");

                        // Direct HTTP GET for chapters
                        var chapters = [];
                        if (rawId) {
                            var endParam = (chapterCount > 0 ? chapterCount + 50 : 200);
                            var apiUrl = 'https://wtr-lab.com/api/chapters/' + rawId + '?start=1&end=' + endParam;
                            diag("[WTR-LAB] Fetching chapter list from API: " + apiUrl);
                            try {
                                var xhr = new XMLHttpRequest();
                                xhr.open('GET', apiUrl, false);
                                xhr.setRequestHeader('Accept', 'application/json, text/plain, */*');
                                xhr.send(null);
                                if (xhr.status >= 200 && xhr.status < 300) {
                                    var apiData = JSON.parse(xhr.responseText);
                                    var rawChapters = (apiData && apiData.chapters) || [];
                                    diag("[WTR-LAB] API successfully returned " + rawChapters.length + " chapters.");
                                    for (var c = 0; c < rawChapters.length; c++) {
                                        var item = rawChapters[c];
                                        var order = item.order != null ? item.order : (c + 1);
                                        var chTitle = (item.title || item.name || ("Chapter " + order)).trim();
                                        var chUrl = 'https://wtr-lab.com/' + locale + '/novel/' + rawId + '/' + serieSlug + '/chapter-' + order;
                                        chapters.push({
                                            title: chTitle,
                                            url: chUrl,
                                            order: order
                                        });
                                    }
                                } else {
                                    diag("[WTR-LAB] API XHR returned status " + xhr.status + ": " + xhr.statusText);
                                }
                            } catch(xhrErr) {
                                diag("[WTR-LAB] Synchronous XHR failed in WebView: " + xhrErr.toString());
                            }
                        }

                        result.title = title;
                        result.author = author;
                        result.description = description;
                        result.genres = resolvedTags;
                        result.coverUrl = coverUrl;
                        result.chapters = chapters;
                        result.rawId = rawId;
                        result.chapterCount = chapterCount;
                        result.locale = locale;
                        result.serieSlug = serieSlug;
                        result.isWtrLab = true;
                        result.rawNextData = nextScript.textContent;

                        result._siteStructureDiag = {
                            isWtrLab: true,
                            rawId: rawId,
                            chapterCount: chapterCount,
                            locale: locale,
                            serieSlug: serieSlug,
                            genresCount: resolvedTags.length,
                            chaptersCount: chapters.length
                        };

                        diag("[WTR-LAB] Extraction finished. Title='" + title + "', Chapters=" + chapters.length + "/" + chapterCount);
                        diag("================== NOVEL EXTRACTION DIAGNOSTIC END ==================");
                        result._diagnosticLogs = diagLogs;
                        return JSON.stringify(result);
                    } catch(wtrEx) {
                        diag("[WTR-LAB] Exception in WTR-Lab extraction: " + wtrEx.toString());
                    }
                } else {
                    diag("[WTR-LAB] Warning: #__NEXT_DATA__ not found on WTR-Lab page.");
                }
            }

            try {
                // 0. Click any "TOC" / "All Chapters" / "Chapters" tab or expand button if present
                try {
                    var tocButtons = document.querySelectorAll('button, [role="tab"], [role="button"], a.nav-link, a.tab, div.tab, span.tab, .accordion-title, [data-tab*="chapter"]');
                    var clickedCount = 0;
                    for (var b = 0; b < tocButtons.length; b++) {
                        var btn = tocButtons[b];
                        var bText = (btn.innerText || '').trim().toLowerCase();
                        if (bText === 'chapters' || bText === 'all chapters' || bText === 'table of contents' || bText === 'toc' || bText.indexOf('all chapters') >= 0 || bText === 'chapter list') {
                            btn.click();
                            clickedCount++;
                        }
                    }
                    diag("[STAGE 0: SETUP] TOC / Expand button clicks attempted. Found " + tocButtons.length + " candidate buttons, clicked: " + clickedCount);
                } catch(e) {
                    diag("[STAGE 0: SETUP] Error in TOC button click: " + e.toString());
                }

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

                // STRATEGY 1: Check Next.js __NEXT_DATA__
                diag("[STRATEGY 1: __NEXT_DATA__] Attempting extraction...");
                var nextScript = document.getElementById("__NEXT_DATA__");
                var nextDataChaptersCount = 0;
                if (nextScript) {
                    try {
                        diag("[STRATEGY 1: __NEXT_DATA__] Element #__NEXT_DATA__ exists (length=" + (nextScript.textContent ? nextScript.textContent.length : 0) + ")");
                        var nextData = JSON.parse(nextScript.textContent);
                        var topKeys = nextData ? Object.keys(nextData) : [];
                        diag("[STRATEGY 1: __NEXT_DATA__] Top-level keys: [" + topKeys.join(", ") + "]");

                        var pp = (nextData.props && nextData.props.pageProps) || {};
                        var ppKeys = Object.keys(pp);
                        diag("[STRATEGY 1: __NEXT_DATA__] pageProps keys: [" + ppKeys.join(", ") + "]");

                        var serie = pp.serie || pp.novel || pp.book || (pp.series && pp.series.data) || pp.series || pp.data;
                        if (serie && typeof serie === 'object') {
                            var rawNextTitle = serie.title || serie.name || null;
                            var rawNextAuthor = serie.author ? (typeof serie.author === 'string' ? serie.author : (serie.author.name || '')) : null;
                            var rawNextDesc = serie.description ? serie.description.replace(/<[^>]+>/g, ' ').trim() : (serie.synopsis ? serie.synopsis.replace(/<[^>]+>/g, ' ').trim() : null);

                            strategyResults.title.nextData = rawNextTitle;
                            strategyResults.author.nextData = rawNextAuthor;
                            strategyResults.synopsis.nextData = rawNextDesc;

                            if (rawNextTitle) {
                                result.title = rawNextTitle;
                                strategyResults.title.winner = "__NEXT_DATA__";
                            }
                            if (rawNextAuthor) {
                                result.author = rawNextAuthor;
                                strategyResults.author.winner = "__NEXT_DATA__";
                            }
                            if (rawNextDesc) {
                                result.description = rawNextDesc;
                                strategyResults.synopsis.winner = "__NEXT_DATA__";
                            }
                            if (serie.image) result.coverUrl = serie.image;
                            if (serie.cover) result.coverUrl = serie.cover;
                            if (serie.poster && !result.coverUrl) result.coverUrl = serie.poster;
                        }

                        // Search for genres in Next.js props
                        var nextGenres = findGenresInObject(pp, 0);
                        strategyResults.tags.nextData = nextGenres;
                        if (nextGenres.length > 0) {
                            strategyResults.tags.winners.push("__NEXT_DATA__");
                            nextGenres.forEach(function(g) { if (result.genres.indexOf(g) < 0) result.genres.push(g); });
                        }

                        // Search for chapters in Next.js props
                        var foundNextChapters = findChaptersInObject(pp, 0);
                        if (foundNextChapters && foundNextChapters.length > 0) {
                            diag("[STRATEGY 1: __NEXT_DATA__] findChaptersInObject found array with " + foundNextChapters.length + " raw items.");
                            foundNextChapters.forEach(function(ch, idx) {
                                var chUrl = ch.url || '';
                                if (!chUrl && (ch.slug || ch.id || ch.chapter_id || ch.order)) {
                                    var idOrSlug = ch.slug || ch.id || ch.chapter_id || ch.order;
                                    var currentBase = window.location.href.split('?')[0].replace(/\/+${'$'}/, '');
                                    chUrl = currentBase + '/chapter-' + idOrSlug;
                                }
                                var chTitle = ch.title || ch.name || ch.chapter_name || ('Chapter ' + (idx + 1));
                                if (chUrl) {
                                    result.chapters.push({
                                        title: chTitle,
                                        url: chUrl,
                                        order: idx + 1
                                    });
                                    nextDataChaptersCount++;
                                }
                            });
                            diag("[STRATEGY 1: __NEXT_DATA__] Successfully parsed " + nextDataChaptersCount + " chapters from __NEXT_DATA__.");
                        } else {
                            diag("[STRATEGY 1: __NEXT_DATA__] findChaptersInObject returned null or empty array.");
                        }
                    } catch(e) {
                        diag("[STRATEGY 1: __NEXT_DATA__] Parse error: " + e.toString());
                    }
                } else {
                    diag("[STRATEGY 1: __NEXT_DATA__] Element #__NEXT_DATA__ does not exist in DOM.");
                }

                diag("[FIELD: TITLE] [STRATEGY: __NEXT_DATA__]: " + (strategyResults.title.nextData ? '"' + strategyResults.title.nextData + '"' : "null/empty"));
                diag("[FIELD: AUTHOR] [STRATEGY: __NEXT_DATA__]: " + (strategyResults.author.nextData ? '"' + strategyResults.author.nextData + '"' : "null/empty"));
                diag("[FIELD: SYNOPSIS] [STRATEGY: __NEXT_DATA__]: " + (strategyResults.synopsis.nextData ? '"' + strategyResults.synopsis.nextData.substring(0, 80) + '... (len=' + strategyResults.synopsis.nextData.length + ')"' : "null/empty"));
                diag("[FIELD: TAGS] [STRATEGY: __NEXT_DATA__]: " + (strategyResults.tags.nextData.length > 0 ? JSON.stringify(strategyResults.tags.nextData) : "null/empty"));
                diag("[FIELD: CHAPTERS] [STRATEGY: __NEXT_DATA__]: parsed=" + nextDataChaptersCount + " chapters.");

                // STRATEGY 2: JSON-LD Structured Data
                diag("[STRATEGY 2: JSON-LD] Attempting extraction...");
                var jsonLdScripts = document.querySelectorAll('script[type="application/ld+json"]');
                diag("[STRATEGY 2: JSON-LD] Found " + jsonLdScripts.length + " <script type=\"application/ld+json\"> tags in DOM.");
                for (var i = 0; i < jsonLdScripts.length; i++) {
                    try {
                        var ld = JSON.parse(jsonLdScripts[i].textContent);
                        var items = Array.isArray(ld) ? ld : (ld['@graph'] ? ld['@graph'] : [ld]);
                        diag("[STRATEGY 2: JSON-LD] Script [" + i + "] items count: " + items.length);
                        for (var j = 0; j < items.length; j++) {
                            var item = items[j];
                            var type = (item['@type'] || '').toLowerCase();
                            var isLikelyNovel = type.indexOf('book') >= 0 ||
                                                type.indexOf('creativework') >= 0 ||
                                                type.indexOf('novel') >= 0 ||
                                                type.indexOf('series') >= 0 ||
                                                type.indexOf('article') >= 0 ||
                                                (!result.title && item.name);
                            diag("[STRATEGY 2: JSON-LD] Script [" + i + "] item [" + j + "] @type=\"" + type + "\", isLikelyNovel=" + isLikelyNovel);
                            if (isLikelyNovel) {
                                if (item.name || item.headline) {
                                    var ldTitle = item.name || item.headline;
                                    strategyResults.title.jsonLd = ldTitle;
                                    if (!result.title) {
                                        result.title = ldTitle;
                                        strategyResults.title.winner = "JSON-LD";
                                    }
                                }
                                if (item.author) {
                                    var ldAuthor = "";
                                    if (typeof item.author === 'string') {
                                        ldAuthor = item.author;
                                    } else if (item.author.name) {
                                        ldAuthor = item.author.name;
                                    } else if (Array.isArray(item.author)) {
                                        ldAuthor = item.author.map(function(a) { return typeof a === 'string' ? a : (a.name || ''); }).filter(Boolean).join(', ');
                                    }
                                    if (ldAuthor) {
                                        strategyResults.author.jsonLd = ldAuthor;
                                        if (!result.author) {
                                            result.author = ldAuthor;
                                            strategyResults.author.winner = "JSON-LD";
                                        }
                                    }
                                }
                                if (item.description) {
                                    strategyResults.synopsis.jsonLd = item.description;
                                    if (!result.description) {
                                        result.description = item.description;
                                        strategyResults.synopsis.winner = "JSON-LD";
                                    }
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
                                    gList.forEach(function(g) {
                                        if (g && result.genres.indexOf(g) < 0) {
                                            result.genres.push(g);
                                            strategyResults.tags.jsonLd.push(g);
                                            if (strategyResults.tags.winners.indexOf("JSON-LD") < 0) strategyResults.tags.winners.push("JSON-LD");
                                        }
                                    });
                                }
                                if (item.keywords) {
                                    var kwList = typeof item.keywords === 'string' ? item.keywords.split(',') : (Array.isArray(item.keywords) ? item.keywords : []);
                                    kwList.forEach(function(kw) {
                                        var kwTrim = kw.trim();
                                        if (kwTrim.length > 1 && result.genres.indexOf(kwTrim) < 0) {
                                            result.genres.push(kwTrim);
                                            strategyResults.tags.jsonLd.push(kwTrim);
                                            if (strategyResults.tags.winners.indexOf("JSON-LD") < 0) strategyResults.tags.winners.push("JSON-LD");
                                        }
                                    });
                                }
                            }
                        }
                    } catch(e) {
                        diag("[STRATEGY 2: JSON-LD] Script [" + i + "] parse error: " + e.toString());
                    }
                }

                diag("[FIELD: TITLE] [STRATEGY: JSON-LD]: " + (strategyResults.title.jsonLd ? '"' + strategyResults.title.jsonLd + '"' : "null/empty"));
                diag("[FIELD: AUTHOR] [STRATEGY: JSON-LD]: " + (strategyResults.author.jsonLd ? '"' + strategyResults.author.jsonLd + '"' : "null/empty"));
                diag("[FIELD: SYNOPSIS] [STRATEGY: JSON-LD]: " + (strategyResults.synopsis.jsonLd ? '"' + strategyResults.synopsis.jsonLd.substring(0, 80) + '... (len=' + strategyResults.synopsis.jsonLd.length + ')"' : "null/empty"));
                diag("[FIELD: TAGS] [STRATEGY: JSON-LD]: " + (strategyResults.tags.jsonLd.length > 0 ? JSON.stringify(strategyResults.tags.jsonLd) : "null/empty"));

                // STRATEGY 3: Open Graph & Meta Tags
                diag("[STRATEGY 3: META TAGS] Attempting extraction...");
                var ogTitle = document.querySelector('meta[property="og:title"]');
                var twitterTitle = document.querySelector('meta[name="twitter:title"]');
                var metaTitleVal = (ogTitle && ogTitle.content) ? ogTitle.content.trim() : ((twitterTitle && twitterTitle.content) ? twitterTitle.content.trim() : null);
                strategyResults.title.metaTags = metaTitleVal;
                if (!result.title && metaTitleVal) {
                    result.title = metaTitleVal;
                    strategyResults.title.winner = "Meta Tags";
                }

                var ogDesc = document.querySelector('meta[property="og:description"]');
                var metaDesc = document.querySelector('meta[name="description"]');
                var twitterDesc = document.querySelector('meta[name="twitter:description"]');
                var metaDescVal = (ogDesc && ogDesc.content) ? ogDesc.content.trim() : ((metaDesc && metaDesc.content) ? metaDesc.content.trim() : ((twitterDesc && twitterDesc.content) ? twitterDesc.content.trim() : null));
                strategyResults.synopsis.metaTags = metaDescVal;
                if (!result.description && metaDescVal) {
                    result.description = metaDescVal;
                    strategyResults.synopsis.winner = "Meta Tags";
                }

                var ogImg = document.querySelector('meta[property="og:image"]');
                var twitterImg = document.querySelector('meta[name="twitter:image"]');
                if (!result.coverUrl) {
                    if (ogImg && ogImg.content) result.coverUrl = ogImg.content.trim();
                    else if (twitterImg && twitterImg.content) result.coverUrl = twitterImg.content.trim();
                }

                var metaAuthor = document.querySelector('meta[name="author"]') || document.querySelector('meta[property="book:author"]');
                var metaAuthorVal = (metaAuthor && metaAuthor.content) ? metaAuthor.content.trim() : null;
                strategyResults.author.metaTags = metaAuthorVal;
                if (!result.author && metaAuthorVal) {
                    result.author = metaAuthorVal;
                    strategyResults.author.winner = "Meta Tags";
                }

                // Meta tags for genres/tags
                var metaKeywords = document.querySelector('meta[name="keywords"]');
                if (metaKeywords && metaKeywords.content) {
                    metaKeywords.content.split(/[,|]/).forEach(function(kw) {
                        var kwTrim = kw.trim();
                        if (kwTrim.length > 1 && kwTrim.length < 30 && !/^(?:novel|read|online|free|light novel)$/i.test(kwTrim)) {
                            if (result.genres.indexOf(kwTrim) < 0) {
                                result.genres.push(kwTrim);
                                strategyResults.tags.metaTags.push(kwTrim);
                                if (strategyResults.tags.winners.indexOf("Meta Tags") < 0) strategyResults.tags.winners.push("Meta Tags");
                            }
                        }
                    });
                }
                var metaTags = document.querySelectorAll('meta[property="article:tag"], meta[property="og:article:tag"], meta[name="novel:genre"]');
                for (var mt = 0; mt < metaTags.length; mt++) {
                    var mContent = (metaTags[mt].content || '').trim();
                    if (mContent && result.genres.indexOf(mContent) < 0) {
                        result.genres.push(mContent);
                        strategyResults.tags.metaTags.push(mContent);
                        if (strategyResults.tags.winners.indexOf("Meta Tags") < 0) strategyResults.tags.winners.push("Meta Tags");
                    }
                }

                diag("[FIELD: TITLE] [STRATEGY: META TAGS]: " + (metaTitleVal ? '"' + metaTitleVal + '"' : "null/empty") + " (og=" + !!ogTitle + ", twitter=" + !!twitterTitle + ")");
                diag("[FIELD: AUTHOR] [STRATEGY: META TAGS]: " + (metaAuthorVal ? '"' + metaAuthorVal + '"' : "null/empty"));
                diag("[FIELD: SYNOPSIS] [STRATEGY: META TAGS]: " + (metaDescVal ? '"' + metaDescVal.substring(0, 80) + '... (len=' + metaDescVal.length + ')"' : "null/empty"));
                diag("[FIELD: TAGS] [STRATEGY: META TAGS]: " + (strategyResults.tags.metaTags.length > 0 ? JSON.stringify(strategyResults.tags.metaTags) : "null/empty") + " (metaKeywords=" + !!metaKeywords + ", article:tag count=" + metaTags.length + ")");

                // STRATEGY 4: DOM Heuristic Fallback
                diag("[STRATEGY 4: DOM HEURISTICS] Attempting extraction...");

                // Title fallback
                var heurTitleVal = null;
                if (!result.title) {
                    var h1 = document.querySelector('h1');
                    if (h1 && h1.innerText && h1.innerText.trim().length > 1) {
                        heurTitleVal = h1.innerText.trim();
                        result.title = heurTitleVal;
                        strategyResults.title.winner = "DOM Heuristic (<h1>)";
                    } else {
                        var docTitle = document.title || '';
                        docTitle = docTitle.replace(/[-|—–].*${'$'}/, '').replace(/\s*(?:Read\s+(?:Light\s+)?Novel|Online\s+Free|WTR-LAB|Novel\s*Updates|Light\s*Novel\s*Pub).*${'$'}/i, '').trim();
                        if (docTitle) {
                            heurTitleVal = docTitle;
                            result.title = docTitle;
                            strategyResults.title.winner = "DOM Heuristic (document.title)";
                        }
                    }
                }
                strategyResults.title.domHeuristic = heurTitleVal;
                diag("[FIELD: TITLE] [STRATEGY: DOM HEURISTIC]: " + (heurTitleVal ? '"' + heurTitleVal + '"' : "null/empty (existing title: \"" + result.title + "\")"));

                // Clean title if it contains boilerplate suffix
                if (result.title) {
                    result.title = result.title.replace(/\s*[-|—–]\s*(?:Read\s+Novel|Light\s*Novel|WTR-LAB|NovelUpdates).*${'$'}/i, '').trim();
                }

                // Author fallback
                var heurAuthorVal = null;
                if (!result.author) {
                    var authorEl = document.querySelector('[class*="author"], [id*="author"], [rel="author"], [itemprop="author"]');
                    if (authorEl && authorEl.innerText) {
                        heurAuthorVal = authorEl.innerText.replace(/^(?:author|written\s*by|by)\s*[:：]?\s*/i, '').trim();
                        result.author = heurAuthorVal;
                        strategyResults.author.winner = "DOM Heuristic (author element: " + authorEl.tagName + "." + authorEl.className + ")";
                    } else {
                        var allSpansAndLinks = document.querySelectorAll('span, a, p, div');
                        for (var k = 0; k < allSpansAndLinks.length; k++) {
                            var text = allSpansAndLinks[k].innerText || '';
                            var match = text.match(/^(?:author|written\s*by|by)\s*[:：]\s*([^\n\r,;]+)/i);
                            if (match && match[1] && match[1].trim().length > 1 && match[1].trim().length < 50) {
                                heurAuthorVal = match[1].trim();
                                result.author = heurAuthorVal;
                                strategyResults.author.winner = "DOM Heuristic (regex over spans/links)";
                                break;
                            }
                        }
                    }
                }
                strategyResults.author.domHeuristic = heurAuthorVal;
                diag("[FIELD: AUTHOR] [STRATEGY: DOM HEURISTIC]: " + (heurAuthorVal ? '"' + heurAuthorVal + '"' : "null/empty (existing author: \"" + result.author + "\")"));
                if (!result.author) {
                    result.author = "Unknown Author";
                    strategyResults.author.winner = "Fallback Default ('Unknown Author')";
                }

                // Cover Image fallback
                if (!result.coverUrl) {
                    var coverImg = document.querySelector('img[class*="cover"], img[id*="cover"], img[class*="poster"], img[class*="thumb"], .novel-cover img, .book-cover img, .series-img img, .thumbnail img');
                    if (coverImg && (coverImg.src || coverImg.getAttribute('data-src') || coverImg.getAttribute('data-lazy-src'))) {
                        result.coverUrl = coverImg.src || coverImg.getAttribute('data-src') || coverImg.getAttribute('data-lazy-src') || '';
                    }
                }

                // Synopsis / Description fallback
                var heurDescVal = null;
                if (!result.description) {
                    var descEl = document.querySelector('[class*="synopsis"], [id*="synopsis"], [class*="description"], [id*="description"], [class*="summary"], [id*="summary"], [class*="intro"], [id*="intro"], [class*="story-detail"]');
                    if (descEl && descEl.innerText && descEl.innerText.trim().length > 20) {
                        heurDescVal = descEl.innerText.trim();
                        result.description = heurDescVal;
                        strategyResults.synopsis.winner = "DOM Heuristic (synopsis element: " + descEl.tagName + "." + descEl.className + ")";
                    } else {
                        var paragraphs = document.querySelectorAll('p');
                        var longestP = "";
                        for (var p = 0; p < Math.min(paragraphs.length, 30); p++) {
                            var pText = (paragraphs[p].innerText || '').trim();
                            if (pText.length > longestP.length && pText.length > 50) {
                                longestP = pText;
                            }
                        }
                        if (longestP) {
                            heurDescVal = longestP;
                            result.description = longestP;
                            strategyResults.synopsis.winner = "DOM Heuristic (longest <p> tag, len=" + longestP.length + ")";
                        }
                    }
                }
                strategyResults.synopsis.domHeuristic = heurDescVal;
                diag("[FIELD: SYNOPSIS] [STRATEGY: DOM HEURISTIC]: " + (heurDescVal ? '"' + heurDescVal.substring(0, 80) + '... (len=' + heurDescVal.length + ')"' : "null/empty (existing desc len=" + result.description.length + ")"));

                // Tags / Genres DOM heuristics
                if (result.genres.length === 0) {
                    var tagEls = document.querySelectorAll('[class*="genre"], [class*="tag"], [rel="tag"], a[href*="/genre"], a[href*="/tag"], a[href*="/genres/"], a[href*="/tags/"], a[href*="genre="], a[href*="tag="], .badge, .chip');
                    for (var t = 0; t < tagEls.length; t++) {
                        var tText = (tagEls[t].innerText || '').trim();
                        if (tText && tText.length >= 2 && tText.length <= 30) {
                            if (!/^(?:home|read|novel|chapters|bookmark|login|sign\s*up|menu|search)$/i.test(tText)) {
                                if (result.genres.indexOf(tText) < 0) {
                                    result.genres.push(tText);
                                    strategyResults.tags.domHeuristic.push(tText);
                                    if (strategyResults.tags.winners.indexOf("DOM Heuristic (tag elements)") < 0) strategyResults.tags.winners.push("DOM Heuristic (tag elements)");
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
                                strategyResults.tags.domHeuristic.push(g);
                                if (strategyResults.tags.winners.indexOf("DOM Heuristic (metadata rows)") < 0) strategyResults.tags.winners.push("DOM Heuristic (metadata rows)");
                            }
                        });
                    }
                }
                diag("[FIELD: TAGS] [STRATEGY: DOM HEURISTIC]: " + (strategyResults.tags.domHeuristic.length > 0 ? JSON.stringify(strategyResults.tags.domHeuristic) : "null/empty (existing tags: " + result.genres.length + ")"));

                // Summary of field extraction winners
                diag("---------------- FINAL FIELD VALUES & WINNERS ----------------");
                diag("[FINAL WINNER: TITLE]: strategy=\"" + strategyResults.title.winner + "\", finalValue=\"" + result.title + "\"");
                diag("[FINAL WINNER: AUTHOR]: strategy=\"" + strategyResults.author.winner + "\", finalValue=\"" + result.author + "\"");
                diag("[FINAL WINNER: SYNOPSIS]: strategy=\"" + strategyResults.synopsis.winner + "\", finalValue=\"" + (result.description ? result.description.substring(0, 100) + "... (len=" + result.description.length + ")" : "none") + "\"");
                diag("[FINAL WINNERS: TAGS]: strategies=[" + strategyResults.tags.winners.join(", ") + "], count=" + result.genres.length + ", finalValues=" + JSON.stringify(result.genres));

                // 4. Chapter List Extraction: Comprehensive DOM Scrape
                // If Next.js didn't find chapters or only found a few, inspect DOM thoroughly!
                var chaptersWinningPath = (result.chapters.length > 0) ? ("__NEXT_DATA__ (" + result.chapters.length + " chapters)") : "none";
                var bestContainer = null;
                var bestContainerLinks = [];
                var maxLinksInContainer = 0;
                var sampleExcludedElements = [];
                var candidateContainers = [];

                diag("---------------- CHAPTER LIST EXTRACTION DIAGNOSTICS ----------------");
                diag("[CHAPTERS: INITIAL] Current chapters before DOM inspection: " + result.chapters.length);

                if (result.chapters.length < 5) {
                    diag("[CHAPTERS: DOM INSPECTION] chapters.length < 5, proceeding with DOM container search...");
                    var seenUrls = {};
                    // Pre-populate seenUrls from any already found
                    result.chapters.forEach(function(ch) { seenUrls[ch.url] = true; });

                    // Find all candidate chapter containers
                    candidateContainers = document.querySelectorAll('[class*="chapter-list"], [class*="chapters"], [id*="chapters"], [class*="toc"], .volume, ul.chapter, ol.chapter, [class*="table-of-contents"], [id*="chapter-list"], #list-chapter, .list-chapter, [class*="episode-list"], [id*="episode-list"], .tab-content, .panel-body, #tab-chapters, .chapter-container');

                    diag("[CHAPTERS: CANDIDATE CONTAINERS] Found " + candidateContainers.length + " matching container elements in DOM.");

                    var totalRawLinksAcrossCandidateContainers = 0;
                    var containersExcludedByPreviewFilter = 0;

                    // If candidate containers exist, prefer the ones with the HIGHEST count of links
                    // (prevents picking a small "latest 2-4 chapters" preview box)
                    for (var cc = 0; cc < candidateContainers.length; cc++) {
                        var containerEl = candidateContainers[cc];
                        var cClass = (containerEl.className || '') + ' ' + (containerEl.id || '');
                        var isPreview = /latest|recent|preview|last-release/i.test(cClass);
                        var links = containerEl.querySelectorAll('a[href]');
                        totalRawLinksAcrossCandidateContainers += links.length;

                        diag("[CHAPTER CONTAINER #" + cc + "] tag=" + containerEl.tagName + ", class/id=\"" + cClass.trim() + "\", rawLinksCount=" + links.length + ", matchesPreviewRegex=" + isPreview);

                        if (isPreview && links.length <= 10) {
                            containersExcludedByPreviewFilter++;
                            diag("[CHAPTER CONTAINER #" + cc + "] EXCLUDED by 'avoid preview/latest/recent' filter (links.length=" + links.length + " <= 10)");
                            for (var exEl = 0; exEl < links.length && sampleExcludedElements.length < 5; exEl++) {
                                sampleExcludedElements.push({
                                    text: (links[exEl].innerText || '').trim(),
                                    className: links[exEl].className || '',
                                    href: links[exEl].href || '',
                                    reason: "Container matched /latest|recent|preview/ filter ('" + cClass.trim() + "')"
                                });
                            }
                        }

                        if (links.length > maxLinksInContainer && (!isPreview || links.length > 10)) {
                            maxLinksInContainer = links.length;
                            bestContainerLinks = links;
                            bestContainer = containerEl;
                        }
                    }

                    diag("[CHAPTERS: RAW LINK COUNT BEFORE FILTERING] Total links across candidate containers: " + totalRawLinksAcrossCandidateContainers);
                    diag("[CHAPTERS: CONTAINERS FILTERED] Containers excluded by preview/latest filter: " + containersExcludedByPreviewFilter + " of " + candidateContainers.length);
                    diag("[CHAPTERS: COUNT REMAINING AFTER PREVIEW FILTER] Selected container links remaining: " + maxLinksInContainer);
                    if (bestContainer) {
                        diag("[CHAPTERS: BEST CONTAINER SELECTED] tag=" + bestContainer.tagName + ", class=\"" + bestContainer.className + "\", id=\"" + bestContainer.id + "\", linkCount=" + bestContainerLinks.length);
                    } else {
                        diag("[CHAPTERS: BEST CONTAINER SELECTED] NONE (no candidate container had > 0 valid links)");
                    }

                    // Fallback to all links in document if no large container found
                    var candidateLinkList = (bestContainerLinks.length > 5) ? bestContainerLinks : document.querySelectorAll('a[href]');
                    var linkListSource = (bestContainerLinks.length > 5) ? ("bestContainerLinks (" + bestContainerLinks.length + " links)") : ("global document.querySelectorAll('a[href]') (" + candidateLinkList.length + " links)");
                    diag("[CHAPTERS: LINK SOURCE CHOSEN] " + linkListSource);
                    diag("[CHAPTERS: TOTAL CANDIDATE LINKS TO EVALUATE] " + candidateLinkList.length);

                    var chapterRegex = /(?:chapter|ch[-_.]?|episode|ep[-_.]?)\s*(\d+)/i;
                    var pathRegex = /\/(?:chapter|ch|episode|ep|c|read)[-_/](\d+)/i;
                    var numericEndingRegex = /\/(?:serie-?\d+.*?\/)?(\d+)\/?(?:[?#]|${'$'})/i;

                    var collectedCandidates = [];

                    for (var a = 0; a < candidateLinkList.length; a++) {
                        var anchor = candidateLinkList[a];
                        var href = anchor.href;
                        var aText = (anchor.innerText || anchor.getAttribute('title') || '').trim();

                        if (!href || href.startsWith('javascript:') || href === window.location.href || href === window.location.href + '#' || href.endsWith('#')) {
                            if (sampleExcludedElements.length < 5) {
                                sampleExcludedElements.push({
                                    text: aText,
                                    className: anchor.className || '',
                                    href: href || '',
                                    reason: "Empty, javascript:, or current page hash anchor"
                                });
                            }
                            continue;
                        }

                        // Filter out navigation boilerplate
                        if (/^(?:next\s+chapter|prev(?:ious)?\s+chapter|first\s+chapter|last\s+chapter|latest\s+chapter|all\s+chapters|read\s+now|start\s+reading|table\s+of\s+contents)$/i.test(aText)) {
                            if (sampleExcludedElements.length < 5) {
                                sampleExcludedElements.push({
                                    text: aText,
                                    className: anchor.className || '',
                                    href: href,
                                    reason: "Navigation boilerplate text: '" + aText + "'"
                                });
                            }
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
                            } else {
                                if (sampleExcludedElements.length < 5) {
                                    sampleExcludedElements.push({
                                        text: aText,
                                        className: anchor.className || '',
                                        href: href,
                                        reason: "Duplicate URL already added: " + cleanUrl
                                    });
                                }
                            }
                        } else {
                            if (sampleExcludedElements.length < 5) {
                                sampleExcludedElements.push({
                                    text: aText,
                                    className: anchor.className || '',
                                    href: href,
                                    reason: "Did not match chapter href/text regex and not in accepted container"
                                });
                            }
                        }
                    }

                    diag("[CHAPTERS: DOM CANDIDATES ACCEPTED] Collected " + collectedCandidates.length + " valid chapter candidates from DOM.");

                    // Log sample of what got excluded
                    diag("[CHAPTERS: EXCLUDED SAMPLE] First " + sampleExcludedElements.length + " excluded elements:");
                    for (var sIdx = 0; sIdx < sampleExcludedElements.length; sIdx++) {
                        var sEl = sampleExcludedElements[sIdx];
                        diag("  Excluded #" + (sIdx + 1) + ": text=\"" + sEl.text + "\", class=\"" + sEl.className + "\", href=\"" + sEl.href + "\", reason=\"" + sEl.reason + "\"");
                    }

                    // If collected candidates are more than what Next.js had, replace with the complete list!
                    if (collectedCandidates.length > result.chapters.length) {
                        chaptersWinningPath = (bestContainerLinks.length > 5) ? ("DOM Container (" + (bestContainer ? (bestContainer.tagName + "." + bestContainer.className) : "accepted") + ")") : "DOM Global Links fallback";
                        result.chapters = collectedCandidates;
                    } else if (result.chapters.length > 0) {
                        chaptersWinningPath = "__NEXT_DATA__";
                    } else {
                        chaptersWinningPath = "NONE (0 chapters found)";
                    }
                }

                diag("[CHAPTERS: FINAL WINNER] Winning extraction path: \"" + chaptersWinningPath + "\", total chapters: " + result.chapters.length);

                // Additional Diagnostic: Site structure extraction
                var rawContainerHtml = "none";
                try {
                    var targetContainer = bestContainer;
                    if (!targetContainer && candidateContainers && candidateContainers.length > 0) {
                        targetContainer = candidateContainers[0];
                    }
                    if (!targetContainer) {
                        targetContainer = document.querySelector('[class*="chapter"], [id*="chapter"], ul, ol, main') || document.body;
                    }
                    if (targetContainer) {
                        rawContainerHtml = (targetContainer.outerHTML || '').substring(0, 3000);
                    }
                } catch(e) {
                    rawContainerHtml = "Error capturing container: " + e.toString();
                }

                var rawHeadJsonLdHtml = "none";
                try {
                    var headJsonLdTag = document.head.querySelector('script[type="application/ld+json"]') || document.querySelector('script[type="application/ld+json"]');
                    if (headJsonLdTag) {
                        rawHeadJsonLdHtml = (headJsonLdTag.outerHTML || '').substring(0, 3000);
                    }
                } catch(e) {
                    rawHeadJsonLdHtml = "Error capturing JSON-LD: " + e.toString();
                }

                var nextDataTopKeys = [];
                var nextDataIsPresent = false;
                try {
                    if (typeof window.__NEXT_DATA__ !== 'undefined' && window.__NEXT_DATA__ !== null) {
                        nextDataIsPresent = true;
                        nextDataTopKeys = Object.keys(window.__NEXT_DATA__);
                    }
                } catch(e) {}

                diag("[SITE STRUCTURE] window.__NEXT_DATA__ present: " + nextDataIsPresent + (nextDataIsPresent ? (", keys: [" + nextDataTopKeys.join(", ") + "]") : ""));
                diag("[SITE STRUCTURE] Head JSON-LD present: " + (rawHeadJsonLdHtml !== "none"));
                diag("[SITE STRUCTURE] Chapter container outerHTML length: " + rawContainerHtml.length);

                result._diagnosticLogs = diagLogs;
                result._siteStructureDiag = {
                    nextDataExists: nextDataIsPresent,
                    nextDataKeys: nextDataTopKeys,
                    headJsonLdOuterHtml: rawHeadJsonLdHtml,
                    chapterContainerOuterHtml: rawContainerHtml
                };

                // Clean formatting
                if (result.description) {
                    result.description = result.description.replace(/<[^>]+>/g, ' ').replace(/\s{2,}/g, ' ').trim();
                }
            } catch (err) {
                result.error = err.toString();
                diag("[ERROR] Exception in scraping script: " + err.toString());
                result._diagnosticLogs = diagLogs;
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
