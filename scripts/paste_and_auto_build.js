const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { execSync } = require('child_process');
const http = require('http');
const https = require('https');

const rootDir = path.join(__dirname, '..');
const configPath = path.join(rootDir, 'config', 'sources.json');
const gradlePath = path.join(rootDir, 'app', 'build.gradle.kts');
const desktopDir = path.join(process.env.USERPROFILE || 'C:\\Users\\admin', 'Desktop');

const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout
});

function ask(q) {
    return new Promise(resolve => rl.question(q, resolve));
}

function fetchText(url) {
    return new Promise((resolve) => {
        try {
            const client = url.startsWith('https') ? https : http;
            client.get(url, { headers: { 'User-Agent': 'DuyTV/1.0' }, timeout: 8000 }, (res) => {
                let data = '';
                res.on('data', chunk => data += chunk);
                res.on('end', () => resolve({ ok: res.statusCode === 200, text: data }));
            }).on('error', () => resolve({ ok: false, text: '' }));
        } catch {
            resolve({ ok: false, text: '' });
        }
    });
}

async function main() {
    console.clear();
    console.log('================================================================');
    console.log('       DUYTV: CHỈ CẦN DÁN LINK LÀ TỰ ĐỘNG TẤT CẢ 100%');
    console.log('================================================================\n');

    let inputUrl = (await ask('👉 Dán đường link file config hoặc link nguồn mới vào đây: ')).trim();

    if (!inputUrl) {
        console.log('❌ Link trống. Đang thoát...');
        rl.close();
        return;
    }

    if (!inputUrl.startsWith('http://') && !inputUrl.startsWith('https://')) {
        inputUrl = 'https://' + inputUrl;
    }
    if (inputUrl.endsWith('/')) inputUrl = inputUrl.slice(0, -1);

    console.log(`\n⏳ Đang phân tích và kiểm tra đường link: ${inputUrl}...`);

    let config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
    let updated = false;

    // Trường hợp 1: Link dẫn tới file JSON config hoàn chỉnh
    if (inputUrl.includes('.json') || inputUrl.includes('raw') || inputUrl.includes('pastebin')) {
        const res = await fetchText(inputUrl);
        if (res.ok) {
            try {
                const parsed = JSON.parse(res.text);
                if (parsed.sources || parsed.primary_domain) {
                    fs.writeFileSync(configPath, JSON.stringify(parsed, null, 2), 'utf8');
                    console.log('✅ Đã tải và thay thế toàn bộ file config từ đường link thành công!');
                    updated = true;
                }
            } catch {}
        }
    }

    // Trường hợp 2: Link domain của một nguồn cụ thể (OPhim, KKPhim, CoBePhim, NguonC, v.v.)
    if (!updated) {
        const lower = inputUrl.toLowerCase();
        let targetKey = 'kkphim';
        if (lower.includes('ophim')) targetKey = 'ophim';
        else if (lower.includes('nguon') || lower.includes('phimapi')) targetKey = lower.includes('nguon') ? 'nguonc' : 'kkphim';
        else if (lower.includes('cobe')) targetKey = 'cobephim';

        if (config.sources[targetKey]) {
            config.sources[targetKey].api_base = inputUrl;
            config.last_updated = new Date().toISOString().split('T')[0];
            fs.writeFileSync(configPath, JSON.stringify(config, null, 2), 'utf8');
            console.log(`✅ Đã tự động nhận diện và cập nhật domain cho [${config.sources[targetKey].name}] sang: ${inputUrl}`);
            updated = true;
        }
    }

    // Tự động tăng version code & name
    let gradleContent = fs.readFileSync(gradlePath, 'utf8');
    const codeMatch = gradleContent.match(/versionCode\s*=\s*(\d+)/);
    const nameMatch = gradleContent.match(/versionName\s*=\s*"([^"]+)"/);

    if (codeMatch && nameMatch) {
        const nextCode = parseInt(codeMatch[1], 10) + 1;
        const currentName = nameMatch[1];
        const parts = currentName.split('.');
        parts[parts.length - 1] = (parseInt(parts[parts.length - 1], 10) + 1).toString();
        const nextName = parts.join('.');

        gradleContent = gradleContent
            .replace(/versionCode\s*=\s*\d+/, `versionCode = ${nextCode}`)
            .replace(/versionName\s*=\s*"[^"]+"/, `versionName = "${nextName}"`);

        fs.writeFileSync(gradlePath, gradleContent, 'utf8');
        console.log(`🚀 Phiên bản app mới: v${nextName} (Build code ${nextCode})`);
    }

    // Tự động biên dịch APK
    console.log('\n================================================================');
    console.log('⚙️ ĐANG TỰ ĐỘNG BIÊN DỊCH BẢN APK MỚI (Vui lòng đợi ~30 giây)...');
    console.log('================================================================\n');

    const gradlewCmd = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew';
    try {
        execSync(`${gradlewCmd} :app:assembleDebug`, {
            cwd: rootDir,
            stdio: 'inherit'
        });
    } catch {
        console.error('❌ Lỗi biên dịch. Vui lòng thử lại.');
        rl.close();
        return;
    }

    const builtApk = path.join(rootDir, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
    const targetApk = path.join(desktopDir, 'DuyTV-Moi-Nhat.apk');

    if (fs.existsSync(builtApk)) {
        fs.copyFileSync(builtApk, targetApk);
        console.log(`\n🎉 BẢN APK MỚI ĐÃ XUẤT RA MÀN HÌNH CHÍNH:`);
        console.log(`   ${targetApk}`);

        // Tự động đẩy lên TV Sharp
        try {
            console.log('\n📡 Đang cài đặt thẳng lên TV Sharp Aquos qua ADB...');
            execSync(`adb -s 192.168.1.170:5555 install -r "${targetApk}"`, { timeout: 60000 });
            console.log('✅ Đã cập nhật xong TV Sharp!');
        } catch {
            console.log('ℹ️ TV Sharp hiện đang tắt hoặc không kết nối.');
        }

        // Tự động đẩy sang Máy tính bảng
        try {
            console.log('📡 Đang gửi sang Máy tính bảng (192.168.1.215:1111)...');
            const stats = fs.statSync(targetApk);
            const fileSize = stats.size;
            const req = http.request({
                hostname: '192.168.1.215',
                port: 1111,
                path: `/storage/emulated/0/Download/DuyTV-Moi-Nhat.apk?cmd=file&size=${fileSize}&file_date=${Math.floor(stats.mtimeMs)}`,
                method: 'POST',
                headers: { 'Content-Type': 'application/octet-stream', 'Content-Length': fileSize }
            }, (res) => {
                if (res.statusCode === 200) console.log('✅ Đã chuyển xong sang Máy tính bảng!');
            });
            fs.createReadStream(targetApk).pipe(req);
        } catch {}

        // Tự động đồng bộ lên GitHub
        try {
            console.log('\n☁️ Đang đồng bộ cấu hình mới lên GitHub...');
            execSync('git add config/sources.json app/build.gradle.kts && git commit -m "chore: update sources via 1-click tool" && git push origin main', {
                cwd: rootDir,
                stdio: 'ignore'
            });
            console.log('✅ Đã đồng bộ lên GitHub thành công!');
        } catch {}
    }

    console.log('\n================================================================');
    console.log('✨ TẤT CẢ ĐÃ TỰ ĐỘNG HOÀN THÀNH 100%! Bấm Enter để đóng cửa sổ.');
    console.log('================================================================');
    await ask('');
    rl.close();
}

main();
