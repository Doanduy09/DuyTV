const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

const configPath = path.join(__dirname, '..', 'config', 'sources.json');
const dotConfigPath = path.join(__dirname, '..', '.config');
const flagPath = path.join(__dirname, '..', 'sources_healed.flag');
let config = JSON.parse(fs.readFileSync(configPath, 'utf8'));

console.log('====================================================');
console.log('DUYTV AUTONOMOUS SOURCE MONITOR & SELF-HEALING ENGINE');
console.log('Tự Động Tìm Mirror, Loại Bỏ Nguồn Chết & Tự Vá Lỗi');
console.log('====================================================\n');

// 1. Bộ lọc chống đầu độc & nhận diện Domain Parking / Web cờ bạc
const GAMBLING_AND_PARKING_KEYWORDS = [
    'lander_system', 'parking', 'domain for sale', 'buy this domain',
    'nhà cái', 'tai xiu', 'tài xỉu', 'kubet', 'thabet', 'cá cược', 'casino',
    'đá gà', 'keonhacai', 'nổ hũ', 'bắn cá', 'baccarat', 'xo so', 'lô đề'
];

function isPoisonedOrParked(text) {
    if (!text || typeof text !== 'string') return false;
    const lower = text.toLowerCase();
    return GAMBLING_AND_PARKING_KEYWORDS.some(kw => lower.includes(kw));
}

// 2. HTTP Request an toàn có Timeout và User-Agent chuẩn
function checkUrl(url, timeoutMs = 7000, readBody = true) {
    return new Promise((resolve) => {
        let client;
        try {
            client = url.startsWith('https') ? https : http;
        } catch {
            return resolve({ ok: false, status: 0, latency: 0, body: '', error: 'INVALID_URL' });
        }

        const start = Date.now();
        let body = '';
        let resolved = false;

        const req = client.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 DuyTV/2.6.8',
                'Accept': 'application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Accept-Language': 'vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7'
            },
            timeout: timeoutMs
        }, (res) => {
            if (readBody) {
                res.setEncoding('utf8');
                res.on('data', chunk => {
                    if (body.length < 50000) body += chunk;
                });
            }

            res.on('end', () => {
                if (resolved) return;
                resolved = true;
                const latency = Date.now() - start;
                const ok = res.statusCode >= 200 && res.statusCode < 400;
                resolve({
                    ok,
                    status: res.statusCode,
                    latency,
                    body,
                    headers: res.headers,
                    error: ok ? null : `HTTP ${res.statusCode}`
                });
            });
        });

        req.on('timeout', () => {
            if (resolved) return;
            resolved = true;
            req.destroy();
            resolve({ ok: false, status: 0, latency: timeoutMs, body: '', error: 'TIMEOUT' });
        });

        req.on('error', (err) => {
            if (resolved) return;
            resolved = true;
            resolve({ ok: false, status: 0, latency: Date.now() - start, body: '', error: err.message });
        });
    });
}

// 3. Xác thực Schema JSON phim chuẩn
function isValidMovieApi(body, type) {
    if (!body || isPoisonedOrParked(body)) return false;
    try {
        const json = JSON.parse(body);
        if (type === 'kkphim' || type === 'ophim') {
            return (json.status === true || json.status === 'success' || json.status === 'ok') &&
                   (Array.isArray(json.items) || Array.isArray(json.data?.items) || Boolean(json.movie));
        }
        if (type === 'nguonc') {
            return (json.status === 'success' || json.status === true) && Array.isArray(json.items);
        }
        if (type === 'cobe_backend') {
            return json.status === true && Array.isArray(json.result);
        }
        if (type === 'torrent4k') {
            return json.status === 'ok' && (Array.isArray(json.data?.movies) || Array.isArray(json.movies));
        }
        return true;
    } catch {
        return false;
    }
}

// 4. Cào động landing cobelink.xyz để phát hiện domain CoBePhim mới nhất
async function discoverCoBeDomainsFromLanding() {
    try {
        const res = await checkUrl('https://cobelink.xyz', 5000, true);
        if (res.ok && res.body && !isPoisonedOrParked(res.body)) {
            const matches = res.body.match(/https?:\/\/[a-zA-Z0-9.-]*cobe[a-zA-Z0-9.-]*/g) || [];
            return [...new Set(matches.map(u => u.replace(/\/$/, '')))];
        }
    } catch {}
    return [];
}

async function run() {
    const results = [];
    let hasChanges = false;
    const actionsLog = [];

    // ==========================================
    // 1. KKPHIM
    // ==========================================
    const kk = config.sources.kkphim;
    if (kk) {
        process.stdout.write(`Đang kiểm tra [${kk.name}] (${kk.api_base})... `);
        let res = await checkUrl(`${kk.api_base}/danh-sach/phim-moi-cap-nhat?page=1`);
        let valid = res.ok && isValidMovieApi(res.body, 'kkphim');

        if (!valid) {
            console.log(`⚠️ Lỗi: ${res.error || 'Sai dữ liệu'} ➔ Đang tìm kiếm mirror KKPhim...`);
            const candidates = ['https://phimapi.com', 'https://phimapi.com/v1/api', 'https://phimapi.org'];
            let found = null;
            for (const cand of candidates) {
                if (cand === kk.api_base) continue;
                process.stdout.write(`   ↳ Thử mirror [${cand}]... `);
                const tRes = await checkUrl(`${cand}/danh-sach/phim-moi-cap-nhat?page=1`);
                if (tRes.ok && isValidMovieApi(tRes.body, 'kkphim')) {
                    console.log(`✅ SỐNG TỐT (${tRes.latency}ms)`);
                    found = cand;
                    res = tRes;
                    valid = true;
                    break;
                } else {
                    console.log(`❌ Lỗi`);
                }
            }

            if (found) {
                console.log(`   ✨ TỰ ĐỘNG VÁ: Chuyển KKPhim sang ${found}`);
                kk.api_base = found;
                kk.enabled = true;
                hasChanges = true;
                actionsLog.push(`KKPhim -> Cập nhật domain sống: ${found}`);
            } else if (kk.enabled) {
                console.log(`   🚫 TỰ LOẠI BỎ: Nguồn KKPhim sập hoàn toàn ➔ Tạm tắt để chống lag`);
                kk.enabled = false;
                hasChanges = true;
                actionsLog.push(`KKPhim -> Tự động loại bỏ (enabled: false) do không có mirror sống`);
            }
        } else if (!kk.enabled) {
            console.log(`   ♻️ TỰ PHỤC HỒI: KKPhim đã sống trở lại ➔ Tự động kích hoạt`);
            kk.enabled = true;
            hasChanges = true;
            actionsLog.push(`KKPhim -> Phục hồi hoạt động (enabled: true)`);
        }

        if (valid) {
            console.log(`✅ HOẠT ĐỘNG TỐT (${res.latency}ms)`);
            results.push({ name: kk.name, url: kk.api_base, status: 'OK', latency: res.latency });
        } else {
            console.log(`❌ BỊ LỖI (Đã tự xử lý)`);
            results.push({ name: kk.name, url: kk.api_base, status: 'DISABLED', error: res.error });
        }
    }

    // ==========================================
    // 2. NGUONC
    // ==========================================
    const nguonc = config.sources.nguonc;
    if (nguonc) {
        process.stdout.write(`Đang kiểm tra [${nguonc.name}] (${nguonc.api_base})... `);
        let res = await checkUrl(`https://phim.nguonc.com/api/films/phim-moi-cap-nhat?page=1`);
        let valid = res.ok && isValidMovieApi(res.body, 'nguonc');

        if (!valid && res.status === 403) {
            valid = true; // Cloudflare chặn datacenter nhưng app tại VN truy cập tốt
            console.log(`🛡️ HOẠT ĐỘNG TẠI VN (Cloudflare chặn US Data Center: HTTP 403 - ${res.latency}ms)`);
            results.push({ name: nguonc.name, url: nguonc.api_base, status: 'OK', latency: res.latency });
        } else if (valid) {
            console.log(`✅ HOẠT ĐỘNG TỐT (${res.latency}ms)`);
            results.push({ name: nguonc.name, url: nguonc.api_base, status: 'OK', latency: res.latency });
        } else if (nguonc.enabled) {
            console.log(`❌ BỊ LỖI ➔ Tạm tắt NguonC để chống lag`);
            nguonc.enabled = false;
            hasChanges = true;
            actionsLog.push(`NguonC -> Tự động loại bỏ (enabled: false)`);
            results.push({ name: nguonc.name, url: nguonc.api_base, status: 'DISABLED', error: res.error });
        }
    }

    // ==========================================
    // 3. COBEPHIM
    // ==========================================
    const cobe = config.sources.cobephim;
    if (cobe) {
        process.stdout.write(`Đang kiểm tra [${cobe.name}] (${cobe.api_base})... `);
        const backendUrl = (cobe.api_backend || 'https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz') + '/api/v1/movies/by-category/co-trang?page=1&limit=1';
        let bRes = await checkUrl(backendUrl);
        let fRes = await checkUrl(cobe.api_base);
        let bOk = bRes.ok && isValidMovieApi(bRes.body, 'cobe_backend');
        let fOk = fRes.ok && !isPoisonedOrParked(fRes.body);

        if (!fOk || !bOk) {
            console.log(`⚠️ Phát hiện CoBePhim đổi domain ➔ Tự động quét landing cobelink.xyz...`);
            const discovered = await discoverCoBeDomainsFromLanding();
            const candidates = [...discovered, 'https://cobephim.tv', 'https://cobephim.club', 'https://cobephim.ws', 'https://cobephim.ink'];
            let found = null;
            for (const cand of [...new Set(candidates)]) {
                if (cand === cobe.api_base) continue;
                process.stdout.write(`   ↳ Thử domain [${cand}]... `);
                const tRes = await checkUrl(cand, 5000, true);
                if (tRes.ok && !isPoisonedOrParked(tRes.body)) {
                    console.log(`✅ SỐNG TỐT (${tRes.latency}ms)`);
                    found = cand;
                    fOk = true;
                    break;
                } else {
                    console.log(`❌ Lỗi`);
                }
            }

            if (found) {
                console.log(`   ✨ TỰ ĐỘNG VÁ: Chuyển CoBePhim sang ${found}`);
                cobe.api_base = found;
                cobe.enabled = true;
                hasChanges = true;
                actionsLog.push(`CoBePhim -> Cập nhật domain sống: ${found}`);
            } else if (!bOk && cobe.enabled) {
                console.log(`   🚫 TỰ LOẠI BỎ: Cả frontend và backend CoBePhim đều chết ➔ Tạm tắt`);
                cobe.enabled = false;
                hasChanges = true;
                actionsLog.push(`CoBePhim -> Tự động loại bỏ (enabled: false)`);
            }
        }

        if (bOk || fOk) {
            const lat = bRes.latency || fRes.latency || 250;
            console.log(`✅ HOẠT ĐỘNG TỐT (${lat}ms)`);
            results.push({ name: cobe.name, url: cobe.api_base, status: 'OK', latency: lat });
        } else {
            results.push({ name: cobe.name, url: cobe.api_base, status: 'DISABLED', error: 'Nguồn tạm dừng' });
        }
    }

    // ==========================================
    // 4. OPHIM (Tự Vá Mirror Hoặc Tự Loại Bỏ)
    // ==========================================
    const ophim = config.sources.ophim;
    if (ophim) {
        process.stdout.write(`Đang kiểm tra [${ophim.name}] (${ophim.api_base})... `);
        let res = await checkUrl(`${ophim.api_base}/danh-sach/phim-moi-cap-nhat?page=1`);
        let valid = res.ok && isValidMovieApi(res.body, 'ophim');

        if (!valid) {
            console.log(`⚠️ Lỗi / Parked domain ➔ Đang tìm kiếm mirror OPhim thay thế...`);
            const pool = Array.isArray(ophim.mirrors) ? ophim.mirrors : [];
            const candidates = [...pool, 'https://ophim.cc', 'https://ophim1.com', 'https://ophim17.cc', 'https://ophim8.cc', 'https://ophim.live', 'https://ophim9.cc'];
            let found = null;
            for (const mirror of [...new Set(candidates)]) {
                if (mirror === ophim.api_base) continue;
                process.stdout.write(`   ↳ Thử mirror [${mirror}]... `);
                const tRes = await checkUrl(`${mirror}/danh-sach/phim-moi-cap-nhat?page=1`);
                if (tRes.ok && isValidMovieApi(tRes.body, 'ophim')) {
                    console.log(`✅ SỐNG TỐT (${tRes.latency}ms)`);
                    found = mirror;
                    valid = true;
                    break;
                } else {
                    console.log(`❌ Lỗi`);
                }
            }

            if (found) {
                console.log(`   ✨ TỰ ĐỘNG VÁ: Chuyển OPhim sang mirror mới: ${found}`);
                ophim.api_base = found;
                ophim.enabled = true;
                hasChanges = true;
                actionsLog.push(`OPhim -> Cập nhật mirror sống: ${found}`);
            } else if (ophim.enabled) {
                console.log(`   🚫 TỰ LOẠI BỎ: OPhim hiện bị sập/parked domain và chưa có mirror sống ➔ TỰ TẮT để app TV mượt mà`);
                ophim.enabled = false;
                hasChanges = true;
                actionsLog.push(`OPhim -> Tự động loại bỏ (enabled: false) chống nghẽn TV`);
            }
        } else if (!ophim.enabled) {
            console.log(`   ♻️ TỰ PHỤC HỒI: OPhim đã sống trở lại ➔ Tự động bật lại`);
            ophim.enabled = true;
            hasChanges = true;
            actionsLog.push(`OPhim -> Tự động phục hồi (enabled: true)`);
        }

        if (valid) {
            console.log(`✅ HOẠT ĐỘNG TỐT (${res.latency}ms)`);
            results.push({ name: ophim.name, url: ophim.api_base, status: 'OK', latency: res.latency });
        } else {
            console.log(`🔒 ĐÃ TỰ ĐỘNG CÁCH LY NGUỒN CHẾT THÀNH CÔNG`);
            results.push({ name: ophim.name, url: ophim.api_base, status: 'DISABLED', error: 'Đã tạm tắt chống giật lag' });
        }
    }

    // ==========================================
    // 5. YTS 4K MIRRORS
    // ==========================================
    const yts = config.sources.torrent4k;
    if (yts) {
        process.stdout.write(`Đang kiểm tra các mirror 4K Cinema... `);
        const candidates = ['https://yts.gg', 'https://yts.lt', 'https://yts.bz', 'https://yts.torrentbay.st', 'https://yts.rs', 'https://yts.mx'];
        const working = [];
        for (const mirror of candidates) {
            const res = await checkUrl(`${mirror}/api/v2/list_movies.json?limit=1`);
            if (res.ok && isValidMovieApi(res.body, 'torrent4k')) {
                working.push({ url: mirror, latency: res.latency });
            }
        }

        if (working.length > 0) {
            working.sort((a, b) => a.latency - b.latency);
            const sorted = working.map(w => w.url);
            console.log(`✅ HOẠT ĐỘNG TỐT (${sorted[0]} - ${working[0].latency}ms)`);
            if (JSON.stringify(sorted) !== JSON.stringify(yts.mirrors)) {
                yts.mirrors = sorted;
                hasChanges = true;
                actionsLog.push(`YTS 4K -> Tự đảo mirror nhanh nhất: ${sorted[0]}`);
            }
            results.push({ name: yts.name, url: sorted[0], status: 'OK', latency: working[0].latency });
        } else {
            console.log(`⚠️ Tất cả mirror 4K đều lỗi tạm thời`);
            results.push({ name: yts.name, url: 'YTS Mirrors', status: 'WARNING', error: 'Tất cả mirror 4K lỗi tạm thời' });
        }
    }

    // ==========================================
    // 6. LƯU CẤU HÌNH & GHI CỜ TỰ BUILD APK MỚI
    // ==========================================
    if (hasChanges) {
        console.log('\n====================================================');
        console.log('🎉 ĐÃ TỰ ĐỘNG CHỮA LÀNH & CẬP NHẬT CẤU HÌNH THÀNH CÔNG:');
        actionsLog.forEach(a => console.log(`   ⚡ ${a}`));

        config.last_updated = new Date().toISOString().split('T')[0];
        fs.writeFileSync(configPath, JSON.stringify(config, null, 2), 'utf8');

        // Đồng bộ file .config (chỉ ghi các nguồn đang enabled=true)
        if (fs.existsSync(dotConfigPath)) {
            const enabledUrls = [
                config.sources.kkphim?.enabled ? config.sources.kkphim?.api_base : null,
                config.sources.ophim?.enabled ? config.sources.ophim?.api_base : null,
                config.sources.nguonc?.enabled ? config.sources.nguonc?.api_base : null,
                config.sources.cobephim?.enabled ? config.sources.cobephim?.api_base : null
            ].filter(Boolean);

            const dotContent = [
                '# ========================================================',
                '# DANH SÁCH NGUỒN PHIM DUYTV (TỰ ĐỘNG LÀM SẠCH BỞI GITHUB BOT)',
                '# Nguồn chết đã tự động bị loại bỏ để bảo vệ tốc độ app.',
                '# ========================================================',
                ...enabledUrls
            ].join('\n') + '\n';
            fs.writeFileSync(dotConfigPath, dotContent, 'utf8');
        }

        // Tạo file cờ để GitHub Actions kích hoạt auto-commit & build APK mới
        fs.writeFileSync(flagPath, actionsLog.join('\n'), 'utf8');
        console.log(`\n📌 Đã xuất cờ kích hoạt tự động Build lại APK: ${flagPath}`);
    }

    console.log('\n====================================================');
    console.log('✨ QUÁ TRÌNH KIỂM TRA & TỰ VÁ NGUỒN HOÀN TẤT THÀNH CÔNG 100%!');
    process.exit(0);
}

run();
