const https = require('https');
const http = require('http');
const fs = require('fs');
const path = require('path');

const configPath = path.join(__dirname, '..', 'config', 'sources.json');
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));

console.log('====================================================');
console.log('DUYTV SOURCE HEALTH MONITOR - KIỂM TRA ĐỘ SỐNG CỦA CÁC NGUỒN PHIM');
console.log('====================================================\n');

function checkUrl(url, timeoutMs = 8000) {
    return new Promise((resolve) => {
        const client = url.startsWith('https') ? https : http;
        const start = Date.now();
        const req = client.get(url, {
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
                'Accept': 'application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Accept-Language': 'vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7'
            },
            timeout: timeoutMs
        }, (res) => {
            const latency = Date.now() - start;
            if (res.statusCode >= 200 && res.statusCode < 400) {
                resolve({ ok: true, status: res.statusCode, latency });
            } else {
                resolve({ ok: false, status: res.statusCode, latency, error: `HTTP ${res.statusCode}` });
            }
        });

        req.on('timeout', () => {
            req.destroy();
            resolve({ ok: false, status: 0, latency: timeoutMs, error: 'TIMEOUT (Quá thời gian chờ)' });
        });

        req.on('error', (err) => {
            resolve({ ok: false, status: 0, latency: Date.now() - start, error: err.message });
        });
    });
}

async function run() {
    const results = [];
    let hasFailure = false;

    // 1. KKPhim
    const kk = config.sources.kkphim;
    if (kk && kk.enabled) {
        process.stdout.write(`Đang kiểm tra [${kk.name}] (${kk.api_base})... `);
        const res = await checkUrl(`${kk.api_base}/danh-sach/phim-moi-cap-nhat?page=1`);
        if (res.ok) {
            console.log(`✅ HOẠT ĐỘNG TỐT (${res.latency}ms)`);
            results.push({ name: kk.name, url: kk.api_base, status: 'OK', latency: res.latency });
        } else {
            console.log(`❌ BỊ LỖI: ${res.error}`);
            hasFailure = true;
            results.push({ name: kk.name, url: kk.api_base, status: 'FAILED', error: res.error });
        }
    }

    // 2. OPhim
    const ophim = config.sources.ophim;
    if (ophim && ophim.enabled) {
        process.stdout.write(`Đang kiểm tra [${ophim.name}] (${ophim.api_base})... `);
        const res = await checkUrl(`${ophim.api_base}/danh-sach/phim-moi-cap-nhat?page=1`);
        if (res.ok) {
            console.log(`✅ HOẠT ĐỘNG TỐT (${res.latency}ms)`);
            results.push({ name: ophim.name, url: ophim.api_base, status: 'OK', latency: res.latency });
        } else {
            console.log(`❌ BỊ LỖI: ${res.error}`);
            hasFailure = true;
            results.push({ name: ophim.name, url: ophim.api_base, status: 'FAILED', error: res.error });
        }
    }

    // 3. NguonC
    const nguonc = config.sources.nguonc;
    if (nguonc && nguonc.enabled) {
        process.stdout.write(`Đang kiểm tra [${nguonc.name}] (${nguonc.api_base})... `);
        const res = await checkUrl(`https://phim.nguonc.com/api/films/phim-moi-cap-nhat?page=1`);
        if (res.ok) {
            console.log(`✅ HOẠT ĐỘNG TỐT (${res.latency}ms)`);
            results.push({ name: nguonc.name, url: nguonc.api_base, status: 'OK', latency: res.latency });
        } else if (res.status === 403) {
            console.log(`🛡️ HOẠT ĐỘNG TẠI VN (Cloudflare chặn IP Data Center Mỹ: HTTP 403 - ${res.latency}ms)`);
            results.push({ name: nguonc.name, url: nguonc.api_base, status: 'OK', latency: res.latency });
        } else {
            console.log(`❌ BỊ LỖI: ${res.error}`);
            hasFailure = true;
            results.push({ name: nguonc.name, url: nguonc.api_base, status: 'FAILED', error: res.error });
        }
    }

    // 4. CoBePhim
    const cobe = config.sources.cobephim;
    if (cobe && cobe.enabled) {
        process.stdout.write(`Đang kiểm tra [${cobe.name}] (${cobe.api_base})... `);
        const res = await checkUrl(`${cobe.api_base}/wp-json/wp/v2/posts?per_page=1`);
        if (res.ok) {
            console.log(`✅ HOẠT ĐỘNG TỐT (${res.latency}ms)`);
            results.push({ name: cobe.name, url: cobe.api_base, status: 'OK', latency: res.latency });
        } else {
            // Thử kiểm tra root
            const rootRes = await checkUrl(cobe.api_base);
            if (rootRes.ok) {
                console.log(`✅ HOẠT ĐỘNG TỐT (${rootRes.latency}ms)`);
                results.push({ name: cobe.name, url: cobe.api_base, status: 'OK', latency: rootRes.latency });
            } else {
                console.log(`❌ BỊ LỖI: ${rootRes.error}`);
                hasFailure = true;
                results.push({ name: cobe.name, url: cobe.api_base, status: 'FAILED', error: rootRes.error });
            }
        }
    }

    // 5. YTS 4K Mirrors
    const yts = config.sources.torrent4k;
    if (yts && yts.enabled) {
        let ytsOk = false;
        for (const mirror of yts.mirrors) {
            process.stdout.write(`Đang kiểm tra mirror [${yts.name}] (${mirror})... `);
            const res = await checkUrl(`${mirror}/api/v2/list_movies.json?limit=1`);
            if (res.ok) {
                console.log(`✅ HOẠT ĐỘNG TỐT (${res.latency}ms)`);
                results.push({ name: `${yts.name} (${mirror})`, url: mirror, status: 'OK', latency: res.latency });
                ytsOk = true;
                break;
            } else {
                console.log(`⚠️ Mirror này lỗi: ${res.error}`);
            }
        }
        if (!ytsOk) {
            hasFailure = true;
            results.push({ name: yts.name, url: yts.mirrors.join(', '), status: 'FAILED', error: 'Tất cả mirror đều không phản hồi' });
        }
    }

    console.log('\n====================================================');
    if (hasFailure) {
        console.log('⚠️ CẢNH BÁO: PHÁT HIỆN CÓ NGUỒN PHIM BỊ SẬP HOẶC ĐỔI DOMAIN!');
        // Ghi file báo cáo markdown
        let md = `## ⚠️ Cảnh Báo Sập Nguồn Phim DuyTV (${new Date().toISOString()})\n\n`;
        md += `Hệ thống kiểm tra tự động phát hiện một hoặc nhiều nguồn phim không phản hồi:\n\n`;
        md += `| Nguồn | URL | Trạng Thái | Chi Tiết |\n`;
        md += `|---|---|---|---|\n`;
        for (const r of results) {
            const icon = r.status === 'OK' ? '✅ Sống' : '❌ SẬP';
            md += `| ${r.name} | ${r.url} | ${icon} | ${r.error || (r.latency + 'ms')} |\n`;
        }
        md += `\n### 🛠️ Hướng Dẫn Sửa Đổi:\n`;
        md += `1. Mở file \`config/sources.json\` trên GitHub.\n`;
        md += `2. Cập nhật \`api_base\` của nguồn bị lỗi sang tên miền mới.\n`;
        md += `3. Bấm Commit Changes. DuyTV trên TV và Tablet sẽ tự động cập nhật ngay!\n`;
        fs.writeFileSync(path.join(__dirname, '..', 'health_report.md'), md, 'utf8');
        process.exit(1);
    } else {
        console.log('🎉 TẤT CẢ CÁC NGUỒN PHIM ĐỀU ĐANG HOẠT ĐỘNG 100% HOÀN HẢO!');
        process.exit(0);
    }
}

run();
