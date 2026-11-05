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

    private fun executeListRequest(url: String, defaultBadge: String? = null): List<MovieItemDto> {
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
}