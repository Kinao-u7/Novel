// Novel Reader Client Logic
const NOVELS = [
  {
    id: "wn-shadow-slave",
    title: "Shadow Slave",
    author: "Guiltythree",
    source: "WebNovel Haven",
    cover: "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=500&auto=format&fit=crop&q=80",
    synopsis: "Growing up in poverty, Sunny never expected anything good from life. However, even he did not anticipate being chosen by the Nightmare Spell and awakened to a world of horrors.",
    tags: ["Fantasy", "Action", "LitRPG", "Mystery"],
    status: "Ongoing",
    totalChapters: 1850,
    inLibrary: true,
    chapters: [
      { id: "c1", title: "Chapter 1: The Nightmare Begins", content: [
        "The siren sounded without warning, its sharp, metallic shriek cutting through the quiet hum of the suburban evening.",
        "Sunny blinked, his chopsticks pausing millimeters above a cold bowl of synthetically seasoned nutrient noodles. A split-second of disbelief washed over him before instinct took the wheel.",
        "The Nightmare Spell. It was always there, lurking at the periphery of existence like a hungry shadow, but nobody ever actually believed their own name would be drawn from the cosmic raffle.",
        "A suffocating pressure seized his chest. The room around him warped, walls dissolving into an endless expanse of pitch-black ocean beneath an eclipsed sun.",
        "\"Welcome, Sleeper,\" a whisper echoed directly behind his skull, indifferent and vast as time itself. \"Prepare for your First Nightmare.\""
      ]},
      { id: "c2", title: "Chapter 2: The Temple of Horns", content: [
        "Cold wind stung his face, smelling faintly of ozone and ancient stone.",
        "Sunny stood on the jagged ledge of an impossibly tall mountain. Below him stretched a sea of petrified clouds, silvered by moonlight.",
        "In his hand lay a chipped bronze blade, cold to the touch. The text of his true name flashed dimly across his vision: Lost from Light.",
        "\"A fitting title for someone about to die,\" he muttered under his breath, stepping cautiously toward the gaping maw of the crumbling citadel ahead."
      ]}
    ]
  },
  {
    id: "wn-lord-mysteries",
    title: "Lord of the Mysteries",
    author: "Cuttlefish That Loves Diving",
    source: "WebNovel Haven",
    cover: "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?w=500&auto=format&fit=crop&q=80",
    synopsis: "With the rising tide of steam power and machinery, who can come close to being a Beyonder? Shrouded in the fog of history and darkness, a mystery unfolds.",
    tags: ["Victorian", "Steampunk", "Lovecraftian", "Supernatural"],
    status: "Completed",
    totalChapters: 1432,
    inLibrary: true,
    chapters: [
      { id: "c1", title: "Chapter 1: Crimson", content: [
        "Pain. Blinding, searing pain drilled into the back of his skull like a rusty corkscrew.",
        "Zhou Mingrui gasped, clutching his forehead as he sat up on the rough wooden floor. The smell of gunsmoke and copper filled his nostrils.",
        "Before him was a brass revolver resting beside an ink-stained letter written in strange, flowing script that he inexplicably understood: 'Everyone will die, including me.'",
        "He raised his hand and felt the wetness behind his ear. Crimson blood stained his fingers, yet beneath the wound, his brain pulsed with cold, unnatural vitality.",
        "Looking out the fog-frosted window, he saw gas lamps flickering in the cobblestone alley of a Victorian city that was emphatically not Shanghai."
      ]}
    ]
  },
  {
    id: "cl-pride-prejudice",
    title: "Pride and Prejudice",
    author: "Jane Austen",
    source: "Public Domain Classics",
    cover: "https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c?w=500&auto=format&fit=crop&q=80",
    synopsis: "A classic romantic comedy exploring themes of class, marriage, and misunderstandings between Elizabeth Bennet and the enigmatic Mr. Darcy.",
    tags: ["Classic", "Romance", "Literature", "Historical"],
    status: "Completed",
    totalChapters: 61,
    inLibrary: true,
    chapters: [
      { id: "c1", title: "Chapter 1", content: [
        "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
        "However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered the rightful property of some one or other of their daughters.",
        "\"My dear Mr. Bennet,\" said his lady to him one day, \"have you heard that Netherfield Park is let at last?\"",
        "Mr. Bennet replied that he had not.",
        "\"But it is,\" returned she; \"for Mrs. Long has just been here, and she told me all about it.\""
      ]}
    ]
  },
  {
    id: "cl-frankenstein",
    title: "Frankenstein",
    author: "Mary Shelley",
    source: "Public Domain Classics",
    cover: "https://images.unsplash.com/photo-1512820790803-83ca734da794?w=500&auto=format&fit=crop&q=80",
    synopsis: "The gothic masterpiece telling the tragic story of Victor Frankenstein and the monstrous sentient creature born of his obsessive scientific experiment.",
    tags: ["Gothic", "Sci-Fi", "Horror", "Classic"],
    status: "Completed",
    totalChapters: 24,
    inLibrary: false,
    chapters: [
      { id: "c1", title: "Letter 1", content: [
        "To Mrs. Saville, England. St. Petersburgh, Dec. 11th, 17--.",
        "You will rejoice to hear that no disaster has accompanied the commencement of an enterprise which you have regarded with such evil forebodings.",
        "I arrived here yesterday, and my first task is to assure my dear sister of my welfare and increasing confidence in the success of my undertaking.",
        "I am already far north of London, and as I walk in the streets of Petersburgh, I feel a cold northern breeze play upon my cheeks, which braces my nerves and fills me with delight."
      ]}
    ]
  }
];

// App State
let state = {
  activeTab: 'library',
  activeNovel: null,
  activeChapterIndex: 0,
  readingMode: 'continuous', // 'continuous' | 'paginated'
  currentPage: 0,
  pageSize: 3, // paragraphs per page
  theme: 'theme-light',
  fontSize: 18,
  lineHeight: 1.8,
  fontFamily: 'serif',
  ttsSpeaking: false,
  ttsSpeed: 1.0,
  currentTtsParagraph: -1,
  bookmarks: JSON.parse(localStorage.getItem('nr_bookmarks') || '[]'),
  downloads: JSON.parse(localStorage.getItem('nr_downloads') || '["wn-shadow-slave:c1", "wn-shadow-slave:c2", "wn-lord-mysteries:c1"]')
};

// Initialize
window.addEventListener('DOMContentLoaded', () => {
  renderLibrary();
  renderBrowse();
  renderDownloads();
  renderBookmarks();
});

// Toast Helper
function showToast(msg) {
  const toast = document.getElementById('toast');
  const msgEl = document.getElementById('toast-message');
  if (!toast || !msgEl) return;
  msgEl.textContent = msg;
  toast.classList.remove('translate-y-20', 'opacity-0');
  toast.classList.add('translate-y-0', 'opacity-100');
  setTimeout(() => {
    toast.classList.remove('translate-y-0', 'opacity-100');
    toast.classList.add('translate-y-20', 'opacity-0');
  }, 3000);
}

// Navigation
function switchTab(tab) {
  state.activeTab = tab;
  ['library', 'browse', 'downloads', 'stats', 'novel-detail'].forEach(t => {
    const el = document.getElementById(`view-${t}`);
    if (el) el.classList.add('hidden');
  });

  const activeEl = document.getElementById(`view-${tab}`);
  if (activeEl) activeEl.classList.remove('hidden');

  // Update nav buttons
  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.classList.remove('bg-white', 'dark:bg-zinc-700', 'shadow-sm', 'text-zinc-900', 'dark:text-white');
    btn.classList.add('text-zinc-600', 'dark:text-zinc-300');
  });
  const btn = document.getElementById(`tab-btn-${tab}`);
  if (btn) {
    btn.classList.add('bg-white', 'dark:bg-zinc-700', 'shadow-sm', 'text-zinc-900', 'dark:text-white');
    btn.classList.remove('text-zinc-600', 'dark:text-zinc-300');
  }

  if (tab === 'library') renderLibrary();
  if (tab === 'browse') renderBrowse();
  if (tab === 'downloads') renderDownloads();
  if (tab === 'stats') renderBookmarks();
}

// Render Library
function renderLibrary() {
  const grid = document.getElementById('library-grid');
  if (!grid) return;
  const inLibrary = NOVELS.filter(n => n.inLibrary);
  document.getElementById('library-count-badge').textContent = `${inLibrary.length} Novels`;

  grid.innerHTML = inLibrary.map(n => `
    <div class="bg-white dark:bg-zinc-800/80 border border-zinc-200 dark:border-zinc-700 rounded-2xl p-4 flex gap-4 hover:shadow-md transition">
      <img src="${n.cover}" alt="${n.title}" class="w-24 h-36 object-cover rounded-xl shadow-sm flex-shrink-0" />
      <div class="flex-1 flex flex-col justify-between">
        <div>
          <span class="text-[10px] font-bold uppercase tracking-wider text-amber-600 dark:text-amber-400">${n.source}</span>
          <h3 class="font-display font-bold text-base text-zinc-900 dark:text-white line-clamp-1">${n.title}</h3>
          <p class="text-xs text-zinc-500 font-medium">By ${n.author}</p>
          <div class="mt-2 flex flex-wrap gap-1">
            ${n.tags.slice(0, 2).map(t => `<span class="text-[10px] bg-zinc-100 dark:bg-zinc-700 px-2 py-0.5 rounded-md text-zinc-600 dark:text-zinc-300">${t}</span>`).join('')}
          </div>
        </div>
        <div class="mt-3 flex items-center gap-2">
          <button onclick="openReader('${n.id}', 0)" class="flex-1 py-1.5 px-3 rounded-lg bg-zinc-900 dark:bg-white text-white dark:text-zinc-900 text-xs font-semibold hover:opacity-90 transition">
            Continue Reading
          </button>
          <button onclick="openNovelDetail('${n.id}')" class="p-1.5 rounded-lg border border-zinc-200 dark:border-zinc-700 text-zinc-600 dark:text-zinc-300 hover:bg-zinc-100 dark:hover:bg-zinc-700 transition" title="Details">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>
          </button>
        </div>
      </div>
    </div>
  `).join('');
}

// Render Browse
let currentSourceFilter = 'all';
let searchQuery = '';

function renderBrowse() {
  const grid = document.getElementById('browse-grid');
  if (!grid) return;

  const filtered = NOVELS.filter(n => {
    const matchesSource = currentSourceFilter === 'all' || n.source === currentSourceFilter;
    const matchesQuery = !searchQuery || 
      n.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
      n.author.toLowerCase().includes(searchQuery.toLowerCase()) ||
      n.tags.some(t => t.toLowerCase().includes(searchQuery.toLowerCase()));
    return matchesSource && matchesQuery;
  });

  grid.innerHTML = filtered.map(n => `
    <div class="bg-white dark:bg-zinc-800/80 border border-zinc-200 dark:border-zinc-700 rounded-2xl overflow-hidden flex flex-col hover:shadow-lg transition">
      <div class="h-44 relative overflow-hidden bg-zinc-100 dark:bg-zinc-900">
        <img src="${n.cover}" alt="${n.title}" class="w-full h-full object-cover" />
        <span class="absolute top-2.5 left-2.5 px-2 py-0.5 text-[10px] font-bold rounded bg-black/60 backdrop-blur text-white">
          ${n.source}
        </span>
      </div>
      <div class="p-4 flex-1 flex flex-col justify-between">
        <div>
          <h3 class="font-display font-bold text-base text-zinc-900 dark:text-white line-clamp-1">${n.title}</h3>
          <p class="text-xs text-zinc-500 font-medium">By ${n.author}</p>
          <p class="text-xs text-zinc-600 dark:text-zinc-300 line-clamp-2 mt-2 leading-relaxed">${n.synopsis}</p>
        </div>
        <div class="mt-4 pt-3 border-t border-zinc-100 dark:border-zinc-700/60 flex items-center justify-between">
          <span class="text-xs text-zinc-400 font-medium">${n.totalChapters} Chapters</span>
          <button onclick="openNovelDetail('${n.id}')" class="px-3 py-1.5 rounded-lg bg-zinc-900 dark:bg-white text-white dark:text-zinc-900 text-xs font-semibold hover:opacity-90 transition">
            View Chapters
          </button>
        </div>
      </div>
    </div>
  `).join('');
}

function filterNovels(q) {
  searchQuery = q;
  renderBrowse();
}

function setSourceFilter(src) {
  currentSourceFilter = src;
  document.querySelectorAll('.source-filter-btn').forEach(btn => {
    if (btn.dataset.source === src) {
      btn.className = "source-filter-btn px-3 py-1 text-xs rounded-full bg-zinc-900 text-white dark:bg-white dark:text-zinc-900 font-medium transition";
    } else {
      btn.className = "source-filter-btn px-3 py-1 text-xs rounded-full bg-zinc-100 hover:bg-zinc-200 dark:bg-zinc-800 text-zinc-700 dark:text-zinc-300 font-medium transition";
    }
  });
  renderBrowse();
}

// Novel Detail View
function openNovelDetail(novelId) {
  const novel = NOVELS.find(n => n.id === novelId);
  if (!novel) return;
  state.activeNovel = novel;

  const card = document.getElementById('novel-detail-card');
  card.innerHTML = `
    <div class="flex flex-col md:flex-row gap-6">
      <img src="${novel.cover}" alt="${novel.title}" class="w-40 h-56 object-cover rounded-2xl shadow-md flex-shrink-0" />
      <div class="flex-1 flex flex-col justify-between">
        <div>
          <div class="flex items-center gap-2">
            <span class="px-2.5 py-0.5 text-xs font-semibold rounded-full bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300">${novel.source}</span>
            <span class="px-2.5 py-0.5 text-xs font-semibold rounded-full bg-zinc-100 text-zinc-700 dark:bg-zinc-700 dark:text-zinc-300">${novel.status}</span>
          </div>
          <h2 class="text-3xl font-display font-bold text-zinc-900 dark:text-white mt-2">${novel.title}</h2>
          <p class="text-sm text-zinc-500 font-medium">By ${novel.author}</p>
          <p class="text-sm text-zinc-600 dark:text-zinc-300 mt-3 leading-relaxed">${novel.synopsis}</p>
          <div class="mt-4 flex flex-wrap gap-1.5">
            ${novel.tags.map(t => `<span class="text-xs bg-zinc-100 dark:bg-zinc-700/80 px-2.5 py-1 rounded-md text-zinc-700 dark:text-zinc-300">${t}</span>`).join('')}
          </div>
        </div>

        <div class="mt-6 flex flex-wrap gap-3">
          <button onclick="toggleLibraryNovel('${novel.id}')" class="px-4 py-2 rounded-xl text-xs font-bold border border-zinc-300 dark:border-zinc-700 hover:bg-zinc-100 dark:hover:bg-zinc-700 transition">
            ${novel.inLibrary ? '✓ In Library' : '+ Add to Library'}
          </button>
          <button onclick="downloadAllChapters('${novel.id}')" class="px-4 py-2 rounded-xl text-xs font-bold bg-zinc-100 dark:bg-zinc-700 hover:bg-zinc-200 dark:hover:bg-zinc-600 transition flex items-center gap-1.5">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>
            Batch Download All
          </button>
          <button onclick="openReader('${novel.id}', 0)" class="px-5 py-2 rounded-xl text-xs font-bold bg-zinc-900 dark:bg-white text-white dark:text-zinc-900 hover:opacity-90 transition">
            Start Reading
          </button>
        </div>
      </div>
    </div>

    <!-- Chapter List -->
    <div class="mt-10 border-t border-zinc-200 dark:border-zinc-700 pt-6">
      <div class="flex items-center justify-between mb-4">
        <h3 class="font-display font-bold text-lg text-zinc-900 dark:text-white">Chapters (${novel.chapters.length})</h3>
        <span class="text-xs text-zinc-400">Offline ready</span>
      </div>
      <div class="space-y-2">
        ${novel.chapters.map((ch, idx) => `
          <div class="flex items-center justify-between p-3.5 rounded-xl hover:bg-zinc-50 dark:hover:bg-zinc-700/50 border border-transparent hover:border-zinc-200 dark:hover:border-zinc-700 transition cursor-pointer" onclick="openReader('${novel.id}', ${idx})">
            <div>
              <p class="font-medium text-sm text-zinc-900 dark:text-white">${ch.title}</p>
              <p class="text-xs text-zinc-400 font-mono mt-0.5">${ch.content.length} paragraphs</p>
            </div>
            <div class="flex items-center gap-2">
              <span class="text-xs text-emerald-600 font-medium">Available</span>
              <svg class="w-4 h-4 text-zinc-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/></svg>
            </div>
          </div>
        `).join('')}
      </div>
    </div>
  `;

  switchTab('novel-detail');
}

function toggleLibraryNovel(id) {
  const n = NOVELS.find(x => x.id === id);
  if (!n) return;
  n.inLibrary = !n.inLibrary;
  showToast(n.inLibrary ? 'Added to Library' : 'Removed from Library');
  openNovelDetail(id);
}

function downloadAllChapters(id) {
  const n = NOVELS.find(x => x.id === id);
  if (!n) return;
  n.chapters.forEach(ch => {
    const key = `${id}:${ch.id}`;
    if (!state.downloads.includes(key)) {
      state.downloads.push(key);
    }
  });
  localStorage.setItem('nr_downloads', JSON.stringify(state.downloads));
  showToast(`Downloaded all chapters of ${n.title} for offline reading!`);
}

// 4. READER ENGINE
function openReader(novelId, chapterIdx) {
  const novel = NOVELS.find(n => n.id === novelId);
  if (!novel) return;
  state.activeNovel = novel;
  state.activeChapterIndex = chapterIdx;
  state.currentPage = 0;

  document.getElementById('view-reader').classList.remove('hidden');
  renderReaderChapter();
}

function exitReader() {
  stopTtsPlayback();
  document.getElementById('view-reader').classList.add('hidden');
}

function renderReaderChapter() {
  const novel = state.activeNovel;
  const chapter = novel.chapters[state.activeChapterIndex];

  document.getElementById('reader-title').textContent = novel.title;
  document.getElementById('reader-chapter-title').textContent = chapter.title;

  const proseContainer = document.getElementById('reader-prose');
  const paginatedControls = document.getElementById('paginated-controls');

  if (state.readingMode === 'continuous') {
    paginatedControls.classList.add('hidden');
    proseContainer.innerHTML = chapter.content.map((p, idx) => `
      <p id="para-${idx}" class="reader-p transition leading-relaxed">${p}</p>
    `).join('');
  } else {
    // Paginated Mode
    paginatedControls.classList.remove('hidden');
    const totalPages = Math.ceil(chapter.content.length / state.pageSize);
    document.getElementById('page-indicator').textContent = `Page ${state.currentPage + 1} of ${totalPages}`;

    const start = state.currentPage * state.pageSize;
    const pageParagraphs = chapter.content.slice(start, start + state.pageSize);
    proseContainer.innerHTML = pageParagraphs.map((p, idx) => `
      <p id="para-${start + idx}" class="reader-p transition leading-relaxed">${p}</p>
    `).join('');
  }

  // Update chapter nav
  document.getElementById('btn-prev-chapter').disabled = state.activeChapterIndex === 0;
  document.getElementById('btn-next-chapter').disabled = state.activeChapterIndex >= novel.chapters.length - 1;
}

function navigateChapter(delta) {
  stopTtsPlayback();
  const nextIdx = state.activeChapterIndex + delta;
  if (nextIdx >= 0 && nextIdx < state.activeNovel.chapters.length) {
    state.activeChapterIndex = nextIdx;
    state.currentPage = 0;
    renderReaderChapter();
    document.getElementById('reader-body-container').scrollTop = 0;
  }
}

function prevPage() {
  if (state.currentPage > 0) {
    state.currentPage--;
    renderReaderChapter();
    document.getElementById('reader-body-container').scrollTop = 0;
  }
}

function nextPage() {
  const chapter = state.activeNovel.chapters[state.activeChapterIndex];
  const totalPages = Math.ceil(chapter.content.length / state.pageSize);
  if (state.currentPage < totalPages - 1) {
    state.currentPage++;
    renderReaderChapter();
    document.getElementById('reader-body-container').scrollTop = 0;
  }
}

// Appearance Settings
function toggleReaderSettings() {
  const drawer = document.getElementById('reader-settings-drawer');
  drawer.classList.toggle('hidden');
}

function setTheme(theme) {
  state.theme = theme;
  document.body.className = `${theme} antialiased min-h-screen flex flex-col transition-colors duration-200`;
}

function setReadingMode(mode) {
  state.readingMode = mode;
  state.currentPage = 0;
  document.getElementById('btn-mode-continuous').className = mode === 'continuous' ? 
    "py-1.5 rounded-lg border text-xs font-medium bg-zinc-900 text-white dark:bg-white dark:text-zinc-900" :
    "py-1.5 rounded-lg border text-xs font-medium bg-zinc-100 text-zinc-800 dark:bg-zinc-800 dark:text-zinc-200 border-zinc-200 dark:border-zinc-700";

  document.getElementById('btn-mode-paginated').className = mode === 'paginated' ? 
    "py-1.5 rounded-lg border text-xs font-medium bg-zinc-900 text-white dark:bg-white dark:text-zinc-900" :
    "py-1.5 rounded-lg border text-xs font-medium bg-zinc-100 text-zinc-800 dark:bg-zinc-800 dark:text-zinc-200 border-zinc-200 dark:border-zinc-700";

  renderReaderChapter();
}

function setFontSize(val) {
  state.fontSize = val;
  document.getElementById('font-size-label').textContent = `${val}px`;
  document.getElementById('reader-prose').style.fontSize = `${val}px`;
}

function setLineHeight(val) {
  state.lineHeight = val;
  document.getElementById('line-height-label').textContent = val;
  document.getElementById('reader-prose').style.lineHeight = val;
}

function setFontFamily(font) {
  state.fontFamily = font;
  const el = document.getElementById('reader-prose');
  el.classList.remove('font-serif', 'font-sans', 'font-mono');
  el.classList.add(`font-${font}`);
}

// TTS Audio Player
let synth = window.speechSynthesis;
let currentUtterance = null;

function toggleTtsPlayback() {
  if (state.ttsSpeaking) {
    stopTtsPlayback();
  } else {
    startTtsPlayback();
  }
}

function startTtsPlayback() {
  if (!synth) {
    showToast('Speech synthesis is not supported on this device');
    return;
  }
  synth.cancel();
  state.ttsSpeaking = true;
  document.getElementById('tts-control-bar').classList.remove('hidden');

  const chapter = state.activeNovel.chapters[state.activeChapterIndex];
  playParagraphTts(chapter, 0);
}

function playParagraphTts(chapter, pIdx) {
  if (!state.ttsSpeaking || pIdx >= chapter.content.length) {
    stopTtsPlayback();
    return;
  }
  state.currentTtsParagraph = pIdx;

  // Highlight paragraph in DOM
  document.querySelectorAll('.reader-p').forEach(p => p.classList.remove('highlight-tts'));
  const pEl = document.getElementById(`para-${pIdx}`);
  if (pEl) {
    pEl.classList.add('highlight-tts');
    pEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  const text = chapter.content[pIdx];
  currentUtterance = new SpeechSynthesisUtterance(text);
  currentUtterance.rate = state.ttsSpeed;
  currentUtterance.onend = () => {
    if (state.ttsSpeaking) {
      playParagraphTts(chapter, pIdx + 1);
    }
  };
  currentUtterance.onerror = () => {
    stopTtsPlayback();
  };
  synth.speak(currentUtterance);
}

function stopTtsPlayback() {
  state.ttsSpeaking = false;
  state.currentTtsParagraph = -1;
  if (synth) synth.cancel();
  document.getElementById('tts-control-bar').classList.add('hidden');
  document.querySelectorAll('.reader-p').forEach(p => p.classList.remove('highlight-tts'));
}

function changeTtsSpeed(delta) {
  state.ttsSpeed = Math.max(0.5, Math.min(2.5, state.ttsSpeed + delta));
  document.getElementById('tts-speed-label').textContent = `${state.ttsSpeed.toFixed(2)}x`;
  if (state.ttsSpeaking) {
    const pIdx = state.currentTtsParagraph;
    const chapter = state.activeNovel.chapters[state.activeChapterIndex];
    if (synth) synth.cancel();
    playParagraphTts(chapter, pIdx);
  }
}

// Bookmarking
function bookmarkCurrentPosition() {
  const novel = state.activeNovel;
  const chapter = novel.chapters[state.activeChapterIndex];
  const bm = {
    id: Date.now().toString(),
    novelId: novel.id,
    novelTitle: novel.title,
    chapterIndex: state.activeChapterIndex,
    chapterTitle: chapter.title,
    preview: chapter.content[0].slice(0, 80) + "...",
    timestamp: new Date().toLocaleDateString()
  };
  state.bookmarks.unshift(bm);
  localStorage.setItem('nr_bookmarks', JSON.stringify(state.bookmarks));
  showToast(`Bookmarked ${chapter.title}`);
}

// Downloads View
function renderDownloads() {
  const list = document.getElementById('downloads-list');
  if (!list) return;

  list.innerHTML = state.downloads.map(d => {
    const [nId, cId] = d.split(':');
    const novel = NOVELS.find(n => n.id === nId);
    return `
      <div class="flex items-center justify-between p-4 rounded-xl bg-white dark:bg-zinc-800/70 border border-zinc-200 dark:border-zinc-700">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 rounded-lg bg-emerald-100 dark:bg-emerald-900/40 text-emerald-600 flex items-center justify-center font-bold text-xs">
            ✓
          </div>
          <div>
            <p class="font-bold text-sm text-zinc-900 dark:text-white">${novel ? novel.title : 'Novel'} - ${cId.toUpperCase()}</p>
            <p class="text-xs text-zinc-400">Offline JSON cache (~45 KB)</p>
          </div>
        </div>
        <span class="text-xs font-semibold text-emerald-600">Cached on Disk</span>
      </div>
    `;
  }).join('');
}

function clearAllDownloads() {
  state.downloads = [];
  localStorage.setItem('nr_downloads', '[]');
  document.getElementById('stat-storage-size').textContent = "0 MB";
  document.getElementById('stat-cached-chapters').textContent = "0";
  renderDownloads();
  showToast('Offline cache cleared');
}

// Stats & Bookmarks
function renderBookmarks() {
  const container = document.getElementById('bookmarks-list');
  if (!container) return;
  if (state.bookmarks.length === 0) {
    container.innerHTML = `<p class="text-xs text-zinc-400 italic">No bookmarks created yet. Tap the bookmark icon in the reader to save key moments.</p>`;
    return;
  }
  container.innerHTML = state.bookmarks.map(bm => `
    <div class="p-3 rounded-xl bg-zinc-50 dark:bg-zinc-800 border border-zinc-200 dark:border-zinc-700 flex items-center justify-between">
      <div>
        <p class="font-semibold text-zinc-900 dark:text-white">${bm.novelTitle} - ${bm.chapterTitle}</p>
        <p class="text-xs text-zinc-500 mt-0.5 italic">"${bm.preview}"</p>
      </div>
      <button onclick="openReader('${bm.novelId}', ${bm.chapterIndex})" class="px-3 py-1 rounded-lg text-xs font-semibold bg-zinc-900 text-white dark:bg-white dark:text-zinc-900">
        Jump
      </button>
    </div>
  `).join('');
}

// EPUB File Importer
function triggerEpubImport() {
  document.getElementById('epub-file-input').click();
}

function handleEpubFile(event) {
  const file = event.target.files[0];
  if (!file) return;

  showToast(`Parsing EPUB: ${file.name}...`);
  // Create novel entry for imported EPUB
  const importedId = `epub-${Date.now()}`;
  const importedNovel = {
    id: importedId,
    title: file.name.replace(/\.epub$/i, ''),
    author: "Imported from Device",
    source: "Local EPUB",
    cover: "https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c?w=500&auto=format&fit=crop&q=80",
    synopsis: "Locally imported EPUB document parsed and formatted for distraction-free reading.",
    tags: ["EPUB", "Local", "E-Book"],
    status: "Imported",
    totalChapters: 3,
    inLibrary: true,
    chapters: [
      {
        id: "c1",
        title: "Chapter 1: Opening",
        content: [
          `Document successfully imported from: ${file.name}.`,
          "All metadata and chapter contents have been mapped to the native reader engine.",
          "You can now customize font families, change themes, adjust margins, or enable text-to-speech audio narration."
        ]
      }
    ]
  };

  NOVELS.unshift(importedNovel);
  renderLibrary();
  showToast(`EPUB imported into Library!`);
  openNovelDetail(importedId);
}
