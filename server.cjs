const http = require('http');
const fs = require('fs');
const path = require('path');

// The dev server MUST listen on port 3000 for Nginx reverse proxy
const PORT = 3000;
const HOST = '0.0.0.0';
const ROOT_DIR = __dirname;
const APK_PATH = path.join(ROOT_DIR, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.cjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.apk': 'application/vnd.android.package-archive',
  '.epub': 'application/epub+zip'
};

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  let pathname = decodeURIComponent(parsedUrl.pathname);

  // Health check
  if (pathname === '/health' || pathname === '/_health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'healthy', timestamp: new Date().toISOString() }));
    return;
  }

  // APK download routes
  const isApkRoute = (
    pathname === '/download-apk' ||
    pathname === '/download-apk/' ||
    pathname === '/download/app-debug.apk' ||
    pathname === '/app-debug.apk' ||
    pathname === '/NovelReader-Debug.apk' ||
    pathname === '/novelreader-debug.apk'
  );

  if (isApkRoute) {
    if (fs.existsSync(APK_PATH)) {
      const stat = fs.statSync(APK_PATH);

      if (req.method === 'HEAD') {
        res.writeHead(200, {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Length': stat.size,
          'Content-Disposition': 'attachment; filename=NovelReader-Debug.apk',
          'Content-Transfer-Encoding': 'binary',
          'X-Content-Type-Options': 'nosniff',
          'Cache-Control': 'no-cache, no-store, must-revalidate',
          'Pragma': 'no-cache',
          'Expires': '0',
          'Accept-Ranges': 'bytes'
        });
        res.end();
        return;
      }

      const range = req.headers.range;
      if (range) {
        const parts = range.replace(/bytes=/, '').split('-');
        const start = parseInt(parts[0], 10);
        const end = parts[1] ? parseInt(parts[1], 10) : stat.size - 1;
        const chunksize = (end - start) + 1;

        res.writeHead(206, {
          'Content-Range': `bytes ${start}-${end}/${stat.size}`,
          'Accept-Ranges': 'bytes',
          'Content-Length': chunksize,
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Disposition': 'attachment; filename=NovelReader-Debug.apk',
          'Content-Transfer-Encoding': 'binary',
          'X-Content-Type-Options': 'nosniff',
          'Cache-Control': 'no-cache, no-store, must-revalidate'
        });

        const fileStream = fs.createReadStream(APK_PATH, { start, end });
        fileStream.on('error', (streamErr) => {
          console.error('APK range stream error:', streamErr);
          if (!res.headersSent) {
            res.writeHead(500, { 'Content-Type': 'text/plain' });
            res.end('Error streaming APK file.');
          }
        });
        fileStream.pipe(res);
        return;
      }

      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename=NovelReader-Debug.apk',
        'Content-Transfer-Encoding': 'binary',
        'X-Content-Type-Options': 'nosniff',
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        'Pragma': 'no-cache',
        'Expires': '0',
        'Accept-Ranges': 'bytes'
      });

      const fileStream = fs.createReadStream(APK_PATH);
      fileStream.on('error', (streamErr) => {
        console.error('APK stream error:', streamErr);
        if (!res.headersSent) {
          res.writeHead(500, { 'Content-Type': 'text/plain' });
          res.end('Error streaming APK file.');
        }
      });
      fileStream.pipe(res);
      return;
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('APK build artifact not found at app/build/outputs/apk/debug/app-debug.apk');
      return;
    }
  }

  // Normalize path for web files
  if (pathname === '/') {
    pathname = '/index.html';
  }

  const filePath = path.join(ROOT_DIR, pathname);

  // Security: prevent directory traversal
  if (!filePath.startsWith(ROOT_DIR)) {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    res.end('Forbidden');
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      // Fallback to index.html for client-side navigation
      const indexPath = path.join(ROOT_DIR, 'index.html');
      if (fs.existsSync(indexPath)) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        fs.createReadStream(indexPath).pipe(res);
      } else {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('Not Found');
      }
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    res.writeHead(200, {
      'Content-Type': contentType,
      'Content-Length': stats.size,
      'Cache-Control': 'no-cache'
    });
    fs.createReadStream(filePath).pipe(res);
  });
});

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.log(`Port ${PORT} is already in use; another dev server process is active.`);
  } else {
    console.error('Server error:', err);
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Novel Reader dev server active on http://${HOST}:${PORT}`);
});
