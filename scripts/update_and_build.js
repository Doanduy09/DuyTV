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

function ask(question) {
    return new Promise(resolve => rl.question(question, resolve));
}

function testUrl(url) {
    return new Promise((resolve) => {
        try {
            const client = url.startsWith('https') ? https : http;
            const req = client.get(url, { timeout: 6000 }, (res) => {
                resolve(res.statusCode >= 200 && res.statusCode < 400);
            });
            req.on('error', () => resolve(false));
            req.on('timeout', () => { req.destroy(); resolve(false); });
        } catch {
            resolve(false);
        }
    });
}

async function main() {
    console.clear();
    console.log('====================================================');
    console.log('       CÔNG CỤ CẬP NHẬT NGUỒN DUYTV TỰ ĐỘNG');
    console.log('====================================================\n');

    const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
    const sources = config.sources;

    console.log('Danh sách nguồn phim hiện tại:');
    console.log(`  [1] KKPhim   : ${sources.kkphim?.api_base || 'N/A'}`);
    console.log(`  [2] OPhim    : ${sources.ophim?.api_base || 'N/A'}`);
    console.log(`  [3] NguonC   : ${sources.nguonc?.api_base || 'N/A'}`);
    console.log(`  [4] CoBePhim : ${sources.cobephim?.api_base || 'N/A'}`);
    console.log(`  [5] Chỉ Build lại APK ngay (không đổi nguồn)`);
    console.log(`  [6] Dọn sạch các bản Release & APK cũ trên GitHub`);
    console.log('====================================================');

    const choice = (await ask('\n👉 Chọn thao tác [1-6]: ')).trim();

    if (choice === '6') {
        const keepInput = (await ask('\n👉 Nhập số bản mới nhất muốn giữ lại trên GitHub (mặc định 1): ')).trim() || '1';
        try {
            execSync(`node "${path.join(__dirname, 'clean_old_releases.js')}" ${keepInput}`, {
                cwd: rootDir,
                stdio: 'inherit'
            });
        } catch (err) {
            console.error('❌ Lỗi khi dọn dẹp GitHub Releases.');
        }
        rl.close();
        return;
    }

    let targetKey = null;
    let targetName = '';

    if (choice === '1') { targetKey = 'kkphim'; targetName = 'KKPhim'; }
    else if (choice === '2') { targetKey = 'ophim'; targetName = 'OPhim'; }
    else if (choice === '3') { targetKey = 'nguonc'; targetName = 'NguonC'; }
    else if (choice === '4') { targetKey = 'cobephim'; targetName = 'CoBePhim'; }
    else if (choice !== '5') {
        console.log('Lựa chọn không hợp lệ. Đang thoát...');
        rl.close();
        return;
    }

    if (targetKey) {
        let newUrl = (await ask(`\n👉 Dán link/domain mới cho ${targetName} (ví dụ https://ophim.cc): `)).trim();
        if (!newUrl.startsWith('http://') && !newUrl.startsWith('https://')) {
            newUrl = 'https://' + newUrl;
        }
        // Bỏ dấu gạch chéo cuối nếu có
        if (newUrl.endsWith('/')) newUrl = newUrl.slice(0, -1);

        console.log(`\nĐang kiểm tra kết nối tới: ${newUrl}...`);
        const isOk = await testUrl(newUrl);
        if (isOk) {
            console.log('✅ Kết nối thành công! Domain đang hoạt động tốt.');
        } else {
            console.log('⚠️ Cảnh báo: Domain chưa phản hồi ngay (vẫn sẽ cập nhật theo yêu cầu).');
        }

        // Cập nhật vào sources.json
        sources[targetKey].api_base = newUrl;
        config.last_updated = new Date().toISOString().split('T')[0];
        fs.writeFileSync(configPath, JSON.stringify(config, null, 2), 'utf8');
        console.log(`✅ Đã lưu nguồn mới vào file config/sources.json`);
    }

    // Tự động tăng version
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
        console.log(`\n🚀 Tự động nâng phiên bản: v${nextName} (Build code ${nextCode})`);
    }

    // Tiến hành biên dịch APK
    console.log('\n====================================================');
    console.log('⏳ ĐANG TỰ ĐỘNG BIÊN DỊCH BẢN APK MỚI (Vui lòng đợi ~30s)...');
    console.log('====================================================\n');

    const gradlewCmd = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew';
    try {
        execSync(`${gradlewCmd} :app:assembleDebug`, {
            cwd: rootDir,
            stdio: 'inherit'
        });
    } catch (err) {
        console.error('❌ Lỗi biên dịch Gradle. Vui lòng kiểm tra lại mã nguồn.');
        rl.close();
        return;
    }

    const builtApk = path.join(rootDir, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
    const targetApk = path.join(desktopDir, 'DuyTV-Moi-Nhat.apk');

    if (fs.existsSync(builtApk)) {
        fs.copyFileSync(builtApk, targetApk);
        console.log(`\n🎉 ĐÃ XUẤT APK THÀNH CÔNG RA MÀN HÌNH:`);
        console.log(`   ${targetApk}`);

        // Tự động cài đặt lên TV Sharp nếu đang kết nối ADB
        try {
            console.log('\n📡 Đang kiểm tra TV Sharp qua ADB (192.168.1.170:5555)...');
            const adbCheck = execSync('adb -s 192.168.1.170:5555 get-state', { timeout: 3000 }).toString();
            if (adbCheck.includes('device')) {
                console.log('📲 Đang tự động cài bản mới lên TV Sharp...');
                execSync(`adb -s 192.168.1.170:5555 install -r "${targetApk}"`, { timeout: 60000 });
                console.log('✅ Đã cập nhật xong TV Sharp!');
            }
        } catch {
            console.log('ℹ️ TV Sharp hiện không kết nối hoặc đang tắt. Bỏ qua tự động cài.');
        }

        // Tự động gửi sang Tablet nếu X-plore đang mở
        try {
            console.log('\n📡 Đang gửi bản mới sang Máy tính bảng (192.168.1.215:1111)...');
            const stats = fs.statSync(targetApk);
            const fileSize = stats.size;
            const req = http.request({
                hostname: '192.168.1.215',
                port: 1111,
                path: `/storage/emulated/0/Download/DuyTV-Moi-Nhat.apk?cmd=file&size=${fileSize}&file_date=${Math.floor(stats.mtimeMs)}`,
                method: 'POST',
                headers: { 'Content-Type': 'application/octet-stream', 'Content-Length': fileSize }
            }, (res) => {
                if (res.statusCode === 200) {
                    console.log('✅ Đã gửi bản mới sang Máy tính bảng (thư mục Download)!');
                }
            });
            fs.createReadStream(targetApk).pipe(req);
        } catch {}
    }

    console.log('\n====================================================');
    console.log('✨ HOÀN TẤT 100%! Bấm phím bất kỳ để đóng cửa sổ này.');
    console.log('====================================================');
    await ask('');
    rl.close();
}

main();
