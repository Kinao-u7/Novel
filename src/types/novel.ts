export type ReaderThemeMode = 'light' | 'warm' | 'sepia' | 'dark' | 'amoled';

export type FontType = 'serif' | 'sans' | 'mono' | 'rounded';

export type ReaderTextAlign = 'left' | 'justify' | 'right';

export type ReadingMode = 'continuous' | 'paginated';

export type MarginType = 'compact' | 'normal' | 'wide';

export interface TermReplacementRule {
  id: string;
  original: string;
  replacement: string;
  isEnabled: boolean;
}

export interface ReaderSettings {
  theme: ReaderThemeMode;
  fontSize: number; // in px, e.g. 18
  lineSpacing: number; // e.g. 1.8
  letterSpacing: number; // e.g. 0
  paragraphSpacing: number; // in px, e.g. 16
  textAlign: ReaderTextAlign;
  marginType: MarginType;
  fontType: FontType;
  readingMode: ReadingMode;
  pageSize: number; // paragraphs per page in paginated mode
  autoScrollSpeed: number; // 0 = off, 1-5 = speed
  ttsSpeechRate: number; // 0.5 to 2.0
  ttsPitch: number; // 0.5 to 1.5
  ttsVoice: string | null;
  globalTermReplacementsEnabled: boolean;
}

export interface DownloadSettings {
  simultaneousDownloads: number;
  autoDownloadNext: boolean;
  throttlingEnabled: boolean;
  throttleBatchSize: number;
  throttleDelaySeconds: number;
  throttleRandomJitter: boolean;
  customDownloadFolder: string;
}

export interface Chapter {
  id: string;
  novelId: string;
  title: string;
  chapterIndex: number;
  releaseDate?: string;
  isDownloaded?: boolean;
  isRead?: boolean;
  wordCount?: number;
}

export interface ChapterContent {
  chapterId: string;
  novelId: string;
  title: string;
  paragraphs: string[];
  isDownloaded?: boolean;
}

export interface Novel {
  id: string;
  title: string;
  author: string;
  coverUrl: string;
  description: string;
  genres: string[];
  status: 'Ongoing' | 'Completed' | 'Imported' | 'Hiatus';
  sourceId: string;
  sourceName: string;
  inLibrary: boolean;
  lastReadChapterId?: string;
  lastReadChapterTitle?: string;
  lastReadChapterIndex?: number;
  lastReadScrollPosition?: number;
  totalChapters: number;
  downloadedChaptersCount: number;
  rating: number;
  lastReadTimestamp?: number;
  isEpub?: boolean;
  sourceUrl?: string;
  chapters?: Chapter[];
}

export interface Bookmark {
  id: string;
  novelId: string;
  novelTitle: string;
  chapterId: string;
  chapterTitle: string;
  paragraphIndex: number;
  excerpt: string;
  note: string;
  timestamp: number;
}

export interface ReadingStats {
  todayMinutesRead: number;
  currentStreakDays: number;
  totalChaptersRead: number;
  totalWordsRead: number;
  wordsPerMinute: number;
  lastSessionTimestamp: number;
}

export interface CustomNovelSource {
  id: string;
  name: string;
  url: string;
  language: string;
  description: string;
  isActive: boolean;
}

export interface ScrapedNovelData {
  title: string;
  author: string;
  description: string;
  coverUrl: string;
  sourceUrl: string;
  genres: string[];
  chapters: {
    id: string;
    title: string;
    chapterIndex: number;
    content?: string[];
  }[];
}
