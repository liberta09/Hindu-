package com.liberta09

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmİzle"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "$mainUrl/film?tarih=2026" to "Yeni Filmler",
        "$mainUrl/film-izle" to "Filmler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/trendler" to "Trendler",
        "$mainUrl/en-iyiler" to "En İyiler",
        "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/dram-filmleri" to "Dram",
        "$mainUrl/tur/komedi-filmleri" to "Komedi",
        "$mainUrl/tur/korku-filmleri" to "Korku",
        "$mainUrl/tur/romantik-filmleri" to "Romantik",
        "$mainUrl/tur/tarih-filmleri" to "Tarih",
        "$mainUrl/tur/bilim-kurgu-filmleri" to "Bilim Kurgu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "/page/$page" else ""
        val document = app.get(url).document
        val items = document.select("main a[href], #content a[href], .content a[href], article a[href]")
            .ifEmpty { document.select("a[href]") }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .take(40)
        return newHomePageResponse(request.name, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return document.select("main a[href], #content a[href], .content a[href], article a[href]")
            .ifEmpty { document.select("a[href]") }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .take(40)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = absUrl("href").takeIf { it.startsWith(mainUrl) } ?: return null
        val path = href.removePrefix(mainUrl).substringBefore("?").removeSuffix("/")
        val isMovie = path.startsWith("/film/")
        val isSeries = path.startsWith("/diziler/")
        if (!isMovie && !isSeries) return null

        val image = selectFirst("img") ?: return null
        val rawPoster = image.absUrl("data-src").ifBlank {
            image.absUrl("data-lazy-src").ifBlank { image.absUrl("src") }
        }
        val poster = rawPoster.takeIf { it.isNotBlank() } ?: return null

        val title = image.attr("alt").trim()
            .removeSuffix(" izle")
            .removeSuffix(" İzle")
            .takeIf { it.isNotBlank() && !it.equals("Image", true) }
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(parent()?.text().orEmpty())?.value?.toIntOrNull()

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: url.substringAfterLast("/").replace("-", " ")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst(".entry-content, .film-description, .description")?.text()?.trim()

        val episodes = document.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            val text = a.text().replace(Regex("\\s+"), " ").trim()
            if (!href.startsWith(mainUrl)) return@mapNotNull null
            val m1 = Regex("(\\d+)\\s*[.]?\\s*Sezon.*?(\\d+)\\s*[.]?\\s*Bölüm", RegexOption.IGNORE_CASE).find(text)
            val m2 = Regex("(\\d+)\\s*[.]?\\s*Bölüm.*?(\\d+)\\s*[.]?\\s*Sezon", RegexOption.IGNORE_CASE).find(text)
            when {
                m1 != null -> newEpisode(href) {
                    name = text
                    season = m1.groupValues[1].toIntOrNull() ?: 1
                    episode = m1.groupValues[2].toIntOrNull() ?: 1
                }
                m2 != null -> newEpisode(href) {
                    name = text
                    season = m2.groupValues[2].toIntOrNull() ?: 1
                    episode = m2.groupValues[1].toIntOrNull() ?: 1
                }
                else -> null
            }
        }.distinctBy { it.data }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
            }
        }
    }

    private suspend fun loadKinescope(
        iframeUrl: String,
        parentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\\.m3u8(?:\\?|$)"""),
                additionalUrls = listOf(Regex("""kinescopecdn\\.net/hls/""")),
                userAgent = null,
                useOkhttp = false,
                timeout = 45_000L
            )
            val (finalRequest, _) = resolver.resolveUsingWebView(
                url = iframeUrl,
                referer = parentUrl,
                headers = mapOf("Referer" to parentUrl, "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8")
            )
            if (finalRequest == null) return false
            val manifestUrl = finalRequest.url.toString()
            if (!manifestUrl.contains(".m3u8", ignoreCase = true)) return false
            val browserHeaders = finalRequest.headers.toMap().toMutableMap()
            if (browserHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) browserHeaders["Referer"] = iframeUrl
            M3u8Helper.generateM3u8(
                source = "HintFilmİzle - Kinescope",
                streamUrl = manifestUrl,
                referer = iframeUrl,
                headers = browserHeaders,
                name = "Kinescope"
            ).forEach(callback)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false
        document.select("iframe[src], iframe[data-src], video[src], video source[src]").forEach {
            val src = it.absUrl("src").ifBlank { it.absUrl("data-src") }.takeIf { it.isNotBlank() } ?: return@forEach
            if (src.contains("kinescope", true) || src.contains("kinescopecdn.net", true)) {
                if (loadKinescope(src, data, subtitleCallback, callback)) {
                    found = true
                    return@forEach
                }
            }
            if (runCatching { loadExtractor(src, data, subtitleCallback, callback) }.getOrDefault(false)) found = true
        }
        document.select("a[href]").forEach {
            val href = it.absUrl("href")
            if (href.contains("vidmoly", true) || href.contains("vidhide", true) || href.contains("streamtape", true) || href.contains("voe.sx", true) || href.contains("ok.ru", true)) {
                if (runCatching { loadExtractor(href, data, subtitleCallback, callback) }.getOrDefault(false)) found = true
            }
        }
        return found
    }
}
