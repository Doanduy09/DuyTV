#!/usr/bin/env node

const { execSync } = require('child_process');

const REPO = process.env.GITHUB_REPOSITORY || 'Doanduy09/DuyTV';
const KEEP_COUNT = Math.max(1, parseInt(process.env.KEEP_RELEASES || process.argv[2] || '1', 10));

function resolveToken() {
    if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN.trim();
    try {
        return execSync('gh auth token', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
    } catch {
        return '';
    }
}

const TOKEN = resolveToken();
if (!TOKEN) {
    console.error('❌ Thiếu GITHUB_TOKEN và chưa đăng nhập gh CLI.');
    process.exit(1);
}

const headers = {
    'Accept': 'application/vnd.github+json',
    'Authorization': `Bearer ${TOKEN}`,
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'DuyTV-Release-Cleaner'
};

async function ghApi(path, options = {}) {
    const res = await fetch(`https://api.github.com/repos/${REPO}${path}`, {
        headers,
        ...options
    });
    if (res.status === 204 || res.status === 404) return null;
    if (!res.ok) {
        const text = await res.text();
        throw new Error(`GitHub API ${options.method || 'GET'} ${path} lỗi (${res.status}): ${text}`);
    }
    return res.json();
}

async function run() {
    console.log(`🧹 Đang kiểm tra kho [${REPO}] — Chế độ giữ lại ${KEEP_COUNT} bản mới nhất...`);

    // 1. Lấy toàn bộ danh sách Releases (tối đa 100 bản/trang)
    const releases = (await ghApi('/releases?per_page=100')) || [];
    releases.sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());

    const toKeep = releases.slice(0, KEEP_COUNT);
    const toDelete = releases.slice(KEEP_COUNT);
    const keptTags = new Set(toKeep.map(r => r.tag_name).filter(Boolean));

    console.log(`📌 Giữ lại (${toKeep.length}):`);
    toKeep.forEach(r => console.log(`   ✅ ${r.tag_name} — ${r.name || ''}`));

    // 2. Xóa các bản Release cũ
    if (toDelete.length > 0) {
        console.log(`\n🗑️ Đang xóa ${toDelete.length} bản Release cũ...`);
        for (const rel of toDelete) {
            try {
                await ghApi(`/releases/${rel.id}`, { method: 'DELETE' });
                console.log(`   🗑️ Đã xóa Release: ${rel.tag_name} (${rel.name || rel.id})`);
            } catch (err) {
                console.error(`   ⚠️ Không thể xóa Release ${rel.tag_name}: ${err.message}`);
            }
        }
    } else {
        console.log(`\n✅ Số lượng Release hiện tại (${releases.length}) đã gọn gàng.`);
    }

    // 3. Xóa toàn bộ Git Tags cũ / Tag mồ côi không thuộc bản giữ lại
    const tagRefs = (await ghApi('/git/refs/tags?per_page=100')) || [];
    const oldTags = tagRefs
        .map(ref => ref.ref.replace(/^refs\/tags\//, ''))
        .filter(tagName => !keptTags.has(tagName));

    if (oldTags.length > 0) {
        console.log(`\n🏷️ Đang dọn ${oldTags.length} Git Tags cũ trên GitHub...`);
        for (const tag of oldTags) {
            try {
                await ghApi(`/git/refs/tags/${encodeURIComponent(tag)}`, { method: 'DELETE' });
                console.log(`   🗑️ Đã xóa Tag: ${tag}`);
            } catch (err) {
                console.error(`   ⚠️ Không thể xóa Tag ${tag}: ${err.message}`);
            }
        }
    }

    // 4. Xóa các gói Artifacts APK cũ trong GitHub Actions để giải phóng dung lượng
    try {
        const artifactsData = await ghApi('/actions/artifacts?per_page=100');
        const artifacts = (artifactsData && artifactsData.artifacts) || [];
        artifacts.sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());
        const oldArtifacts = artifacts.slice(KEEP_COUNT);
        if (oldArtifacts.length > 0) {
            console.log(`\n📦 Đang dọn ${oldArtifacts.length} gói Artifacts APK cũ trong Actions...`);
            for (const art of oldArtifacts) {
                try {
                    await ghApi(`/actions/artifacts/${art.id}`, { method: 'DELETE' });
                } catch {}
            }
            console.log(`   ✅ Đã giải phóng ${oldArtifacts.length} gói Artifacts cũ.`);
        }
    } catch {}

    // 5. Xóa toàn bộ lịch sử Workflow Runs cũ đã hoàn tất (Completed) trong tab GitHub Actions,
    // chỉ giữ lại các tiến trình đang chạy (in_progress/queued) hoặc KEEP_COUNT bản chạy mới nhất.
    try {
        const runsData = await ghApi('/actions/runs?per_page=100');
        const runs = (runsData && runsData.workflow_runs) || [];
        runs.sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime());

        const currentRunId = process.env.GITHUB_RUN_ID ? Number(process.env.GITHUB_RUN_ID) : null;
        const activeRuns = runs.filter(r => r.status !== 'completed' || r.id === currentRunId);
        const completedRuns = runs.filter(r => r.status === 'completed' && r.id !== currentRunId);

        const keepCompletedCount = activeRuns.length > 0 ? 0 : KEEP_COUNT;
        const runsToDelete = completedRuns.slice(keepCompletedCount);

        if (runsToDelete.length > 0) {
            console.log(`\n⚡ Đang dọn sạch ${runsToDelete.length} bản build cũ trong GitHub Actions...`);
            for (const runItem of runsToDelete) {
                try {
                    await ghApi(`/actions/runs/${runItem.id}`, { method: 'DELETE' });
                    console.log(`   🗑️ Đã xóa Workflow Run #${runItem.run_number}: ${runItem.display_title || runItem.name}`);
                } catch (err) {
                    console.error(`   ⚠️ Không thể xóa Workflow Run ${runItem.id}: ${err.message}`);
                }
            }
        } else {
            console.log(`\n✅ Danh sách Workflow Runs trong Actions đã gọn gàng.`);
        }
    } catch (err) {
        console.error(`   ⚠️ Không thể dọn Workflow Runs: ${err.message}`);
    }

    console.log(`\n✨ Hoàn tất làm sạch GitHub! Hiện chỉ giữ lại ${toKeep.length} bản mới nhất.`);
}

run().catch(err => {
    console.error('❌ Lỗi khi dọn dẹp:', err.message);
    process.exit(1);
});
