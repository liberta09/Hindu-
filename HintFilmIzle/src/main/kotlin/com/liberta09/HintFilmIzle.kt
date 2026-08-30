package com.liberta09

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film" to "Filmler",
        "$mainUrl/film-izle" to "Filmler & Diziler",
        "$mainUrl/trendler" to "Trendler",
        "$mainUrl/tur/aksiyon-filmleri" to "Aksiyon",
        "$mainUrl/tur/dram-filmleri" to "Dram",
        "$mainUrl/tur/komedi-filmleri" to "Komedi",
        "$mainUrl/tur/korku-filmleri" to "Korku",
        "$mainUrl/tur/romantik-filmleri" to "Romantik",
        "$mainUrl/tur/tarih-filmleri" to "Tarih",
        "$mainUrl/tur/bilim-kurgu-filmleri" to "Bilim Kurgu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page"
        val document = app.get(url, headers = headers).document
        val results = document.select("article, .film-box, .movie-item, .poster, .movie, .film, .item, .movie-box")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(60)
        val hasNext = document.selectFirst("a.next, a[rel=next], .pagination a.next, .pagination .next") != null || results.size >= 10
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    private fun Element.findCard(): Element {
        var current: Element? = this
        repeat(5) {
            val value = current
            if (value != null && value.selectFirst("img") != null && value.select("a[href]").isNotEmpty()) return value
            current = value?.parent()
        }
        return this
    }

    private fun imageUrl(element: Element?): String? {
        if (element == null) return null
        val image = element.selectFirst("img") ?: return null
        val raw = image.attr("data-src").ifBlank {
            image.attr("data-lazy-src").ifBlank {
                image.attr("data-original").ifBlank { image.attr("src") }
            }
        }
        return fixUrlNull(raw)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val card = findCard()
        val anchor = if (tagName() == "a") this else card.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href").trim()) ?: return null
        if (!href.startsWith(mainUrl)) return null
        val path = href.removePrefix(mainUrl).substringBefore("?").removeSuffix("/")
        if (path.isBlank() || path == "/film" || path == "/film-izle" || path == "/trendler" ||
            path.startsWith("/tur/") || path.startsWith("/kategori") || path.startsWith("/koleksiyon")
        ) return null

        val title = listOf(
            card.selectFirst("h1,h2,h3,h4,h5,.title,.name,.film-name,.movie-title,.truncate")?.text(),
            card.selectFirst("img")?.attr("alt"),
            anchor.attr("title"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() && it.trim().length > 1 }?.trim() ?: return null

        val poster = imageUrl(card)
        val text = card.text()
        val isSeries = text.contains("dizi", true) || text.contains("sezon", true) ||
            text.contains("bölüm", true) || path.contains("bolum") || path.contains("sezon") ||
            path.contains("tum-bolum")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/film?s=$encoded",
            "$mainUrl/film-izle?s=$encoded"
        )
        for (url in urls) {
            val results = runCatching {
                app.get(url, headers = headers).document
                    .select("article, .film-box, .movie-item, .poster, .movie, .film, .item, .movie-box")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }.getOrNull().orEmpty()
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseEpisodeInfo(href: String, text: String): Pair<Int?, Int>? {
        Regex("""(\d+)[-_]?sezon[-_](\d+)[-_]?bolum""", RegexOption.IGNORE_CASE).find(href)?.let {
            val ep = it.groupValues[2].toIntOrNull() ?: return null
            return it.groupValues[1].toIntOrNull() to ep
        }
        Regex("""(\d+)\s*[.]\s*Bölüm""", RegexOption.IGNORE_CASE).find(text)?.let {
            val ep = it.groupValues[1].toIntOrNull() ?: return null
            return null to ep
        }
        Regex("""Bölüm\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.let {
            val ep = it.groupValues[1].toIntOrNull() ?: return null
            return null to ep
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1, .film h1, .movie-title, .entry-title, .single-title, .post-title")?.text()?.trim()
            ?: document.title().substringBefore("|").trim().takeIf { it.isNotBlank() }
            ?: return null

        val metaImage = document.selectFirst("meta[property='og:image']")
        val poster = if (metaImage != null) {
            fixUrlNull(metaImage.attr("content"))
        } else {
            imageUrl(document.selectFirst(".film, .movie, .poster, .single-poster") ?: document)
        }

        val plot = document.selectFirst(".description, .plot, .summary, .synopsis, .film-description, .entry-content p")?.text()?.trim()

        val episodes = document.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl)) return@mapNotNull null
            if (!href.contains("bolum", ignoreCase = true)) return@mapNotNull null
            val text = a.text().replace(Regex("\\s+"), " ").trim()
            val info = parseEpisodeInfo(href, text) ?: return@mapNotNull null
            val seasonNum = info.first
            val episodeNum = info.second
            newEpisode(href) {
                name = text.ifBlank { "Bölüm $episodeNum" }
                this.episode = episodeNum
                this.season = seasonNum
            }
        }.distinctBy { it.data }

        // Never return empty TvSeries — CloudStream shows "Bağlantı bulunamadı"
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

    private fun normalizeEmbed(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return fixUrlNull(trimmed)
    }

    private fun collectEmbedUrls(document: Document): List<String> {
        val frames = linkedSetOf<String>()
        val html = document.html()

        document.select("[data-frame]").forEach { el ->
            normalizeEmbed(el.attr("data-frame"))?.let { frames.add(it) }
        }

        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { el ->
            val raw = el.attr("src").ifBlank {
                el.attr("data-src").ifBlank { el.attr("data-lazy-src") }
            }
            normalizeEmbed(raw)?.let { frames.add(it) }
        }

        document.select("video source[src], source[src], video[src]").forEach { el ->
            normalizeEmbed(el.attr("src"))?.let { frames.add(it) }
        }

        Regex(
            """(?:https?:)?//(?:www\.)?(?:ok\.ru|odnoklassniki\.ru|bitchute\.com|bysevepoin\.com|listeamed\.net|filemoon|streamwish|vidhide|dood)[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            normalizeEmbed(m.value)?.let { frames.add(it) }
        }

        return frames.filter { url ->
            !url.contains("player.hintfilmizle.com", ignoreCase = true) &&
                (url.startsWith("http://") || url.startsWith("https://"))
        }
    }

    private suspend fun extractBitchute(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching {
            app.get(embedUrl, referer = referer, headers = headers).text
        }.getOrNull() ?: return false

        val mp4 = Regex(
            "https?://seed[^\"'\\s]+\\.bitchute\\.com/[^\"'\\s]+\\.mp4",
            RegexOption.IGNORE_CASE
        ).find(html)?.value ?: return false

        callback(
            newExtractorLink(
                source = name,
                name = "BitChute",
                url = mp4,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.bitchute.com/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Referer" to "https://www.bitchute.com/")
            }
        )
        return true
    }

    private suspend fun extractOkRu(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching {
            app.get(
                embedUrl.replace("/video/", "/videoembed/"),
                headers = headers + mapOf("Referer" to "https://ok.ru/")
            ).text
        }.getOrNull() ?: return false

        var found = false
        // Match url fields that look like media
        Regex(""""url"\s*:\s*"(//[^\"]+|https?:[^\"]+)"""").findAll(html).forEach { m ->
            var videoUrl = m.groupValues[1].replace("\\/", "/")
            if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
            if (!videoUrl.contains("okcdn") && !videoUrl.contains("mycdn") &&
                !videoUrl.contains(".mp4") && !videoUrl.contains("video")
            ) return@forEach

            callback(
                newExtractorLink(
                    source = "OkRu",
                    name = "OkRu",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://ok.ru/"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        return found
    }

    private suspend fun extractGenericStreams(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(embedUrl, referer = referer, headers = headers)
        }.getOrNull() ?: return false

        val html = response.text
        var found = false

        response.document.select("iframe[src], iframe[data-src]").forEach { el ->
            val nested = normalizeEmbed(el.attr("src").ifBlank { el.attr("data-src") }) ?: return@forEach
            if (nested == embedUrl) return@forEach
            if (runCatching { loadExtractor(nested, embedUrl, subtitleCallback, callback) }.getOrDefault(false)) {
                found = true
            }
        }

        Regex(
            "(https?://[^\"'\\s<>]+?\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>]*)?)",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            val stream = m.groupValues[1]
            if (stream.contains("google", true) || stream.contains("facebook", true)) return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = stream,
                    type = if (stream.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val embeds = collectEmbedUrls(document)
        if (embeds.isEmpty()) return false

        var found = false

        for (embed in embeds) {
            val viaExtractor = runCatching {
                loadExtractor(embed, data, subtitleCallback, callback)
            }.getOrDefault(false)
            if (viaExtractor) {
                found = true
                continue
            }

            when {
                embed.contains("bitchute.com", ignoreCase = true) -> {
                    if (extractBitchute(embed, data, callback)) found = true
                }
                embed.contains("ok.ru", ignoreCase = true) ||
                    embed.contains("odnoklassniki", ignoreCase = true) -> {
                    if (extractOkRu(embed, callback)) found = true
                }
                else -> {
                    if (extractGenericStreams(embed, data, subtitleCallback, callback)) found = true
                }
            }
        }

        return found
    }
}
