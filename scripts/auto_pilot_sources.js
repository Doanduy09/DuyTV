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
                req.destroy();
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

            res.setEncoding('utf8');
            res.on('data', chunk => {
                if (body.length < 2048) body += chunk;
            });

            res.on('end', () => {
                if (resolved) return;
                resolved = true;
                const latency = Date.now() - start;
                const ok = res.statusCode >= 200 && res.statusCode < 400;
                const isM3u = body.includes('#EXTM3U') || body.includes('#EXTINF') || body.includes('.ts') || body.includes('.m3u8');
                resolve({
                    ok,
                    status: res.statusCode,
                    latency,
                    isM3u,
                    error: ok ? null : `HTTP ${res.statusCode}`
                });
            });
        });

        req.on('timeout', () => {
            if (resolved) return;
            resolved = true;
            req.destroy();
            resolve({ ok: false, status: 0, latency: timeoutMs, error: 'TIMEOUT' });
        });

        req.on('error', (err) => {
            if (resolved) return;
            resolved = true;
            resolve({ ok: false, status: 0, latency: Date.now() - start, error: err.message });
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
        let check = await probeJsonApi(`${kk.api_base}/danh-sach/phim-moi-cap-nhat?page=1`, 'kkphim');
        if (check.ok && check.validSchema) {
            console.log(`✅ HOẠT ĐỘNG (${check.latency}ms)`);
            if (kk.enabled === false) {
                kk.enabled = true;
                hasSourcesChanges = true;
                actionLogs.push(`Tái kích hoạt [${kk.name}]`);
            }
        } else {
            console.log(`⚠️ LỖI ➔ Đang tìm kiếm mirror dự phòng...`);
            const pool = ['https://phimapi.com', 'https://phimapi.com/v1/api', 'https://phimapi.org'];
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
        let check = await probeJsonApi(`https://phim.nguonc.com/api/films/phim-moi-cap-nhat?page=1`, 'nguonc');
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
        let check = await probeJsonApi(`${ophim.api_base}/danh-sach/phim-moi-cap-nhat?page=1`, 'ophim');
        if (check.ok && check.validSchema) {
            console.log(`✅ HOẠT ĐỘNG (${check.latency}ms)`);
            if (ophim.enabled === false) {
                ophim.enabled = true;
                hasSourcesChanges = true;
                actionLogs.push(`Tái kích hoạt [${ophim.name}]`);
            }
        } else {
            console.log(`⚠️ LỖI ➔ Đang quét pool mirror...`);
            const pool = ['https://ophim1.com', 'https://ophim.live', 'https://ophim17.cc', 'https://ophim8.cc', 'https://ophim.cc'];
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
    // PHẦN 2: TỰ ĐỘNG QUÉT & KIỂM ĐỊNH KÊNH TRUYỀN HÌNH (IPTV AUTO-SCOUT)
    // ==============================================================
    console.log('\n--- BƯỚC 2: TỰ ĐỘNG QUÉT & KIỂM ĐỊNH KÊNH TRUYỀN HÌNH ---');
    const validatedChannels = [];

    for (const ch of MASTER_CHANNELS) {
        process.stdout.write(`📺 Kiểm tra kênh [${ch.name}]... `);
        const aliveStreams = [];
        for (const streamUrl of ch.streams) {
            const probe = await probeStream(streamUrl, 3, 4000);
            if (probe.ok && probe.isM3u) {
                aliveStreams.push(streamUrl);
            }
        }

        if (aliveStreams.length > 0) {
            console.log(`✅ SỐNG (${aliveStreams.length} luồng khả dụng)`);
            validatedChannels.push({
                name: ch.name,
                logo: ch.logo,
                group: ch.group,
                streamUrl: aliveStreams[0],
                backupUrls: aliveStreams.slice(1)
            });
        } else {
            console.log(`❌ SẬP (Không luồng nào phản hồi)`);
        }
    }

    // Tạo file M3U chuẩn sạch tinh khiết
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

    const currentM3u = fs.existsSync(cleanM3uPath) ? fs.readFileSync(cleanM3uPath, 'utf8') : '';
    if (currentM3u.trim() !== m3uContent.trim()) {
        fs.writeFileSync(cleanM3uPath, m3uContent, 'utf8');
        hasTvChanges = true;
        actionLogs.push(`Cập nhật playlist truyền hình sạch (config/tv_channels_clean.m3u: ${validatedChannels.length} kênh sống)`);
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
}

runAutoPilot().catch(err => {
    console.error('❌ Lỗi Auto-Pilot:', err);
    process.exit(1);
});
