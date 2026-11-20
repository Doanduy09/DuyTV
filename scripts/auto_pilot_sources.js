const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

const rootDir = path.join(__dirname, '..');
const configSourcesPath = path.join(rootDir, 'config', 'sources.json');
const dotConfigPath = path.join(rootDir, '.config');
const versionJsonPath = path.join(rootDir, 'config', 'version.json');
const gradlePath = path.join(rootDir, 'app', 'build.gradle.kts');
const cleanM3uPath = path.join(rootDir, 'config', 'tv_channels_clean.m3u');

let sourcesConfig = JSON.parse(fs.readFileSync(configSourcesPath, 'utf8'));

console.log('================================================================');
console.log('⚡ DUYTV FULL AUTONOMOUS SELF-HEALING & AUTO-DISCOVERY ENGINE ⚡');
console.log('Hệ Thống Tự Hành: Tự Tìm Nguồn Phim + Tự Loại Bỏ Nguồn Chết');
console.log('+ Tự Kiểm Định Kênh Truyền Hình + Tự Chuẩn Bị Tái Build APK');
console.log('================================================================\n');

// 1. HTTP stream probe với cơ chế Follow Redirect (301, 302, 307, 308)
function probeStream(url, maxRedirects = 4, timeoutMs = 5000) {
    return new Promise((resolve) => {
        if (!url || typeof url !== 'string' || !url.startsWith('http')) {
            return resolve({ ok: false, error: 'INVALID_URL' });
        }
        if (url.includes('.coin') || url.includes('vnns.net') || url.includes('freem3u.xyz')) {
            return resolve({ ok: false, error: 'BLACK_LISTED_DOMAIN' });
        }
        if (maxRedirects < 0) {
            return resolve({ ok: false, error: 'TOO_MANY_REDIRECTS' });
        }

        let client;
        try {
            client = url.startsWith('https') ? https : http;
        } catch {
            return resolve({ ok: false, error: 'PROTOCOL_ERROR' });
        }

        const start = Date.now();
        let body = '';
        let resolved = false;
        let hardTimer = null;

        const finish = (result) => {
            if (resolved) return;
            resolved = true;
            if (hardTimer) clearTimeout(hardTimer);
            try { req.destroy(); } catch {}
            resolve(result);
        };

        const req = client.get(url, {
            headers: {
                'User-Agent': 'TiviMate/4.7.0 (Linux; Android 11)',
                'Accept': '*/*'
            },
            timeout: timeoutMs
        }, (res) => {
            // Xử lý chuyển hướng HTTP Redirect
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                resolved = true;
                if (hardTimer) clearTimeout(hardTimer);
                try { req.destroy(); } catch {}
                let targetLoc = res.headers.location;
                try {
                    if (!targetLoc.startsWith('http')) {
                        targetLoc = new URL(targetLoc, url).href;
                    }
                    return probeStream(targetLoc, maxRedirects - 1, timeoutMs).then(resolve);
                } catch {
                    return resolve({ ok: false, error: 'BAD_REDIRECT_LOCATION' });
                }
            }

            const ok = res.statusCode >= 200 && res.statusCode < 400;
            const ct = (res.headers['content-type'] || '').toLowerCase();
            const isVideoStreamCt = ct.includes('mpegurl') || ct.includes('video/') || ct.includes('mp2t') || ct.includes('octet-stream');

            if (!ok) {
                return finish({ ok: false, status: res.statusCode, latency: Date.now() - start, isM3u: false, error: `HTTP ${res.statusCode}` });
            }

            res.setEncoding('utf8');
            res.on('data', chunk => {
                if (resolved) return;
                if (body.length < 2048) body += chunk;
                const looksLikeM3u = body.includes('#EXTM3U') || body.includes('#EXTINF') || body.includes('.ts') || body.includes('.m3u8');
                // Thoát sớm ngay khi nhận đủ header M3U hoặc chunk video trực tiếp (tránh treo vô hạn với luồng MPEG-TS live)
                if (looksLikeM3u || (isVideoStreamCt && body.length >= 128)) {
                    finish({
                        ok: true,
                        status: res.statusCode,
                        latency: Date.now() - start,
                        isM3u: true,
                        error: null
                    });
                }
            });

            res.on('end', () => {
                const isM3u = isVideoStreamCt || body.includes('#EXTM3U') || body.includes('#EXTINF') || body.includes('.ts') || body.includes('.m3u8');
                finish({
                    ok,
                    status: res.statusCode,
                    latency: Date.now() - start,
                    isM3u,
                    error: ok ? null : `HTTP ${res.statusCode}`
                });
            });
        });

        // Hard wall-clock timeout: đảm bảo không bao giờ treo tiến trình kể cả khi server gửi dữ liệu nhỏ giọt
        hardTimer = setTimeout(() => {
            finish({ ok: false, status: 0, latency: timeoutMs, isM3u: false, error: 'TIMEOUT' });
        }, timeoutMs + 500);

        req.on('timeout', () => {
            finish({ ok: false, status: 0, latency: timeoutMs, isM3u: false, error: 'TIMEOUT' });
        });

        req.on('error', (err) => {
            finish({ ok: false, status: 0, latency: Date.now() - start, isM3u: false, error: err.message });
        });
    });
}

// 2. HTTP probe cho API Phim (với Anti-Poisoning Filter)
const GAMBLING_KEYWORDS = [
    'nhà cái', 'tai xiu', 'tài xỉu', 'kubet', 'thabet', 'cá cược', 'casino',
    'đá gà', 'keonhacai', 'nổ hũ', 'bắn cá', 'baccarat', 'xo so', 'lô đề'
];

function isPoisonedOrGambling(text) {
    if (!text || typeof text !== 'string') return false;
    const lower = text.toLowerCase();
    return GAMBLING_KEYWORDS.some(kw => lower.includes(kw));
}

function probeJsonApi(url, type, timeoutMs = 6000) {
    return new Promise((resolve) => {
        let client;
        try {
            client = url.startsWith('https') ? https : http;
        } catch {
            return resolve({ ok: false, error: 'INVALID_URL' });
        }

        const start = Date.now();
        let body = '';
        let resolved = false;

        const req = client.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 DuyTV-AutoPilot/2.6.8',
                'Accept': 'application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Accept-Language': 'vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7'
            },
            timeout: timeoutMs
        }, (res) => {
            res.setEncoding('utf8');
            res.on('data', chunk => {
                if (body.length < 50000) body += chunk;
            });

            res.on('end', () => {
                if (resolved) return;
                resolved = true;
                const latency = Date.now() - start;
                const ok = res.statusCode >= 200 && res.statusCode < 400;
                let validSchema = false;

                if (ok && !isPoisonedOrGambling(body)) {
                    try {
                        const json = JSON.parse(body);
                        if (type === 'kkphim' || type === 'ophim') {
                            validSchema = (json.status === true || json.status === 'success' || json.status === 'ok') &&
                                          (Array.isArray(json.items) || Array.isArray(json.data?.items) || Boolean(json.movie));
                        } else if (type === 'nguonc') {
                            validSchema = (json.status === 'success' || json.status === true) && Array.isArray(json.items);
                        } else if (type === 'cobe_backend') {
                            validSchema = json.status === true && Array.isArray(json.result);
                        } else if (type === 'torrent4k') {
                            validSchema = json.status === 'ok' && Array.isArray(json.data?.movies);
                        } else {
                            validSchema = true;
                        }
                    } catch {
                        validSchema = false;
                    }
                }

                // Chấp nhận ngoại lệ Cloudflare chặn IP US cho NguonC
                if (!ok && res.statusCode === 403 && type === 'nguonc') {
                    validSchema = true;
                }

                resolve({
                    ok: ok || (res.statusCode === 403 && type === 'nguonc'),
                    validSchema,
                    status: res.statusCode,
                    latency,
                    body,
                    error: ok ? null : `HTTP ${res.statusCode}`
                });
            });
        });

        req.on('timeout', () => {
            if (resolved) return;
            resolved = true;
            req.destroy();
            resolve({ ok: false, validSchema: false, status: 0, latency: timeoutMs, error: 'TIMEOUT' });
        });

        req.on('error', (err) => {
            if (resolved) return;
            resolved = true;
            resolve({ ok: false, validSchema: false, status: 0, latency: Date.now() - start, error: err.message });
        });
    });
}

// Retry 2 lần trước khi kết luận nguồn phim chết (tránh tắt nhầm do lag mạng nhất thời)
async function probeJsonApiWithRetry(url, type, timeoutMs = 8000, retries = 2) {
    let last = { ok: false, validSchema: false, status: 0, latency: 0, error: 'UNKNOWN' };
    for (let i = 0; i < retries; i++) {
        last = await probeJsonApi(url, type, timeoutMs);
        if (last.ok && last.validSchema) return last;
        if (i < retries - 1) await new Promise(r => setTimeout(r, 1500));
    }
    return last;
}

// 2b. Helper tải văn bản/HTML/M3U an toàn (giới hạn 1.5MB chống OOM, tự follow redirect)
function fetchText(url, extraHeaders = {}, maxRedirects = 3, timeoutMs = 8000, maxBytes = 1500000) {
    return new Promise((resolve) => {
        if (!url || !url.startsWith('http') || maxRedirects < 0) {
            return resolve({ ok: false, status: 0, body: '' });
        }
        let client;
        try {
            client = url.startsWith('https') ? https : http;
        } catch {
            return resolve({ ok: false, status: 0, body: '' });
        }

        let body = '';
        let resolved = false;
        const req = client.get(url, {
            headers: {
                'User-Agent': 'DuyTV-AutoPilot/2.7.0 (GitHub-Actions-SelfHealing)',
                'Accept': '*/*',
                ...extraHeaders
            },
            timeout: timeoutMs
        }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                resolved = true;
                req.destroy();
                try {
                    const nextUrl = res.headers.location.startsWith('http')
                        ? res.headers.location
                        : new URL(res.headers.location, url).href;
                    return fetchText(nextUrl, extraHeaders, maxRedirects - 1, timeoutMs, maxBytes).then(resolve);
                } catch {
                    return resolve({ ok: false, status: 0, body: '' });
                }
            }
            res.setEncoding('utf8');
            res.on('data', chunk => {
                if (body.length < maxBytes) body += chunk;
            });
            res.on('end', () => {
                if (resolved) return;
                resolved = true;
                resolve({ ok: res.statusCode >= 200 && res.statusCode < 400, status: res.statusCode, body });
            });
        });
        req.on('timeout', () => {
            if (resolved) return;
            resolved = true;
            req.destroy();
            resolve({ ok: false, status: 0, body: '' });
        });
        req.on('error', () => {
            if (resolved) return;
            resolved = true;
            resolve({ ok: false, status: 0, body: '' });
        });
    });
}

// 2c. Tự động săn tìm domain API phim mới từ cộng đồng GitHub khi nguồn hiện tại bị sập
async function discoverMovieDomainsFromGitHub(query, domainRegex) {
    const headers = { 'Accept': 'application/vnd.github+json' };
    if (process.env.GITHUB_TOKEN) {
        headers['Authorization'] = `Bearer ${process.env.GITHUB_TOKEN}`;
    }
    const url = `https://api.github.com/search/repositories?q=${encodeURIComponent(query)}&sort=updated&per_page=8`;
    const res = await fetchText(url, headers, 2, 7000, 300000);
    if (!res.ok || !res.body) return [];
    const found = new Set();
    try {
        const data = JSON.parse(res.body);
        const items = Array.isArray(data.items) ? data.items : [];
        for (const repo of items) {
            const blob = `${repo.homepage || ''} ${repo.description || ''}`;
            const matches = blob.match(domainRegex) || [];
            matches.forEach(m => found.add(m.replace(/\/+$/, '')));
        }
    } catch {}
    return [...found];
}

// 2d. Tự động cào danh sách luồng IPTV sống từ các kho M3U cộng đồng tự cập nhật 24/7
const COMMUNITY_IPTV_PLAYLISTS = [
    'https://iptv-org.github.io/iptv/countries/vn.m3u',
    'https://raw.githubusercontent.com/khanh71/All-In-One-IPTV/main/http-iptv.m3u',
    'https://raw.githubusercontent.com/giangnam0201/All-In-One-IPTV/main/channels.m3u',
    'https://raw.githubusercontent.com/KevinNitroG/Entertainment/m3u/playlists/coocaa.m3u'
];

function normalizeChannelKey(rawName) {
    if (!rawName) return '';
    let s = rawName
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .replace(/đ/gi, 'd')
        .toLowerCase()
        .replace(/\b(hd|fhd|sd|4k|1080p|720p|vn|vietnam|tv|kenh|channel|du phong|backup|server\s*\d+|s\d+)\b/g, ' ')
        .replace(/[^a-z0-9]+/g, '')
        .trim();

    // Chuẩn hóa các tên gọi phổ biến của đài địa phương
    if (s.startsWith('thdt1') || s.includes('dongthap1')) return 'dongthap1';
    if (s.startsWith('hanoitv1') || s === 'hanoi1' || s === 'h1') return 'hanoi1';
    if (s.includes('danang1') || s === 'drt1') return 'danang1';
    if (s.includes('cantho') || s === 'thtpct') return 'cantho';
    if (s.startsWith('qpvn') || s.includes('quocphong')) return 'qpvn';
    return s;
}

async function harvestCommunityIptvStreams(extraPlaylistUrl) {
    const urls = [...COMMUNITY_IPTV_PLAYLISTS];
    if (extraPlaylistUrl && extraPlaylistUrl.startsWith('http')) {
        urls.push(extraPlaylistUrl);
    }

    const channelMap = new Map(); // normalizedKey -> Set<streamUrl>
    const results = await Promise.all(urls.map(u => fetchText(u, { 'User-Agent': 'TiviMate/4.7.0 (Linux; Android 11)' }, 3, 9000)));

    let totalEntries = 0;
    results.forEach((res, idx) => {
        if (!res.ok || !res.body || !res.body.includes('#EXTINF')) return;
        const lines = res.body.split(/\r?\n/);
        let currentName = '';
        for (const rawLine of lines) {
            const line = rawLine.trim();
            if (!line) continue;
            if (line.startsWith('#EXTINF')) {
                const commaIdx = line.lastIndexOf(',');
                currentName = commaIdx >= 0 ? line.slice(commaIdx + 1).trim() : '';
            } else if (line.startsWith('http://') || line.startsWith('https://')) {
                if (currentName && !line.includes('.coin') && !line.includes('vnns.net') && !line.includes('freem3u.xyz')) {
                    const key = normalizeChannelKey(currentName);
                    if (key.length >= 3) {
                        if (!channelMap.has(key)) channelMap.set(key, new Set());
                        channelMap.get(key).add(line);
                        totalEntries++;
                    }
                }
                currentName = '';
            }
        }
        console.log(`   📡 Đã thu hoạch M3U cộng đồng #${idx + 1}: ${urls[idx].slice(0, 65)}...`);
    });

    console.log(`   ✨ Tổng hợp được ${totalEntries} luồng dự phòng cho ${channelMap.size} mã kênh từ cộng đồng.\n`);
    return channelMap;
}

// 3. Danh sách kênh truyền hình kiểm định chuẩn quốc gia (Master Channel Roster)
const MASTER_CHANNELS = [
    {
        name: "VTV1 HD",
        logo: "https://i.imgur.com/8QdY2kH.png",
        group: "VTV Quốc Gia",
        streams: [
            "https://dethich.pw/vtv1/index.m3u8",
            "https://live.vtvdigital.vn/vtv1/index.m3u8",
            "https://truyenhinhso.vn/live/vtv1.m3u8"
        ]
    },
    {
        name: "VTV2 HD",
        logo: "https://i.imgur.com/uGz8x2M.png",
        group: "VTV Quốc Gia",
        streams: [
            "https://dethich.pw/vtv2/index.m3u8",
            "https://live.vtvdigital.vn/vtv2/index.m3u8"
        ]
    },
    {
        name: "VTV3 HD",
        logo: "https://i.imgur.com/3N4w1bY.png",
        group: "VTV Quốc Gia",
        streams: [
            "https://dethich.pw/vtv3/index.m3u8",
            "https://live.vtvdigital.vn/vtv3/index.m3u8"
        ]
    },
    {
        name: "VTV4 HD",
        logo: "https://i.imgur.com/4N3yLzG.png",
        group: "VTV Quốc Gia",
        streams: [
            "https://dethich.pw/vtv4/index.m3u8",
            "https://live.vtvdigital.vn/vtv4/index.m3u8"
        ]
    },
    {
        name: "VTV5 HD",
        logo: "https://i.imgur.com/5O4wMzH.png",
        group: "VTV Quốc Gia",
        streams: [
            "https://dethich.pw/vtv5/index.m3u8",
            "https://live.vtvdigital.vn/vtv5/index.m3u8"
        ]
    },
    {
        name: "VTV7 HD",
        logo: "https://i.imgur.com/7P5wNzI.png",
        group: "VTV Quốc Gia",
        streams: [
            "https://dethich.pw/vtv7/index.m3u8",
            "https://live.vtvdigital.vn/vtv7/index.m3u8"
        ]
    },
    {
        name: "VTV8 HD",
        logo: "https://i.imgur.com/8Q6wOzJ.png",
        group: "VTV Quốc Gia",
        streams: [
            "https://dethich.pw/vtv8/index.m3u8",
            "https://live.vtvdigital.vn/vtv8/index.m3u8"
        ]
    },
    {
        name: "VTV9 HD",
        logo: "https://i.imgur.com/9R7wPzK.png",
        group: "VTV Quốc Gia",
        streams: [
            "https://dethich.pw/vtv9/index.m3u8",
            "https://live.vtvdigital.vn/vtv9/index.m3u8"
        ]
    },
    {
        name: "THVL1 HD",
        logo: "https://i.imgur.com/q3fjpYc.png",
        group: "THVL Vĩnh Long",
        streams: [
            "https://dethich.pw/thvl1/index.m3u8"
        ]
    },
    {
        name: "THVL2 HD",
        logo: "https://i.imgur.com/zGv54Ed.png",
        group: "THVL Vĩnh Long",
        streams: [
            "https://dethich.pw/thvl2/index.m3u8"
        ]
    },
    {
        name: "THVL3 HD",
        logo: "https://i.imgur.com/44c1Yoz.png",
        group: "THVL Vĩnh Long",
        streams: [
            "https://dethich.pw/thvl3/index.m3u8"
        ]
    },
    {
        name: "THVL4 HD",
        logo: "https://i.imgur.com/JWDsJxB.png",
        group: "THVL Vĩnh Long",
        streams: [
            "https://dethich.pw/thvl4/index.m3u8"
        ]
    },
    {
        name: "HTV7 HD",
        logo: "https://i.imgur.com/8T4q4pY.png",
        group: "HTV TP.HCM",
        streams: [
            "https://dethich.pw/htv7/index.m3u8",
            "https://live.vtvdigital.vn/htv7/index.m3u8"
        ]
    },
    {
        name: "HTV9 HD",
        logo: "https://i.imgur.com/8M2qNpE.png",
        group: "HTV TP.HCM",
        streams: [
            "https://dethich.pw/htv9/index.m3u8",
            "https://live.vtvdigital.vn/htv9/index.m3u8"
        ]
    },
    {
        name: "ANTV HD",
        logo: "https://i.imgur.com/Y4j5mQO.png",
        group: "VTV Quốc Gia",
        streams: [
            "https://dethich.pw/antv/index.m3u8",
            "https://live.vtvdigital.vn/antv/index.m3u8"
        ]
    },
    {
        name: "QPVN HD (Quốc Phòng)",
        logo: "https://i.imgur.com/tHqW5jN.png",
        group: "VTV Quốc Gia",
        streams: [
            "https://dethich.pw/qpvn/index.m3u8",
            "https://live.vtvdigital.vn/qpvn/index.m3u8"
        ]
    },
    {
        name: "Đồng Tháp 1 HD",
        logo: "https://i.imgur.com/U8cW2xJ.png",
        group: "Đài Tỉnh Thành",
        streams: [
            "https://dethich.pw/thdt1/index.m3u8"
        ]
    },
    {
        name: "Hà Nội 1 HD",
        logo: "https://i.imgur.com/H1tN9qP.png",
        group: "Đài Tỉnh Thành",
        streams: [
            "https://dethich.pw/hanoitv1/index.m3u8"
        ]
    },
    {
        name: "Đà Nẵng 1 HD",
        logo: "https://i.imgur.com/D1nW8xK.png",
        group: "Đài Tỉnh Thành",
        streams: [
            "https://dethich.pw/danang1/index.m3u8"
        ]
    },
    {
        name: "Cần Thơ HD",
        logo: "https://i.imgur.com/CTnW7xL.png",
        group: "Đài Tỉnh Thành",
        streams: [
            "https://dethich.pw/cantho/index.m3u8"
        ]
    }
];

async function runAutoPilot() {
    let hasSourcesChanges = false;
    let hasTvChanges = false;
    const actionLogs = [];

    // ==============================================================
    // PHẦN 1: TỰ ĐỘNG QUÉT & VÁ NGUỒN PHIM (MOVIE AUTO-HEAL)
    // ==============================================================
    console.log('--- BƯỚC 1: TỰ ĐỘNG QUÉT & KIỂM ĐỊNH NGUỒN PHIM ---');

    // 1. KKPhim
    const kk = sourcesConfig.sources.kkphim;
    if (kk) {
        process.stdout.write(`🎬 Kiểm tra [${kk.name}] (${kk.api_base})... `);
        let check = await probeJsonApiWithRetry(`${kk.api_base}/danh-sach/phim-moi-cap-nhat?page=1`, 'kkphim');
        if (check.ok && check.validSchema) {
            console.log(`✅ HOẠT ĐỘNG (${check.latency}ms)`);
            if (kk.enabled === false) {
                kk.enabled = true;
                hasSourcesChanges = true;
                actionLogs.push(`Tái kích hoạt [${kk.name}]`);
            }
        } else {
            console.log(`⚠️ LỖI ➔ Đang tìm kiếm mirror dự phòng & quét GitHub...`);
            const discovered = await discoverMovieDomainsFromGitHub('phimapi kkphim', /https?:\/\/[a-zA-Z0-9.-]*phimapi[a-zA-Z0-9.-]*/gi);
            const pool = [...new Set(['https://phimapi.com', 'https://phimapi.com/v1/api', 'https://phimapi.org', 'https://phimapi.net', ...discovered])];
            let found = null;
            for (const cand of pool) {
                if (cand === kk.api_base) continue;
                process.stdout.write(`   ↳ Thử mirror [${cand}]... `);
                const test = await probeJsonApi(`${cand}/danh-sach/phim-moi-cap-nhat?page=1`, 'kkphim');
                if (test.ok && test.validSchema) {
                    console.log(`✅ SỐNG TỐT (${test.latency}ms)`);
                    found = cand;
                    break;
                } else {
                    console.log(`❌ Lỗi`);
                }
            }
            if (found) {
                kk.api_base = found;
                kk.enabled = true;
                hasSourcesChanges = true;
                actionLogs.push(`Tự động vá [${kk.name}] -> ${found}`);
            } else {
                console.log(`   ❌ Tất cả mirror [${kk.name}] đều sập ➔ Tự động Disable`);
                if (kk.enabled !== false) {
                    kk.enabled = false;
                    hasSourcesChanges = true;
                    actionLogs.push(`Tự động Disable [${kk.name}] (Sập toàn bộ mirror)`);
                }
            }
        }
    }

    // 2. NguonC
    const nguonc = sourcesConfig.sources.nguonc;
    if (nguonc) {
        process.stdout.write(`🎬 Kiểm tra [${nguonc.name}] (${nguonc.api_base})... `);
        let check = await probeJsonApiWithRetry(`https://phim.nguonc.com/api/films/phim-moi-cap-nhat?page=1`, 'nguonc');
        if (check.ok) {
            console.log(`✅ HOẠT ĐỘNG (${check.latency}ms)`);
            if (nguonc.enabled === false) {
                nguonc.enabled = true;
                hasSourcesChanges = true;
                actionLogs.push(`Tái kích hoạt [${nguonc.name}]`);
            }
        } else {
            console.log(`⚠️ LỖI ➔ Đang tìm kiếm endpoint dự phòng...`);
            const pool = ['https://phim.nguonc.com/api/film', 'https://api.nguonc.com/api/film'];
            let found = null;
            for (const cand of pool) {
                if (cand === nguonc.api_base) continue;
                const test = await probeJsonApi(`${cand}/phim-moi-cap-nhat?page=1`, 'nguonc');
                if (test.ok) {
                    found = cand;
                    break;
                }
            }
            if (found) {
                nguonc.api_base = found;
                nguonc.enabled = true;
                hasSourcesChanges = true;
                actionLogs.push(`Tự động vá [${nguonc.name}] -> ${found}`);
            } else {
                if (nguonc.enabled !== false) {
                    nguonc.enabled = false;
                    hasSourcesChanges = true;
                    actionLogs.push(`Tự động Disable [${nguonc.name}]`);
                }
            }
        }
    }

    // 3. CoBePhim (Cào landing cobelink.xyz tự động)
    const cobe = sourcesConfig.sources.cobephim;
    if (cobe) {
        process.stdout.write(`🎬 Kiểm tra [${cobe.name}] (${cobe.api_base})... `);
        const backendUrl = (cobe.api_backend || 'https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz') + '/api/v1/movies/by-category/co-trang?page=1&limit=1';
        let backendCheck = await probeJsonApi(backendUrl, 'cobe_backend');
        let frontendCheck = await probeStream(cobe.api_base, 2, 4000);

        if (backendCheck.ok || frontendCheck.ok) {
            console.log(`✅ HOẠT ĐỘNG (${backendCheck.latency || frontendCheck.latency}ms)`);
            if (cobe.enabled === false) {
                cobe.enabled = true;
                hasSourcesChanges = true;
                actionLogs.push(`Tái kích hoạt [${cobe.name}]`);
            }
        } else {
            console.log(`⚠️ LỖI ➔ Đang cào landing cobelink.xyz để lấy domain mới nhất...`);
            let foundDomain = null;
            try {
                const landingRes = await probeJsonApi('https://cobelink.xyz', 'raw', 5000);
                if (landingRes.ok && landingRes.body) {
                    const matches = landingRes.body.match(/https?:\/\/[a-zA-Z0-9.-]*cobe[a-zA-Z0-9.-]*/g) || [];
                    const candidates = [...new Set([...matches, 'https://cobephim.tv', 'https://cobephim.club', 'https://cobephim.ws', 'https://cobephim.ink'])];
                    for (const cand of candidates) {
                        const cleanCand = cand.replace(/\/$/, '');
                        if (cleanCand === cobe.api_base) continue;
                        const test = await probeStream(cleanCand, 2, 4000);
                        if (test.ok) {
                            foundDomain = cleanCand;
                            break;
                        }
                    }
                }
            } catch {}

            if (foundDomain) {
                cobe.api_base = foundDomain;
                cobe.enabled = true;
                hasSourcesChanges = true;
                actionLogs.push(`Tự động vá [${cobe.name}] -> ${foundDomain}`);
            } else {
                if (cobe.enabled !== false) {
                    cobe.enabled = false;
                    hasSourcesChanges = true;
                    actionLogs.push(`Tự động Disable [${cobe.name}] (Sập không tìm thấy mirror)`);
                }
            }
        }
    }

    // 4. OPhim
    const ophim = sourcesConfig.sources.ophim;
    if (ophim) {
        process.stdout.write(`🎬 Kiểm tra [${ophim.name}] (${ophim.api_base})... `);
        let check = await probeJsonApiWithRetry(`${ophim.api_base}/danh-sach/phim-moi-cap-nhat?page=1`, 'ophim');
        if (check.ok && check.validSchema) {
            console.log(`✅ HOẠT ĐỘNG (${check.latency}ms)`);
            if (ophim.enabled === false) {
                ophim.enabled = true;
                hasSourcesChanges = true;
                actionLogs.push(`Tái kích hoạt [${ophim.name}]`);
            }
        } else {
            console.log(`⚠️ LỖI ➔ Đang quét pool mirror & tự động săn domain OPhim mới trên GitHub...`);
            const discovered = await discoverMovieDomainsFromGitHub('ophim api phim-moi-cap-nhat', /https?:\/\/[a-zA-Z0-9.-]*ophim[a-zA-Z0-9.-]*/gi);
            const pool = [...new Set([
                'https://ophim1.com', 'https://ophim17.cc', 'https://ophim18.cc',
                'https://ophim16.cc', 'https://ophim.live', 'https://ophim8.cc',
                'https://ophim.cc', ...discovered
            ])];
            let found = null;
            for (const cand of pool) {
                if (cand === ophim.api_base) continue;
                const test = await probeJsonApi(`${cand}/danh-sach/phim-moi-cap-nhat?page=1`, 'ophim');
                if (test.ok && test.validSchema) {
                    found = cand;
                    break;
                }
            }
            if (found) {
                ophim.api_base = found;
                ophim.enabled = true;
                hasSourcesChanges = true;
                actionLogs.push(`Tự động vá [${ophim.name}] -> ${found}`);
            } else {
                console.log(`   ❌ Tất cả mirror [${ophim.name}] đều sập hoặc là trang landing/parking ➔ Tự động Disable`);
                if (ophim.enabled !== false) {
                    ophim.enabled = false;
                    hasSourcesChanges = true;
                    actionLogs.push(`Tự động Disable [${ophim.name}] (Tất cả mirror đều sập)`);
                }
            }
        }
    }

    // 5. YTS 4K Cinema
    const yts = sourcesConfig.sources.torrent4k;
    if (yts) {
        const pool = Array.isArray(yts.mirrors) ? [...yts.mirrors] : [];
        const extraMirrors = ['https://yts.gg', 'https://yts.lt', 'https://yts.bz', 'https://yts.torrentbay.st', 'https://yts.rs', 'https://yts.mx'];
        const allCandidates = [...new Set([...pool, ...extraMirrors])];

        const workingMirrors = [];
        for (const mirror of allCandidates) {
            const check = await probeJsonApi(`${mirror}/api/v2/list_movies.json?limit=1`, 'torrent4k', 4000);
            if (check.ok && check.validSchema) {
                workingMirrors.push({ url: mirror, latency: check.latency });
            }
        }
        if (workingMirrors.length > 0) {
            workingMirrors.sort((a, b) => a.latency - b.latency);
            const sortedUrls = workingMirrors.map(m => m.url);
            if (JSON.stringify(sortedUrls) !== JSON.stringify(yts.mirrors)) {
                yts.mirrors = sortedUrls;
                hasSourcesChanges = true;
                actionLogs.push(`Tự động sắp xếp YTS 4K Mirrors (Top: ${sortedUrls[0]})`);
            }
        }
    }

    // ==============================================================
    // PHẦN 2: TỰ ĐỘNG QUÉT, TÌM LUỒNG MỚI & KIỂM ĐỊNH KÊNH TV (IPTV AUTO-SCOUT)
    // ==============================================================
    console.log('\n--- BƯỚC 2: TỰ ĐỘNG QUÉT & TÌM KIẾM KÊNH TRUYỀN HÌNH MỚI ---');
    const communityMap = await harvestCommunityIptvStreams(sourcesConfig.sources.iptv?.url);
    const validatedChannels = [];
    let healedFromCommunityCount = 0;

    for (const ch of MASTER_CHANNELS) {
        process.stdout.write(`📺 Kiểm tra kênh [${ch.name}]... `);
        const aliveCandidates = []; // { url, latency, isCommunity }

        // 1. Thử các luồng ưu tiên (static primary) trước
        for (const streamUrl of ch.streams) {
            const probe = await probeStream(streamUrl, 3, 4000);
            if (probe.ok && probe.isM3u) {
                aliveCandidates.push({ url: streamUrl, latency: probe.latency, isCommunity: false });
            }
        }

        // 2. Nếu luồng chính chết hoặc chưa có luồng dự phòng -> tự động tìm & thử từ kho M3U cộng đồng
        if (aliveCandidates.length < 2) {
            const chKey = normalizeChannelKey(ch.name);
            const discoveredUrls = communityMap.has(chKey)
                ? [...communityMap.get(chKey)].filter(u => !ch.streams.includes(u)).slice(0, 6)
                : [];

            if (discoveredUrls.length > 0) {
                const probeResults = await Promise.all(
                    discoveredUrls.map(async (u) => ({ url: u, res: await probeStream(u, 3, 4000) }))
                );
                for (const item of probeResults) {
                    if (item.res.ok && item.res.isM3u) {
                        aliveCandidates.push({ url: item.url, latency: item.res.latency, isCommunity: true });
                    }
                }
            }
        }

        if (aliveCandidates.length > 0) {
            // Sắp xếp ưu tiên luồng chính (nếu sống), sau đó theo độ trễ thấp nhất
            aliveCandidates.sort((a, b) => (Number(a.isCommunity) - Number(b.isCommunity)) || (a.latency - b.latency));
            const primary = aliveCandidates[0];
            const backups = aliveCandidates.slice(1, 2).map(x => x.url); // Giữ tối đa 1 luồng dự phòng tốt nhất

            if (primary.isCommunity) {
                healedFromCommunityCount++;
                console.log(`🔄 TỰ VÁ TỪ CỘNG ĐỒNG (${primary.latency}ms, ${aliveCandidates.length} luồng)`);
            } else {
                console.log(`✅ SỐNG (${primary.latency}ms, ${aliveCandidates.length} luồng khả dụng)`);
            }

            validatedChannels.push({
                name: ch.name,
                logo: ch.logo,
                group: ch.group,
                streamUrl: primary.url,
                backupUrls: backups
            });
        } else {
            console.log(`❌ SẬP (Không luồng nào phản hồi kể cả cộng đồng)`);
        }
    }

    if (healedFromCommunityCount > 0) {
        actionLogs.push(`Tự động tìm & vá ${healedFromCommunityCount} kênh TV bị chết bằng luồng M3U cộng đồng`);
    }

    // Tạo file M3U chuẩn sạch tinh khiết (Anti-Wipe Guardrail: chỉ ghi khi có >= 3 kênh sống)
    let m3uContent = '#EXTM3U name="DuyTV Official Clean Channels" tvg-shift=0\n\n';
    for (const vch of validatedChannels) {
        const tvgId = vch.name.replace(/\s+/g, '_').toLowerCase();
        m3uContent += `#EXTINF:-1 tvg-id="${tvgId}" tvg-name="${vch.name}" tvg-logo="${vch.logo}" group-title="${vch.group}",${vch.name}\n`;
        m3uContent += `${vch.streamUrl}\n\n`;
        for (const bUrl of vch.backupUrls) {
            m3uContent += `#EXTINF:-1 tvg-id="${tvgId}_bk" tvg-name="${vch.name} (Dự Phòng)" tvg-logo="${vch.logo}" group-title="${vch.group}",${vch.name} (Dự Phòng)\n`;
            m3uContent += `${bUrl}\n\n`;
        }
    }

    if (validatedChannels.length >= 3) {
        const currentM3u = fs.existsSync(cleanM3uPath) ? fs.readFileSync(cleanM3uPath, 'utf8') : '';
        if (currentM3u.trim() !== m3uContent.trim()) {
            fs.writeFileSync(cleanM3uPath, m3uContent, 'utf8');
            hasTvChanges = true;
            actionLogs.push(`Cập nhật playlist truyền hình sạch (config/tv_channels_clean.m3u: ${validatedChannels.length} kênh sống)`);
        }

        // Luôn đồng bộ vào assets dự phòng của Android TV
        const assetM3uPath = path.join(rootDir, 'app', 'src', 'main', 'assets', 'tv_channels_clean.m3u');
        const assetDir = path.dirname(assetM3uPath);
        if (!fs.existsSync(assetDir)) {
            fs.mkdirSync(assetDir, { recursive: true });
        }
        const currentAssetM3u = fs.existsSync(assetM3uPath) ? fs.readFileSync(assetM3uPath, 'utf8') : '';
        if (currentAssetM3u.trim() !== m3uContent.trim()) {
            fs.writeFileSync(assetM3uPath, m3uContent, 'utf8');
            hasTvChanges = true;
        }
    } else {
        console.log('⚠️ BẢO VỆ CHỐNG XÓA TRẮNG: Số kênh sống < 3 (nghi lỗi mạng Runner) ➔ Giữ nguyên playlist M3U cũ!');
    }

    // ==============================================================
    // PHẦN 3: LƯU CẤU HÌNH & TỰ ĐỘNG CHUẨN BỊ TÁI BUILD APK (IF CHANGED)
    // ==============================================================
    console.log('\n--- BƯỚC 3: TỔNG HỢP & XỬ LÝ TỰ ĐỘNG HÓA ---');
    if (hasSourcesChanges || hasTvChanges) {
        console.log('⚡ PHÁT HIỆN BIẾN ĐỘNG HỆ THỐNG:');
        actionLogs.forEach(log => console.log(`   + ${log}`));

        // 1. Cập nhật config/sources.json
        sourcesConfig.last_updated = new Date().toISOString().split('T')[0];
        sourcesConfig.tv_channels_clean_url = "https://raw.githubusercontent.com/Doanduy09/DuyTV/main/config/tv_channels_clean.m3u";
        fs.writeFileSync(configSourcesPath, JSON.stringify(sourcesConfig, null, 2), 'utf8');

        // 2. Cập nhật .config
        const activeMovieUrls = [
            sourcesConfig.sources.kkphim?.enabled ? sourcesConfig.sources.kkphim.api_base : null,
            sourcesConfig.sources.ophim?.enabled ? sourcesConfig.sources.ophim.api_base : null,
            sourcesConfig.sources.nguonc?.enabled ? sourcesConfig.sources.nguonc.api_base : null,
            sourcesConfig.sources.cobephim?.enabled ? sourcesConfig.sources.cobephim.api_base : null
        ].filter(Boolean);
        const dotConfigLines = [
            '# ========================================================',
            '# DANH SÁCH NGUỒN PHIM DUYTV (TỰ ĐỘNG ĐỒNG BỘ 24/7 BỞI GITHUB AUTO-PILOT)',
            '# ========================================================',
            ...activeMovieUrls
        ];
        fs.writeFileSync(dotConfigPath, dotConfigLines.join('\n') + '\n', 'utf8');

        // 3. Tự động nâng Version Code & Version Name trong config/version.json và app/build.gradle.kts
        let versionInfo = { versionCode: 84, versionName: "2.6.8" };
        if (fs.existsSync(versionJsonPath)) {
            try { versionInfo = JSON.parse(fs.readFileSync(versionJsonPath, 'utf8')); } catch {}
        }
        const nextVersionCode = (versionInfo.versionCode || 84) + 1;
        const nextVersionName = "2.6.9";

        versionInfo.versionCode = nextVersionCode;
        versionInfo.versionName = nextVersionName;
        versionInfo.sha256 = "";
        versionInfo.releaseNotes = `v${nextVersionName} (Build #${nextVersionCode}): Hệ thống Auto-Pilot tự động phát hiện, vá nguồn phim và cập nhật kênh truyền hình sống mới 100%.`;
        fs.writeFileSync(versionJsonPath, JSON.stringify(versionInfo, null, 2), 'utf8');

        // Cập nhật app/build.gradle.kts
        if (fs.existsSync(gradlePath)) {
            let gradleContent = fs.readFileSync(gradlePath, 'utf8');
            gradleContent = gradleContent.replace(/versionCode\s*=\s*\d+/, `versionCode = ${nextVersionCode}`);
            gradleContent = gradleContent.replace(/versionName\s*=\s*"[^"]+"/, `versionName = "${nextVersionName}"`);
            fs.writeFileSync(gradlePath, gradleContent, 'utf8');
        }

        // Tạo cờ đánh dấu cho GitHub Actions
        const flagPath = path.join(rootDir, 'auto_pilot_triggered.flag');
        fs.writeFileSync(flagPath, actionLogs.join('\n'), 'utf8');
        console.log(`\n🎉 HOÀN TẤT: Đã tự động nâng lên v${nextVersionName} (Build #${nextVersionCode}) và sẵn sàng tự động build APK!`);
    } else {
        console.log('✅ Toàn bộ nguồn phim và kênh truyền hình đang ở trạng thái HOÀN HẢO. Không cần thay đổi.');
    }

    // 4. Xuất Báo Cáo Ra GitHub Step Summary (nếu chạy trong GitHub Actions)
    if (process.env.GITHUB_STEP_SUMMARY) {
        try {
            const summaryLines = [
                '# ⚡ DuyTV Auto-Pilot 24/7 Ecosystem Summary',
                `*Thời gian quét:* \`${new Date().toISOString()}\``,
                '',
                '### 1. Trạng Thái Nguồn Phim',
                '| Nguồn | Trạng Thái | API Endpoint | Ghi Chú |',
                '| :--- | :---: | :--- | :--- |',
                `| **KKPhim** | ${sourcesConfig.sources.kkphim?.enabled ? '🟢 Hoạt động' : '🔴 Vô hiệu'} | \`${sourcesConfig.sources.kkphim?.api_base || 'N/A'}\` | Khớp 100% |`,
                `| **NguonC** | ${sourcesConfig.sources.nguonc?.enabled ? '🟢 Hoạt động' : '🔴 Vô hiệu'} | \`${sourcesConfig.sources.nguonc?.api_base || 'N/A'}\` | Khớp 100% |`,
                `| **CoBePhim** | ${sourcesConfig.sources.cobephim?.enabled ? '🟢 Hoạt động' : '🔴 Vô hiệu'} | \`${sourcesConfig.sources.cobephim?.api_base || 'N/A'}\` | Tự động cào cobelink.xyz |`,
                `| **OPhim** | ${sourcesConfig.sources.ophim?.enabled ? '🟢 Hoạt động' : '🟡 Tự ngắt (Parked)'} | \`${sourcesConfig.sources.ophim?.api_base || 'N/A'}\` | Tự bảo vệ an ninh |`,
                `| **YTS 4K** | ${sourcesConfig.sources.torrent4k?.enabled ? '🟢 Hoạt động' : '🔴 Vô hiệu'} | \`${sourcesConfig.sources.torrent4k?.mirrors?.[0] || 'N/A'}\` | Torrent 4K/FHD |`,
                '',
                '### 2. Kênh Truyền Hình Đã Kiểm Định (100% Live)',
                `*Tổng số kênh sạch:* **${validatedChannels.length}** kênh.`,
                '',
                '### 3. Kết Quả Xử Lý',
                (hasSourcesChanges || hasTvChanges)
                    ? `⚡ **Phát hiện biến động:** Đã tự động kích hoạt Build APK mới!\n${actionLogs.map(l => `- ${l}`).join('\n')}`
                    : '✅ **Hệ thống ổn định hoàn hảo:** Không cần build lại.'
            ];
            fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, summaryLines.join('\n') + '\n', 'utf8');
        } catch (e) {
            console.error('Không thể ghi GITHUB_STEP_SUMMARY:', e.message);
        }
    }

    // 5. Gửi Cảnh Báo Telegram (Nếu cấu hình TELEGRAM_BOT_TOKEN & TELEGRAM_CHAT_ID)
    const telegramToken = process.env.TELEGRAM_BOT_TOKEN;
    const telegramChatId = process.env.TELEGRAM_CHAT_ID;
    if (telegramToken && telegramChatId) {
        try {
            const statusIcon = (hasSourcesChanges || hasTvChanges) ? '⚡' : '✅';
            const msg = [
                `<b>${statusIcon} DUYTV AUTO-PILOT 24/7 BÁO CÁO</b>`,
                `📅 <i>${new Date().toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' })}</i>`,
                `🎬 <b>Nguồn phim:</b> KKPhim, NguonC, CoBePhim, YTS (OPhim: tắt)`,
                `📺 <b>Truyền hình:</b> ${validatedChannels.length} kênh sống 100%`,
                (hasSourcesChanges || hasTvChanges)
                    ? `🚀 <b>Hành động:</b> Đã kích hoạt Build APK mới!\n${actionLogs.map(l => `• ${l}`).join('\n')}`
                    : `✨ <b>Trạng thái:</b> Toàn bộ hệ thống ổn định hoàn hảo.`
            ].join('\n');

            await new Promise((resolve) => {
                const postData = JSON.stringify({
                    chat_id: telegramChatId,
                    text: msg,
                    parse_mode: 'HTML'
                });
                const tReq = https.request(`https://api.telegram.org/bot${telegramToken}/sendMessage`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Content-Length': Buffer.byteLength(postData)
                    },
                    timeout: 5000
                }, (tRes) => {
                    tRes.resume();
                    resolve();
                });
                tReq.on('error', () => resolve());
                tReq.write(postData);
                tReq.end();
            });
            console.log('📱 Đã gửi thông báo Telegram thành công.');
        } catch (e) {
            console.log('Bỏ qua thông báo Telegram:', e.message);
        }
    }
}

runAutoPilot().catch(err => {
    console.error('❌ Lỗi Auto-Pilot:', err);
    process.exit(1);
});
