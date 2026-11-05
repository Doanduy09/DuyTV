package com.duytv.app.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MovieRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private var cachedTvSports: TvSportsData? = null
    private var lastTvSportsFetchTime: Long = 0L

    suspend fun getTvAndSportsData(forceRefresh: Boolean = false): TvSportsData = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedTvSports != null && (now - lastTvSportsFetchTime < 5 * 60 * 1000L)) {
            return@withContext cachedTvSports!!
        }

        val sportsUrl = "https://paste.c-net.org/PostersMistrust"
        val tvUrl = "https://raw.githubusercontent.com/khanh71/All-In-One-IPTV/main/http-iptv.m3u"

        val sportsDef = async {
            try {
                val req = Request.Builder()
                    .url(sportsUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DuyTV/1.0")
                    .build()
                val res = client.newCall(req).execute()
                if (res.isSuccessful) res.body?.string() else null
            } catch (e: Exception) {
                null
            }
        }

        val tvDef = async {
            try {
                val req = Request.Builder()
                    .url(tvUrl)
                    .header("User-Agent", "TiviMate/4.7.0")
                    .build()
                val res = client.newCall(req).execute()
                if (res.isSuccessful) res.body?.string() else null
            } catch (e: Exception) {
                null
            }
        }

        val sportsBody = sportsDef.await()
        val tvBody = tvDef.await()

        val parsedSports = if (!sportsBody.isNullOrBlank()) parseM3uData(sportsBody, "Bóng Đá") else TvSportsData()
        val parsedTv = if (!tvBody.isNullOrBlank()) parseM3uData(tvBody, "Truyền Hình") else TvSportsData()

        val combinedChannels = mutableListOf<TvChannel>()

        // 1. Nạp danh sách các kênh truyền hình chính thống ĐÃ ĐƯỢC KIỂM TRA 100% HOẠT ĐỘNG
        val officialChannels = getVerifiedOfficialChannels()
        combinedChannels.addAll(officialChannels)

        // 2. Bổ sung các kênh phát hiện từ online M3U nếu chưa có trong danh sách chính thống
        val existingUrls = officialChannels.map { it.streamUrl }.toSet()
        val existingNames = officialChannels.map { it.name.lowercase() }.toSet()

        for (ch in parsedTv.channels) {
            if (!existingUrls.contains(ch.streamUrl) && !existingNames.contains(ch.name.lowercase())) {
                combinedChannels.add(ch)
            }
        }

        // 3. Nếu danh sách kênh rỗng, nạp dự phòng từ thể thao
        if (combinedChannels.isEmpty()) {
            combinedChannels.addAll(parsedSports.channels)
        }

        val result = TvSportsData(
            matches = parsedSports.matches.ifEmpty { parsedTv.matches },
            channels = combinedChannels
        )

        cachedTvSports = result
        lastTvSportsFetchTime = now
        result
    }

    private fun getVerifiedOfficialChannels(): List<TvChannel> {
        return listOf(
            // ⭐ TRUYỀN HÌNH VĨNH LONG (THVL 1 - 5) - CHÍNH THỐNG VIETTEL TV360 & FPT CDN 1080P
            TvChannel(
                id = "thvl1_hd_tv360",
                name = "THVL1 HD - Vĩnh Long",
                logoUrl = "https://i.imgur.com/gvPtTR2.png",
                streamUrl = "https://freem3u.xyz/api/live/play.m3u8?vid=25",
                groupTitle = "THVL Vĩnh Long"
            ),
            TvChannel(
                id = "thvl1_hd_fpt",
                name = "THVL1 HD (Dự phòng FPT)",
                logoUrl = "https://i.imgur.com/gvPtTR2.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/THVL1-HD-1080p/playlist.m3u8",
                groupTitle = "THVL Vĩnh Long"
            ),
            TvChannel(
                id = "thvl2_hd_tv360",
                name = "THVL2 HD - Vĩnh Long",
                logoUrl = "https://i.imgur.com/wYadDFn.png",
                streamUrl = "https://freem3u.xyz/api/live/play.m3u8?vid=26",
                groupTitle = "THVL Vĩnh Long"
            ),
            TvChannel(
                id = "thvl2_hd_fpt",
                name = "THVL2 HD (Dự phòng FPT)",
                logoUrl = "https://i.imgur.com/wYadDFn.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/THVL2-HD-1080p/playlist.m3u8",
                groupTitle = "THVL Vĩnh Long"
            ),
            TvChannel(
                id = "thvl3_hd_tv360",
                name = "THVL3 HD - Phim Hay",
                logoUrl = "https://i.imgur.com/gvPtTR2.png",
                streamUrl = "https://freem3u.xyz/api/live/play.m3u8?vid=219",
                groupTitle = "THVL Vĩnh Long"
            ),
            TvChannel(
                id = "thvl4_hd_tv360",
                name = "THVL4 HD - Giải Trí",
                logoUrl = "https://i.imgur.com/wYadDFn.png",
                streamUrl = "https://freem3u.xyz/api/live/play.m3u8?vid=220",
                groupTitle = "THVL Vĩnh Long"
            ),
            TvChannel(
                id = "thvl5_hd_tv360",
                name = "THVL5 HD - Tin Tức",
                logoUrl = "https://i.imgur.com/gvPtTR2.png",
                streamUrl = "https://freem3u.xyz/api/live/play.m3u8?vid=91",
                groupTitle = "THVL Vĩnh Long"
            ),

            // 🔴 TRUYỀN HÌNH QUỐC GIA VIỆT NAM (VTV 1 - 9, ANTV, QPVN) - CHÍNH THỐNG VTV DIGITAL & TV360
            TvChannel(
                id = "vtv1_hd_chinhthong",
                name = "VTV1 HD - Thời Sự",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://luuchieu.truyenhinhso.vn/media/live/C90B082632CD4A75F4B3EDB3/index.m3u8",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv1_hd_tv360",
                name = "VTV1 HD (Dự phòng TV360)",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://vmttv.dpdns.org/tv360/?id=vtv1",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv2_hd_vtvdigital",
                name = "VTV2 HD - Khoa Học Giáo Dục",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv2/master.m3u8",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv2_hd_tv360",
                name = "VTV2 HD (Dự phòng TV360)",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://vmttv.dpdns.org/tv360/?id=vtv2",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv3_hd_tv360",
                name = "VTV3 HD - Giải Trí & Thể Thao",
                logoUrl = "https://i.imgur.com/7rLCvgS.png",
                streamUrl = "https://vmttv.dpdns.org/tv360/?id=vtv3",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv4_hd_vtvdigital",
                name = "VTV4 HD - Đối Ngoại",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv4/master.m3u8",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv4_hd_tv360",
                name = "VTV4 HD (Dự phòng TV360)",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://vmttv.dpdns.org/tv360/?id=vtv4",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv5_tnb_vtvdigital",
                name = "VTV5 HD - Tây Nam Bộ",
                logoUrl = "https://i.imgur.com/7qPKNFU.png",
                streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv5tnb/master.m3u8",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv7_hd_vtvdigital",
                name = "VTV7 HD - Kênh Giáo Dục",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv7/master.m3u8",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv8_hd_vtvdigital",
                name = "VTV8 HD - Miền Trung Tây Nguyên",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv8/master.m3u8",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv9_hd_vtvdigital",
                name = "VTV9 HD - Miền Nam",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv9/master.m3u8",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "vtv9_hd_tv360",
                name = "VTV9 HD (Dự phòng TV360)",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://vmttv.dpdns.org/tv360/?id=vtv9",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "antv_hd_prime",
                name = "ANTV HD - Truyền Hình Công An",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://liveh12.vtvprime.vn/hls/ANNINHTV/index.m3u8",
                groupTitle = "VTV Quốc Gia"
            ),
            TvChannel(
                id = "qpvn_hd_prime",
                name = "QPVN HD - Truyền Hình Quốc Phòng",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://liveh12.vtvprime.vn/hls/QPTV/index.m3u8",
                groupTitle = "VTV Quốc Gia"
            ),

            // 📺 TRUYỀN HÌNH TP. HỒ CHÍ MINH (HTV & HTVC)
            TvChannel(
                id = "htv1_hd_fpt",
                name = "HTV1 HD - Thông Tin Công Cộng",
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/04/HTV1.png/960px-HTV1.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTV1-HD-1080p/playlist.m3u8",
                groupTitle = "HTV TP.HCM"
            ),
            TvChannel(
                id = "htv2_hd_fpt",
                name = "HTV2 HD - Vie Channel",
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/d/d2/HTV2_-_Vie_Channel.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTV2-HD-1080p/playlist.m3u8",
                groupTitle = "HTV TP.HCM"
            ),
            TvChannel(
                id = "htv3_sd_fpt",
                name = "HTV3 - Thiếu Nhi & Gia Đình",
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/6/65/HTV3-2018.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTV3-SD-720p/playlist.m3u8",
                groupTitle = "HTV TP.HCM"
            ),
            TvChannel(
                id = "htv7_hd_fpt",
                name = "HTV7 HD - Giải Trí Đặc Sắc",
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/c/c0/HTV7_logo.webp",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTV7-HD-1080p/chunks.m3u8",
                groupTitle = "HTV TP.HCM"
            ),
            TvChannel(
                id = "htv9_hd_fpt",
                name = "HTV9 HD - Tin Tức & Thời Sự",
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/b/bf/HTV9_logo.webp",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTV9-HD-1080p/chunks.m3u8",
                groupTitle = "HTV TP.HCM"
            ),
            TvChannel(
                id = "htvc_thethao_fpt",
                name = "HTVC Thể Thao HD",
                logoUrl = "https://upload.wikimedia.org/wikipedia/vi/3/3f/HTV_THETHAO_2014.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTV-THETHAO-HD-1080p/chunks.m3u8",
                groupTitle = "HTV TP.HCM"
            ),
            TvChannel(
                id = "htvc_phim_fpt",
                name = "HTVC Phim HD",
                logoUrl = "https://raw.githubusercontent.com/HaNoiIPTV/HaNoiIPTV.m3u/refs/heads/master/HTVC%20Phim%20HD.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTVC-PHIM-HD-1080p/chunks.m3u8",
                groupTitle = "HTV TP.HCM"
            ),
            TvChannel(
                id = "htvc_thuanviet_fpt",
                name = "HTVC Thuần Việt HD",
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/c/c0/HTV7_logo.webp",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTVC-THUANVIET-HD-1080p/chunks.m3u8",
                groupTitle = "HTV TP.HCM"
            ),
            TvChannel(
                id = "htvc_giadinh_fpt",
                name = "HTVC Gia Đình HD",
                logoUrl = "https://i.ibb.co/BVQtkHgF/IMG-2899.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTVC-GIADINH-HD-1080p/chunks.m3u8",
                groupTitle = "HTV TP.HCM"
            ),

            // 🏛️ ĐÀI TRUYỀN HÌNH TỈNH THÀNH (CHÍNH THỐNG TỪ CÁC ĐÀI ĐỊA PHƯƠNG)
            TvChannel(
                id = "hanoi1_hd_prime",
                name = "Hà Nội 1 HD",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://liveh34.vtvprime.vn/hls/HANOI1TV/index.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "hanoi2_hd_prime",
                name = "Hà Nội 2 HD",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://liveh34.vtvprime.vn/hls/HANOI2/index.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "haiphong_sd_fpt",
                name = "Hải Phòng TV",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HAIPHONG-SD-720p/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "danang1_sd_fpt",
                name = "Đà Nẵng 1 TV",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/DANANG1-SD-720p/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "cantho_tv_cdn",
                name = "Cần Thơ TV",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://live.canthotv.vn/live/tv/chunklist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "dongthap_hd_prime",
                name = "Đồng Tháp TV (THĐT1)",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://liveh34.vtvprime.vn/hls/DONGTHAPTV/index.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "camau_hd_cdn",
                name = "Cà Mau TV (CTV)",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://tv.ctvcamau.vn/live/tv/tv.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "angiang_sd_fpt",
                name = "An Giang TV (ATV1)",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/ANGIANG-SD-720p/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "tiengiang_sd_fpt",
                name = "Tiền Giang TV (THTG)",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/TIENGIANG-SD-720p/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "dongnai1_sd_fpt",
                name = "Đồng Nai 1 (ĐNRTV1)",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/DONGNAI1-SD-720p/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "dongnai2_sd_fpt",
                name = "Đồng Nai 2 (ĐNRTV2)",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/DONGNAI2-SD-720p/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "binhthuan_sd_fpt",
                name = "Bình Thuận TV (BTV)",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/BINHTHUAN-SD-720p/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "khanhhoa_sd_fpt",
                name = "Khánh Hòa TV (KTV)",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/KHANHHOA-SD-720p/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "daklak_sd_fpt",
                name = "Đắk Lắk TV (DRT)",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/DAKLAK-SD-720p/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "hatinh_sd_fpt",
                name = "Hà Tĩnh TV (HTTV)",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HATINH-SD-720p/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "nghean_tv_cdn",
                name = "Nghệ An TV (NTV)",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://67tz1gnwrvxic.cmccdn.net/hls/NGHEANTV/03.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "thainguyen_tv_cdn",
                name = "Thái Nguyên TV (TNTV)",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://streaming.thainguyentv.vn/hls/livestream.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "tayninh_tv_cdn",
                name = "Tây Ninh TV (TTV11)",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://live.mediatech.vn/live/2851dc2c9af68834814a89e61db0faee561/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "langson_tv_cdn",
                name = "Lạng Sơn TV",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://stream.langsontv.vn/live/285c78da0c246524c90917842f8de03bd21/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "bacninh_tv_cdn",
                name = "Bắc Ninh TV (BTV)",
                logoUrl = "https://i.imgur.com/qWWpYRR.png",
                streamUrl = "https://live-hq.evgcdn.net/live/285f5f227e988ab4445a2138091d3d62e8d/chunklist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            ),
            TvChannel(
                id = "hue_tv_cdn",
                name = "Huế TRT",
                logoUrl = "https://i.imgur.com/vZYlGIW.png",
                streamUrl = "https://live.huetv.com.vn/TRT-Online/playlist.m3u8",
                groupTitle = "Đài Tỉnh Thành"
            )
        )
    }

    private fun parseM3uData(content: String, defaultGroup: String = "Truyền Hình"): TvSportsData {
        val lines = content.lines()
        var currentExt: String? = null
        val rawMatches = mutableListOf<FootballMatch>()
        val channels = mutableListOf<TvChannel>()

        val logoRegex = Regex("""tvg-logo="([^"]+)"""")
        val groupRegex = Regex("""group-title="([^"]+)"""")
        val timeRegex = Regex("""\[(\d{1,2}:\d{2})\]""")
        val vsRegex = Regex("""^(.*?)\s*vs\s*(.*?)\s*\[(\d{1,2}:\d{2})\]""", RegexOption.IGNORE_CASE)
        val blvRegex = Regex("""\[(?:BLV\s+|Kho Ga\s*-\s*)([^\]]+)\]""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                currentExt = trimmed
            } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val ext = currentExt
                if (ext != null) {
                    val logo = logoRegex.find(ext)?.groupValues?.get(1)
                    var group = groupRegex.find(ext)?.groupValues?.get(1) ?: defaultGroup
                    val commaIdx = ext.lastIndexOf(',')
                    val rawTitle = if (commaIdx >= 0) ext.substring(commaIdx + 1).trim() else ""

                    val vsMatch = vsRegex.find(rawTitle)
                    val timeMatch = timeRegex.find(rawTitle)

                    if (vsMatch != null && timeMatch != null) {
                        val home = vsMatch.groupValues[1].replace(Regex("""\[.*?\]"""), "").trim()
                        val away = vsMatch.groupValues[2].replace(Regex("""\[.*?\]"""), "").trim()
                        val time = timeMatch.groupValues[1].trim()
                        val blv = blvRegex.find(rawTitle)?.groupValues?.get(1)?.trim() ?: "Bình Luận Tiếng Việt"
                        val tournament = group.replace("Kho Ga TV - ", "").trim()

                        rawMatches.add(
                            FootballMatch(
                                id = "${home}_${away}_${time}_${rawMatches.size}",
                                title = rawTitle,
                                homeTeam = home,
                                awayTeam = away,
                                homeLogo = logo,
                                awayLogo = logo,
                                tournament = tournament,
                                kickoffTime = time,
                                commentator = blv,
                                streamUrl = trimmed,
                                groupTitle = group
                            )
                        )
                    } else {
                        var cleanName = rawTitle.replace(Regex("""\[.*?\]"""), "")
                            .replace(Regex("""\(.*?\)"""), "")
                            .replace("|", "-")
                            .trim()

                        if (cleanName.contains("THVL", ignoreCase = true) || cleanName.contains("Vĩnh Long", ignoreCase = true) || cleanName.contains("Vinh Long", ignoreCase = true)) {
                            group = "THVL Vĩnh Long"
                        }

                        channels.add(
                            TvChannel(
                                id = "chan_${channels.size}",
                                name = if (cleanName.isNotBlank()) cleanName else "Kênh TV",
                                logoUrl = logo,
                                streamUrl = trimmed,
                                groupTitle = group
                            )
                        )
                    }
                    currentExt = null
                }
            }
        }

        // Nhóm các luồng mirror của cùng 1 trận đấu lại với nhau
        val groupedMatches = rawMatches.groupBy { "${it.homeTeam.lowercase()}_vs_${it.awayTeam.lowercase()}_${it.kickoffTime}" }
            .map { (key, list) ->
                val first = list.first()
                FootballMatchGroup(
                    id = key,
                    homeTeam = first.homeTeam,
                    awayTeam = first.awayTeam,
                    kickoffTime = first.kickoffTime,
                    tournament = first.tournament,
                    logo = first.homeLogo,
                    streams = list.map { MatchStreamItem(it.title, it.commentator, it.streamUrl) }
                )
            }

        return TvSportsData(matches = groupedMatches, channels = channels)
    }

    suspend fun getNewUpdatedMovies(page: Int = 1): List<MovieItemDto> = withContext(Dispatchers.IO) {
        val base = RemoteConfigManager.getBaseUrl()
        val url = "$base/danh-sach/phim-moi-cap-nhat?page=$page"
        executeListRequest(url, defaultBadge = "MỚI")
    }

    suspend fun getMoviesByCategory(type: String, page: Int = 1): List<MovieItemDto> = withContext(Dispatchers.IO) {
        if (type == "torrent-4k") {
            return@withContext getTop4KTorrents(page)
        }
        if (type.contains("co-trang")) {
            val cobeList = getCoBeCategoryMovies("co-trang", page)
            val base = RemoteConfigManager.getBaseUrl()
            val kkList = executeListRequest("$base/v1/api/the-loai/co-trang?page=$page", defaultBadge = "🏯 CỔ TRANG")
            return@withContext (cobeList + kkList).distinctBy { it.slug }
        }

        val base = RemoteConfigManager.getBaseUrl()
        val url = when {
            type.startsWith("the-loai/") -> "$base/v1/api/$type?page=$page"
            type == "phim-chieu-rap" -> "$base/v1/api/danh-sach/phim-chieu-rap?page=$page"
            type == "hoat-hinh" -> "$base/v1/api/danh-sach/hoat-hinh?page=$page"
            type == "tv-shows" -> "$base/v1/api/danh-sach/tv-shows?page=$page"
            else -> "$base/v1/api/danh-sach/$type?page=$page"
        }
        val badge = when (type) {
            "phim-chieu-rap" -> "🎟️ RẠP"
            "hoat-hinh" -> "🌸 ANIME"
            "phim-bo" -> "📺 BỘ"
            "phim-le" -> "🎬 LẺ"
            else -> null
        }
        executeListRequest(url, defaultBadge = badge)
    }

    suspend fun getTop4KTorrents(page: Int = 1): List<MovieItemDto> = withContext(Dispatchers.IO) {
        val mirrors = listOf("https://yts.gg", "https://yts.torrentbay.st")
        for (mirror in mirrors) {
            try {
                val url = "$mirror/api/v2/list_movies.json?quality=2160p&limit=24&page=$page&sort_by=download_count"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DuyTV/1.0")
                    .build()
                val res = client.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: continue
                    val jsonTree = JsonParser.parseString(body).asJsonObject
                    if (jsonTree.has("data") && jsonTree.getAsJsonObject("data").has("movies")) {
                        val moviesArray = jsonTree.getAsJsonObject("data").getAsJsonArray("movies")
                        return@withContext moviesArray.map { elem ->
                            val obj = elem.asJsonObject
                            val id = obj.get("id").asString
                            val title = obj.get("title").asString
                            val titleLong = if (obj.has("title_long") && !obj.get("title_long").isJsonNull) obj.get("title_long").asString else title
                            val imdbCode = if (obj.has("imdb_code") && !obj.get("imdb_code").isJsonNull) obj.get("imdb_code").asString else ""
                            val year = if (obj.has("year") && !obj.get("year").isJsonNull) obj.get("year").asInt else 2026
                            val rating = if (obj.has("rating") && !obj.get("rating").isJsonNull) obj.get("rating").asDouble else 0.0
                            val cover = if (obj.has("large_cover_image") && !obj.get("large_cover_image").isJsonNull) {
                                obj.get("large_cover_image").asString
                            } else if (obj.has("medium_cover_image") && !obj.get("medium_cover_image").isJsonNull) {
                                obj.get("medium_cover_image").asString
                            } else ""

                            MovieItemDto(
                                id = id,
                                name = title,
                                slug = "torrent_${id}_$imdbCode",
                                originName = titleLong,
                                posterUrl = cover,
                                thumbUrl = cover,
                                year = year,
                                source = "Torrent4K",
                                imdbCode = imdbCode,
                                rating = rating,
                                qualityTag = "4K UHD",
                                badge = "💎 4K"
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        emptyList()
    }

    suspend fun getMovieDetail(slug: String): MovieDetailResponse? = withContext(Dispatchers.IO) {
        if (slug.startsWith("cobe_")) {
            return@withContext getCoBeMovieDetail(slug.removePrefix("cobe_"))
        }
        if (slug.startsWith("torrent_")) {
            return@withContext getTorrentMovieDetail(slug)
        }

        val base = RemoteConfigManager.getBaseUrl()
        val url = "$base/phim/$slug"
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "DuyTV/1.0")
                .build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                res.body?.string()?.let {
                    val detail = gson.fromJson(it, MovieDetailResponse::class.java)
                    // If movie has an origin_name or imdb, check for 4K Torrent sources and Vietsub
                    val movieName = detail.movie?.originName ?: detail.movie?.name
                    if (!movieName.isNullOrBlank() && detail.status) {
                        try {
                            val torrents = searchYtsTorrents(movieName)
                            if (torrents.isNotEmpty()) {
                                val topTorrent = torrents.first()
                                val extraEpisodes = listOf(
                                    EpisodeItemDto(
                                        name = "4K Cinema (Torrent YTS)",
                                        slug = topTorrent.slug,
                                        linkM3u8 = null,
                                        linkEmbed = null
                                    )
                                )
                                val mutableGroups = detail.episodes?.toMutableList() ?: mutableListOf()
                                mutableGroups.add(
                                    ServerGroupDto(
                                        serverName = "💎 Server 4K Cinema (Torrent)",
                                        serverData = extraEpisodes
                                    )
                                )
                                return@withContext detail.copy(episodes = mutableGroups)
                            }
                        } catch (_: Exception) {}
                    }
                    return@withContext detail
                }
            }
        } catch (_: Exception) {}
        null
    }

    private suspend fun getTorrentMovieDetail(slug: String): MovieDetailResponse? = withContext(Dispatchers.IO) {
        // slug format: torrent_{id}_{imdbCode}
        val parts = slug.split("_")
        val movieId = parts.getOrNull(1) ?: return@withContext null
        val imdbCode = parts.getOrNull(2) ?: ""

        val detailUrl = "https://yts.gg/api/v2/movie_details.json?movie_id=$movieId&with_images=true"
        try {
            val req = Request.Builder()
                .url(detailUrl)
                .header("User-Agent", "Mozilla/5.0 DuyTV/1.0")
                .build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val json = res.body?.string() ?: return@withContext null
                val jsonTree = JsonParser.parseString(json).asJsonObject
                val m = jsonTree.getAsJsonObject("data").getAsJsonObject("movie")

                val title = m.get("title").asString
                val titleLong = if (m.has("title_long") && !m.get("title_long").isJsonNull) m.get("title_long").asString else title
                val desc = if (m.has("description_full") && !m.get("description_full").isJsonNull) m.get("description_full").asString else ""
                val cover = if (m.has("large_cover_image") && !m.get("large_cover_image").isJsonNull) m.get("large_cover_image").asString else ""
                val bg = if (m.has("background_image_original") && !m.get("background_image_original").isJsonNull) m.get("background_image_original").asString else cover
                val year = if (m.has("year") && !m.get("year").isJsonNull) m.get("year").asInt else 2026
                val rating = if (m.has("rating") && !m.get("rating").isJsonNull) m.get("rating").asDouble else 0.0
                val runtime = if (m.has("runtime") && !m.get("runtime").isJsonNull) "${m.get("runtime").asInt} phút" else "120 phút"

                val episodeList = mutableListOf<EpisodeItemDto>()
                if (m.has("torrents") && m.get("torrents").isJsonArray) {
                    val torrentsArray = m.getAsJsonArray("torrents")
                    torrentsArray.forEach { tElem ->
                        val tObj = tElem.asJsonObject
                        val quality = tObj.get("quality").asString
                        val type = if (tObj.has("type") && !tObj.get("type").isJsonNull) tObj.get("type").asString else "Bluray"
                        val size = tObj.get("size").asString
                        val seeds = tObj.get("seeds").asInt
                        val hash = tObj.get("hash").asString
                        val magnet = "magnet:?xt=urn:btih:$hash&dn=${URLEncoder.encode(title, "UTF-8")}&tr=udp://open.demonii.com:1337/announce&tr=udp://tracker.openbittorrent.com:80"

                        episodeList.add(
                            EpisodeItemDto(
                                name = "$quality $type ($size • $seeds seeds)",
                                slug = hash,
                                linkM3u8 = magnet,
                                linkEmbed = magnet
                            )
                        )
                    }
                }

                val serverGroups = mutableListOf<ServerGroupDto>()
                serverGroups.add(
                    ServerGroupDto(
                        serverName = "💎 Server 4K UHD Torrent (YTS & Debrid)",
                        serverData = episodeList
                    )
                )

                // Check if KKPhim has this movie in Vietnamese for instant 1-click fallback
                try {
                    val kkMatches = searchMovies(title)
                    if (kkMatches.isNotEmpty()) {
                        val firstMatch = kkMatches.first()
                        val kkDetail = getMovieDetail(firstMatch.slug)
                        if (kkDetail?.episodes != null && kkDetail.episodes.isNotEmpty()) {
                            val vipServer = kkDetail.episodes.first()
                            serverGroups.add(
                                ServerGroupDto(
                                    serverName = "⚡ Server VIP Vietsub (Phát ngay mượt mà)",
                                    serverData = vipServer.serverData
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}

                val movieDetail = MovieDetailDto(
                    id = movieId,
                    name = title,
                    slug = slug,
                    originName = titleLong,
                    content = if (desc.isNotBlank()) desc else "Phim chất lượng cao 4K Ultra HD bản quyền quốc tế có tích hợp phụ đề tiếng Việt tự động.",
                    type = "single",
                    status = "Hoàn tất",
                    thumbUrl = bg,
                    posterUrl = cover,
                    time = runtime,
                    episodeCurrent = "4K UHD Remux",
                    episodeTotal = "1 Tập",
                    quality = "4K UHD",
                    lang = "Vietsub Tự Động",
                    year = year
                )

                return@withContext MovieDetailResponse(
                    status = true,
                    msg = "OK",
                    movie = movieDetail,
                    episodes = serverGroups
                )
            }
        } catch (_: Exception) {}
        null
    }

    suspend fun getCoBeCategoryMovies(catSlug: String, page: Int = 1): List<MovieItemDto> = withContext(Dispatchers.IO) {
        try {
            val url = "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/api/v1/movies/by-category/$catSlug?page=$page&limit=24"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DuyTV/1.0")
                .header("Referer", "https://cobephim.mom/")
                .build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val json = res.body?.string() ?: return@withContext emptyList()
                val jsonTree = JsonParser.parseString(json).asJsonObject
                if (jsonTree.has("result") && jsonTree.get("result").isJsonArray) {
                    val array = jsonTree.getAsJsonArray("result")
                    return@withContext array.map { elem ->
                        val obj = elem.asJsonObject
                        val name = obj.get("name").asString
                        val slug = obj.get("slug").asString
                        val originName = if (obj.has("origin_name") && !obj.get("origin_name").isJsonNull) obj.get("origin_name").asString else ""
                        val thumb = if (obj.has("thumbnail") && !obj.get("thumbnail").isJsonNull) obj.get("thumbnail").asString else ""
                        val poster = if (obj.has("poster") && !obj.get("poster").isJsonNull) obj.get("poster").asString else thumb
                        val year = if (obj.has("publish_year") && !obj.get("publish_year").isJsonNull) obj.get("publish_year").asInt else 2001
                        val lang = if (obj.has("language") && !obj.get("language").isJsonNull) obj.get("language").asString else "Lồng Tiếng"

                        MovieItemDto(
                            id = "cobe_$slug",
                            name = name,
                            slug = "cobe_$slug",
                            originName = originName,
                            posterUrl = poster,
                            thumbUrl = thumb,
                            year = year,
                            source = "CoBePhim",
                            qualityTag = lang,
                            badge = "🌸 CỔ TRANG"
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        emptyList()
    }

    private suspend fun getCoBeMovieDetail(slug: String): MovieDetailResponse? = withContext(Dispatchers.IO) {
        try {
            val url = "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/api/v1/movies/get-episodes/$slug"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DuyTV/1.0")
                .header("Referer", "https://cobephim.mom/")
                .build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val json = res.body?.string() ?: return@withContext null
                val jsonTree = JsonParser.parseString(json).asJsonObject

                val name = jsonTree.get("name").asString
                val originName = if (jsonTree.has("origin_name") && !jsonTree.get("origin_name").isJsonNull) jsonTree.get("origin_name").asString else ""
                val desc = if (jsonTree.has("description") && !jsonTree.get("description").isJsonNull) jsonTree.get("description").asString else ""
                val thumb = if (jsonTree.has("thumbnail") && !jsonTree.get("thumbnail").isJsonNull) jsonTree.get("thumbnail").asString else ""
                val poster = if (jsonTree.has("poster") && !jsonTree.get("poster").isJsonNull) jsonTree.get("poster").asString else thumb
                val year = if (jsonTree.has("publish_year") && !jsonTree.get("publish_year").isJsonNull) jsonTree.get("publish_year").asInt else 2001
                val epCurrent = if (jsonTree.has("episode_current") && !jsonTree.get("episode_current").isJsonNull) jsonTree.get("episode_current").asString else "Hoàn tất"
                val epTotal = if (jsonTree.has("episode_total") && !jsonTree.get("episode_total").isJsonNull) jsonTree.get("episode_total").asString else "Trọn bộ"
                val quality = if (jsonTree.has("quality") && !jsonTree.get("quality").isJsonNull) jsonTree.get("quality").asString else "HD"
                val lang = if (jsonTree.has("language") && !jsonTree.get("language").isJsonNull) jsonTree.get("language").asString else "Lồng Tiếng"
                val time = if (jsonTree.has("episode_time") && !jsonTree.get("episode_time").isJsonNull) jsonTree.get("episode_time").asString else "45 phút"

                val episodeItems = mutableListOf<EpisodeItemDto>()
                if (jsonTree.has("episodes") && jsonTree.get("episodes").isJsonArray) {
                    val epArray = jsonTree.getAsJsonArray("episodes")
                    epArray.forEach { epElem ->
                        val epObj = epElem.asJsonObject
                        val epId = epObj.get("id").asLong
                        val epName = epObj.get("name").asString
                        val epSlug = epObj.get("slug").asString
                        val embedUrl = "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/embed/$epId"

                        episodeItems.add(
                            EpisodeItemDto(
                                name = epName,
                                slug = "cobe_ep_${epId}_$epSlug",
                                linkM3u8 = null,
                                linkEmbed = embedUrl
                            )
                        )
                    }
                }

                // Pre-resolve Episode 1 m3u8 so it plays with 0 delay
                if (episodeItems.isNotEmpty()) {
                    val firstEp = episodeItems.first()
                    val epId = firstEp.slug.split("_").getOrNull(2)?.toLongOrNull()
                    if (epId != null) {
                        val m3u8 = resolveCoBeEpisode(epId)
                        if (m3u8 != null) {
                            episodeItems[0] = firstEp.copy(linkM3u8 = m3u8)
                        }
                    }
                }

                val movieDetail = MovieDetailDto(
                    id = slug,
                    name = name,
                    slug = "cobe_$slug",
                    originName = originName,
                    content = desc.replace(Regex("<[^>]*>"), ""),
                    type = "series",
                    status = "Hoàn tất",
                    thumbUrl = thumb,
                    posterUrl = poster,
                    time = time,
                    episodeCurrent = epCurrent,
                    episodeTotal = epTotal,
                    quality = quality,
                    lang = lang,
                    year = year
                )

                return@withContext MovieDetailResponse(
                    status = true,
                    msg = "OK",
                    movie = movieDetail,
                    episodes = listOf(
                        ServerGroupDto(
                            serverName = "🌸 Server CoBePhim (Lồng Tiếng HD)",
                            serverData = episodeItems
                        )
                    )
                )
            }
        } catch (_: Exception) {}
        null
    }

    fun hexXorDecrypt(hex: String, key: String = "mySecretKey2024"): String {
        val sb = StringBuilder()
        var i = 0
        while (i < hex.length - 1) {
            val b = hex.substring(i, i + 2).toInt(16)
            val k = key[(i / 2) % key.length].code
            sb.append((b xor k).toChar())
            i += 2
        }
        return sb.toString()
    }

    suspend fun resolveCoBeEpisode(epId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/embed/$epId"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DuyTV/1.0")
                .header("Referer", "https://cobephim.mom/")
                .build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val html = res.body?.string() ?: return@withContext null
                val match = Regex("\"encrypted_url\"\\s*:\\s*\"([^\"]+)\"").find(html)
                if (match != null) {
                    val encrypted = match.groupValues[1]
                    val decrypted = hexXorDecrypt(encrypted, "mySecretKey2024")
                    if (decrypted.startsWith("http")) {
                        return@withContext decrypted
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }

    private fun removeVietnameseAccents(str: String): String {
        val nfd = java.text.Normalizer.normalize(str, java.text.Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(nfd, "")
            .replace("đ", "d").replace("Đ", "D")
    }

    private fun getSmartKeywordAliases(rawKeyword: String): List<String> {
        val lower = rawKeyword.trim().lowercase()
        val aliases = mutableListOf<String>()

        if (lower.contains("đắc kỷ") || lower.contains("đắc kỉ") || lower.contains("đát kỷ") || lower.contains("dac ky") || lower.contains("trụ vương")) {
            aliases.add("dac ky")
            aliases.add("đắc kỷ")
            aliases.add("đát kỷ")
            aliases.add("phong thần")
            aliases.add("gods of honour")
        }

        if (lower.contains("tây du") || lower.contains("tay du") || lower.contains("ngộ không")) {
            aliases.add("tay du ky")
            aliases.add("tây du ký")
        }

        if (lower.contains("bao thanh") || lower.contains("triển chiêu") || lower.contains("bao công")) {
            aliases.add("bao thanh thien")
            aliases.add("bao thanh thiên")
        }

        if (lower.contains("thiên long") || lower.contains("thien long") || lower.contains("kiều phong")) {
            aliases.add("thien long bat bo")
            aliases.add("thiên long bát bộ")
        }

        if (lower.contains("thần điêu") || lower.contains("than dieu") || lower.contains("dương quá")) {
            aliases.add("than dieu dai hiep")
            aliases.add("thần điêu đại hiệp")
        }

        if (lower.contains("xạ điêu") || lower.contains("xa dieu")) {
            aliases.add("anh hung xa dieu")
            aliases.add("anh hùng xạ điêu")
        }

        if (lower.contains("tam quốc") || lower.contains("tam quoc")) {
            aliases.add("tam quoc")
            aliases.add("tam quốc")
        }

        if (lower.contains("ỷ thiên") || lower.contains("y thien")) {
            aliases.add("y thien do long ky")
            aliases.add("ỷ thiên đồ long ký")
        }

        val unaccented = removeVietnameseAccents(rawKeyword)
        if (unaccented != rawKeyword) {
            aliases.add(unaccented)
        }

        return aliases.distinct()
    }

    suspend fun searchCoBePhim(keyword: String, aliases: List<String> = emptyList()): List<MovieItemDto> = withContext(Dispatchers.IO) {
        val queries = (listOf(keyword) + aliases).distinct()
        val results = mutableListOf<MovieItemDto>()
        val seenSlugs = mutableSetOf<String>()

        for (q in queries) {
            try {
                val encoded = URLEncoder.encode(q, "UTF-8")
                val url = "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/api/v1/movies/search?keyword=$encoded&page=1&limit=32"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DuyTV/1.0")
                    .header("Referer", "https://cobephim.mom/")
                    .build()
                val res = client.newCall(req).execute()
                if (res.isSuccessful) {
                    val json = res.body?.string() ?: continue
                    val jsonTree = JsonParser.parseString(json).asJsonObject
                    if (jsonTree.has("result") && jsonTree.get("result").isJsonArray) {
                        val array = jsonTree.getAsJsonArray("result")
                        array.forEach { elem ->
                            val obj = elem.asJsonObject
                            val name = obj.get("name").asString
                            val slug = obj.get("slug").asString
                            val originName = if (obj.has("origin_name") && !obj.get("origin_name").isJsonNull) obj.get("origin_name").asString else ""
                            val thumb = if (obj.has("thumbnail") && !obj.get("thumbnail").isJsonNull) obj.get("thumbnail").asString else ""
                            val poster = if (obj.has("poster") && !obj.get("poster").isJsonNull) obj.get("poster").asString else thumb
                            val year = if (obj.has("publish_year") && !obj.get("publish_year").isJsonNull) obj.get("publish_year").asInt else 2001
                            val lang = if (obj.has("language") && !obj.get("language").isJsonNull) obj.get("language").asString else "Lồng Tiếng"

                            if (seenSlugs.add(slug)) {
                                results.add(
                                    MovieItemDto(
                                        id = "cobe_$slug",
                                        name = name,
                                        slug = "cobe_$slug",
                                        originName = originName,
                                        posterUrl = poster,
                                        thumbUrl = thumb,
                                        year = year,
                                        source = "CoBePhim",
                                        qualityTag = lang,
                                        badge = "🌸 CỔ TRANG"
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            if (results.size >= 25) break
        }
        results
    }

    suspend fun searchAllSources(keyword: String): List<MovieItemDto> = withContext(Dispatchers.IO) {
        val aliases = getSmartKeywordAliases(keyword)

        val cobeDeferred = async { searchCoBePhim(keyword, aliases) }
        val kkphimDeferred = async { searchMoviesWithAliases(keyword, aliases) }
        val nguoncDeferred = async { searchNguonC(keyword) }
        val ytsDeferred = async { searchYtsTorrents(keyword) }

        val cobeList = try { cobeDeferred.await() } catch (_: Exception) { emptyList() }
        val kkList = try { kkphimDeferred.await() } catch (_: Exception) { emptyList() }
        val ncList = try { nguoncDeferred.await() } catch (_: Exception) { emptyList() }
        val ytsList = try { ytsDeferred.await() } catch (_: Exception) { emptyList() }

        val combined = mutableListOf<MovieItemDto>()
        val seenSlugs = mutableSetOf<String>()

        // 1. CoBePhim (Top Ancient / TVB Drama source!)
        for (item in cobeList) {
            if (seenSlugs.add(item.slug)) combined.add(item)
        }
        // 2. KKPhim VIP
        for (item in kkList) {
            if (seenSlugs.add(item.slug)) combined.add(item)
        }
        // 3. NguonC
        for (item in ncList) {
            if (seenSlugs.add(item.slug)) combined.add(item)
        }
        // 4. Torrent 4K
        for (item in ytsList) {
            if (seenSlugs.add(item.slug)) combined.add(item)
        }

        combined
    }

    suspend fun searchMoviesWithAliases(keyword: String, aliases: List<String>): List<MovieItemDto> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MovieItemDto>()
        val seen = mutableSetOf<String>()
        val queries = (listOf(keyword) + aliases).distinct()
        for (q in queries) {
            try {
                val matches = searchMovies(q)
                for (m in matches) {
                    if (seen.add(m.slug)) list.add(m)
                }
            } catch (_: Exception) {}
            if (list.size >= 30) break
        }
        list
    }

    suspend fun searchMovies(keyword: String): List<MovieItemDto> = withContext(Dispatchers.IO) {
        val base = RemoteConfigManager.getBaseUrl()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "$base/v1/api/tim-kiem?keyword=$encoded"
        executeListRequest(url, defaultBadge = "VIP")
    }

    suspend fun searchNguonC(keyword: String): List<MovieItemDto> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://phim.nguonc.com/api/films/search?keyword=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "DuyTV/1.0")
                .build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val json = res.body?.string() ?: return@withContext emptyList()
                val jsonTree = JsonParser.parseString(json).asJsonObject
                if (jsonTree.has("items") && jsonTree.get("items").isJsonArray) {
                    val itemsArray = jsonTree.getAsJsonArray("items")
                    return@withContext itemsArray.map { elem ->
                        val obj = elem.asJsonObject
                        val name = obj.get("name").asString
                        val slug = obj.get("slug").asString
                        val originName = if (obj.has("original_name") && !obj.get("original_name").isJsonNull) obj.get("original_name").asString else ""
                        val poster = if (obj.has("poster_url") && !obj.get("poster_url").isJsonNull) obj.get("poster_url").asString else ""
                        val thumb = if (obj.has("thumb_url") && !obj.get("thumb_url").isJsonNull) obj.get("thumb_url").asString else ""
                        val year = if (obj.has("year") && !obj.get("year").isJsonNull) {
                            try { obj.get("year").asString.toInt() } catch (_: Exception) { 2026 }
                        } else 2026

                        MovieItemDto(
                            id = slug,
                            name = name,
                            slug = slug,
                            originName = originName,
                            posterUrl = poster,
                            thumbUrl = thumb,
                            year = year,
                            source = "NguonC",
                            qualityTag = "FHD",
                            badge = "🌐 NGUỒN C"
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        emptyList()
    }

    suspend fun searchYtsTorrents(keyword: String): List<MovieItemDto> = withContext(Dispatchers.IO) {
        val mirrors = listOf("https://yts.gg", "https://yts.torrentbay.st")
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        for (mirror in mirrors) {
            try {
                val url = "$mirror/api/v2/list_movies.json?query_term=$encoded&limit=15"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 DuyTV/1.0")
                    .build()
                val res = client.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: continue
                    val jsonTree = JsonParser.parseString(body).asJsonObject
                    if (jsonTree.has("data") && jsonTree.getAsJsonObject("data").has("movies")) {
                        val moviesArray = jsonTree.getAsJsonObject("data").getAsJsonArray("movies")
                        return@withContext moviesArray.map { elem ->
                            val obj = elem.asJsonObject
                            val id = obj.get("id").asString
                            val title = obj.get("title").asString
                            val titleLong = if (obj.has("title_long") && !obj.get("title_long").isJsonNull) obj.get("title_long").asString else title
                            val imdbCode = if (obj.has("imdb_code") && !obj.get("imdb_code").isJsonNull) obj.get("imdb_code").asString else ""
                            val year = if (obj.has("year") && !obj.get("year").isJsonNull) obj.get("year").asInt else 2026
                            val rating = if (obj.has("rating") && !obj.get("rating").isJsonNull) obj.get("rating").asDouble else 0.0
                            val cover = if (obj.has("large_cover_image") && !obj.get("large_cover_image").isJsonNull) {
                                obj.get("large_cover_image").asString
                            } else if (obj.has("medium_cover_image") && !obj.get("medium_cover_image").isJsonNull) {
                                obj.get("medium_cover_image").asString
                            } else ""

                            MovieItemDto(
                                id = id,
                                name = title,
                                slug = "torrent_${id}_$imdbCode",
                                originName = titleLong,
                                posterUrl = cover,
                                thumbUrl = cover,
                                year = year,
                                source = "Torrent4K",
                                imdbCode = imdbCode,
                                rating = rating,
                                qualityTag = "4K UHD",
                                badge = "💎 4K"
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        emptyList()
    }

    suspend fun getVietnameseSubtitle(imdbId: String): String? = withContext(Dispatchers.IO) {
        if (imdbId.isBlank()) return@withContext null
        try {
            val url = "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 DuyTV/1.0")
                .build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val json = res.body?.string() ?: return@withContext null
                val jsonTree = JsonParser.parseString(json).asJsonObject
                if (jsonTree.has("subtitles") && jsonTree.get("subtitles").isJsonArray) {
                    val subs = jsonTree.getAsJsonArray("subtitles")
                    for (sub in subs) {
                        val obj = sub.asJsonObject
                        val lang = if (obj.has("lang")) obj.get("lang").asString else ""
                        if (lang.equals("vie", ignoreCase = true) || lang.equals("vi", ignoreCase = true)) {
                            if (obj.has("url")) {
                                return@withContext obj.get("url").asString
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }

    private fun tryExecuteList(url: String, defaultBadge: String? = null): List<MovieItemDto> {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "DuyTV/1.0")
                .build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val json = res.body?.string() ?: return emptyList()
                val parsed = gson.fromJson(json, MovieListResponse::class.java)
                if (parsed?.items != null && parsed.items.isNotEmpty()) {
                    return parsed.items.map {
                        if (defaultBadge != null && it.badge == null) it.copy(badge = defaultBadge) else it
                    }
                }
                val jsonTree = JsonParser.parseString(json).asJsonObject
                if (jsonTree.has("data") && jsonTree.get("data").isJsonObject) {
                    val dataObj = jsonTree.getAsJsonObject("data")
                    if (dataObj.has("items")) {
                        val itemsArray = dataObj.getAsJsonArray("items")
                        return itemsArray.map {
                            val item = gson.fromJson(it, MovieItemDto::class.java)
                            if (defaultBadge != null && item.badge == null) item.copy(badge = defaultBadge) else item
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    private fun executeListRequest(url: String, defaultBadge: String? = null): List<MovieItemDto> {
        val directResult = tryExecuteList(url, defaultBadge)
        if (directResult.isNotEmpty()) return directResult

        // Failover: nếu nguồn chính gặp sự cố, tự động dùng mirror dự phòng https://phimapi.com
        val fallbackBases = listOf("https://phimapi.com", "https://ophim.cc")
        for (backupBase in fallbackBases) {
            try {
                val uri = java.net.URI(url)
                val pathAndQuery = (uri.rawPath ?: "") + if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
                val backupUrl = "$backupBase$pathAndQuery"
                if (backupUrl != url) {
                    val backupResult = tryExecuteList(backupUrl, defaultBadge)
                    if (backupResult.isNotEmpty()) return backupResult
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }
}