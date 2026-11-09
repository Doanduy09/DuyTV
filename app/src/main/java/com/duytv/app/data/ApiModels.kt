package com.duytv.app.data

import com.google.gson.annotations.SerializedName

data class SourceConfig(
    @SerializedName("name") val name: String = "",
    @SerializedName("api_base") val apiBase: String = "",
    @SerializedName("image_cdn") val imageCdn: String? = null,
    @SerializedName("enabled") val enabled: Boolean = true
)

data class SourcesContainer(
    @SerializedName("kkphim") val kkphim: SourceConfig = SourceConfig(name = "KKPhim", apiBase = "https://phimapi.com", imageCdn = "https://phimimg.com"),
    @SerializedName("ophim") val ophim: SourceConfig = SourceConfig(name = "OPhim", apiBase = "https://ophim.cc"),
    @SerializedName("nguonc") val nguonc: SourceConfig = SourceConfig(name = "NguonC", apiBase = "https://phim.nguonc.com/api/film"),
    @SerializedName("cobephim") val cobephim: SourceConfig = SourceConfig(name = "CoBePhim", apiBase = "https://cobephim.mom")
)

data class RemoteConfig(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("sources") val sources: SourcesContainer = SourcesContainer(),
    @SerializedName("primary_domain") val primaryDomain: String = "https://phimapi.com",
    @SerializedName("backup_domains") val backupDomains: List<String> = listOf(
        "https://phimapi.com/v1/api",
        "https://phimimg.com"
    ),
    @SerializedName("image_cdn") val imageCdn: String = "https://phimimg.com"
)

data class MovieListResponse(
    @SerializedName("status") val status: Boolean,
    @SerializedName("items") val items: List<MovieItemDto>? = emptyList(),
    @SerializedName("pagination") val pagination: PaginationDto? = null
)

data class PaginationDto(
    @SerializedName("totalItems") val totalItems: Int = 0,
    @SerializedName("totalItemsPerPage") val totalItemsPerPage: Int = 24,
    @SerializedName("currentPage") val currentPage: Int = 1,
    @SerializedName("totalPages") val totalPages: Int = 1
)

data class MovieItemDto(
    @SerializedName("_id") val id: String? = null,
    @SerializedName("name") val name: String = "",
    @SerializedName("slug") val slug: String = "",
    @SerializedName("origin_name") val originName: String? = "",
    @SerializedName("poster_url") val posterUrl: String? = "",
    @SerializedName("thumb_url") val thumbUrl: String? = "",
    @SerializedName("year") val year: Int? = 2026,
    val source: String = "KKPhim",
    val imdbCode: String? = null,
    val rating: Double? = null,
    val qualityTag: String? = null,
    val badge: String? = null
)

data class TorrentStreamDto(
    val name: String = "",
    val title: String = "",
    val infoHash: String? = null,
    val fileIdx: Int? = 0,
    val url: String? = null,
    val sizeText: String = "",
    val quality: String = "1080p",
    val seeds: Int = 0
)

data class SubtitleDto(
    val id: String = "",
    val url: String = "",
    val lang: String = "vi",
    val name: String = "Tiếng Việt"
)

data class CategoryItem(
    val id: String,
    val title: String,
    val endpoint: String,
    val type: String = "api"
)

data class MovieDetailResponse(
    @SerializedName("status") val status: Boolean,
    @SerializedName("msg") val msg: String? = null,
    @SerializedName("movie") val movie: MovieDetailDto? = null,
    @SerializedName("episodes") val episodes: List<ServerGroupDto>? = emptyList()
)

data class MovieDetailDto(
    @SerializedName("_id") val id: String? = null,
    @SerializedName("name") val name: String = "",
    @SerializedName("slug") val slug: String = "",
    @SerializedName("origin_name") val originName: String? = "",
    @SerializedName("content") val content: String? = "",
    @SerializedName("type") val type: String? = "",
    @SerializedName("status") val status: String? = "",
    @SerializedName("thumb_url") val thumbUrl: String? = "",
    @SerializedName("poster_url") val posterUrl: String? = "",
    @SerializedName("time") val time: String? = "",
    @SerializedName("episode_current") val episodeCurrent: String? = "",
    @SerializedName("episode_total") val episodeTotal: String? = "",
    @SerializedName("quality") val quality: String? = "FHD",
    @SerializedName("lang") val lang: String? = "Vietsub",
    @SerializedName("year") val year: Int? = 2026
)

data class ServerGroupDto(
    @SerializedName("server_name") val serverName: String = "Server VIP",
    @SerializedName("server_data") val serverData: List<EpisodeItemDto> = emptyList()
)

data class EpisodeItemDto(
    @SerializedName("name") val name: String = "",
    @SerializedName("slug") val slug: String = "",
    @SerializedName("filename") val filename: String? = null,
    @SerializedName("link_embed") val linkEmbed: String? = null,
    @SerializedName("link_m3u8") val linkM3u8: String? = null,
    val backupStreams: List<String> = emptyList()
)
