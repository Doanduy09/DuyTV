const fs = require('fs');
const path = require('path');

const rootDir = path.join(__dirname, '..');
const dotConfigPath = path.join(rootDir, '.config');
const sourcesJsonPath = path.join(rootDir, 'config', 'sources.json');

if (!fs.existsSync(dotConfigPath)) {
    console.log('File .config không tồn tại, bỏ qua đồng bộ.');
    process.exit(0);
}

const content = fs.readFileSync(dotConfigPath, 'utf8');
const lines = content.split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line.length > 0 && !line.startsWith('#'));

console.log(`Đã đọc ${lines.length} đường link từ file .config:`, lines);

let sourcesConfig = {
    version: 1,
    last_updated: new Date().toISOString().split('T')[0],
    sources: {}
};

if (fs.existsSync(sourcesJsonPath)) {
    try {
        sourcesConfig = JSON.parse(fs.readFileSync(sourcesJsonPath, 'utf8'));
    } catch {}
}

if (!sourcesConfig.sources) sourcesConfig.sources = {};

// Duyệt từng link trong .config và tự động phân loại hoặc thêm vào nguồn
lines.forEach((url, idx) => {
    let cleanUrl = url.trim();
    if (!cleanUrl.startsWith('http://') && !cleanUrl.startsWith('https://')) {
        cleanUrl = 'https://' + cleanUrl;
    }
    if (cleanUrl.endsWith('/')) cleanUrl = cleanUrl.slice(0, -1);

    const lower = cleanUrl.toLowerCase();

    // Nhận diện nguồn quen thuộc
    if (lower.includes('ophim')) {
        sourcesConfig.sources.ophim = {
            name: "OPhim",
            api_base: cleanUrl,
            enabled: true
        };
    } else if (lower.includes('phimapi')) {
        sourcesConfig.sources.kkphim = {
            name: "KKPhim",
            api_base: cleanUrl,
            image_cdn: "https://phimimg.com",
            enabled: true
        };
    } else if (lower.includes('nguonc') || lower.includes('phim.nguon')) {
        sourcesConfig.sources.nguonc = {
            name: "NguonC",
            api_base: cleanUrl,
            enabled: true
        };
    } else if (lower.includes('cobe')) {
        sourcesConfig.sources.cobephim = {
            name: "CoBePhim",
            api_base: cleanUrl,
            enabled: true
        };
    } else if (lower.includes('yts')) {
        if (!sourcesConfig.sources.torrent4k) {
            sourcesConfig.sources.torrent4k = { name: "YTS 4K", mirrors: [], enabled: true };
        }
        if (!sourcesConfig.sources.torrent4k.mirrors.includes(cleanUrl)) {
            sourcesConfig.sources.torrent4k.mirrors.push(cleanUrl);
        }
    } else {
        // Nguồn phim mới (ví dụ mephim, rapphim, ...)
        let domainName = '';
        try {
            const u = new URL(cleanUrl);
            domainName = u.hostname.replace('www.', '');
        } catch {
            domainName = `custom_source_${idx + 1}`;
        }

        const key = 'custom_' + domainName.replace(/[^a-zA-Z0-9]/g, '_');
        sourcesConfig.sources[key] = {
            name: domainName.toUpperCase(),
            api_base: cleanUrl,
            enabled: true
        };
    }
});

sourcesConfig.last_updated = new Date().toISOString().split('T')[0];
fs.writeFileSync(sourcesJsonPath, JSON.stringify(sourcesConfig, null, 2), 'utf8');
console.log('✅ Đã cập nhật xong toàn bộ nguồn từ file .config vào sources.json!');
