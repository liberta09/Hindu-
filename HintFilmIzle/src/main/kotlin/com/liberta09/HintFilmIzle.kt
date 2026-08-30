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
        val document = app.get(url).document
        val results = document.select("article, .film-box, .movie-item, .poster, .movie, .film, .item, .belink a, .movie-box")
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
        if (path.isBlank() || path == "/film" || path == "/film-izle" || path == "/trendler" || path.startsWith("/tur/") || path.startsWith("/kategori") || path.startsWith("/koleksiyon")) return null

        val title = listOf(
            card.selectFirst("h1,h2,h3,h4,h5,.title,.name,.film-name,.movie-title,.truncate")?.text(),
            card.selectFirst("img")?.attr("alt"),
            anchor.attr("title"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() && it.trim().length > 1 }?.trim() ?: return null

        val poster = imageUrl(card)
        val text = card.text()
        val isSeries = text.contains("dizi", true) || text.contains("sezon", true) || text.contains("bölüm", true) || path.startsWith("/dizi/") || path.contains("/series/") || path.contains("bolum")

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
                app.get(url).document
                    .select("article, .film-box, .movie-item, .poster, .movie, .film, .item, .movie-box")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }.getOrNull().orEmpty()
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
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
            val text = a.text().trim()
            val href = fixUrlNull(a.attr("href"))
            val match = Regex("(?:S|Sezon\\s*)?(\\d+)?\\s*(?:x|[.]?Bölüm|Episode)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)
            val episodeNumber = match?.groupValues?.lastOrNull()?.toIntOrNull()
            if (href != null && episodeNumber != null) {
                newEpisode(href) {
                    name = text
                    episode = episodeNumber
                }
            } else null
        }.distinctBy { it.data }

        val isSeries = episodes.isNotEmpty() || document.text().contains("sezon", true) || document.text().contains("bölüm", true) || url.contains("/dizi/")

        return if (isSeries) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val html = document.html()

        val frames = mutableSetOf<String>()

        // 1) data-frame attribute (site uses this for player links)
        document.select("[data-frame]").forEach { el ->
            fixUrlNull(el.attr("data-frame"))?.let { frames.add(it) }
        }

        // 2) Classic iframes / sources
        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src], video source[src], source[src]").forEach { el ->
            val raw = el.attr("src").ifBlank {
                el.attr("data-src").ifBlank { el.attr("data-lazy-src") }
            }
            fixUrlNull(raw)?.let { frames.add(it) }
        }

        // 3) Regex for player.hintfilmizle.com and common embed patterns in HTML
        Regex(
            """(?:https?:)?//(?:player\.)?hintfilmizle\.com/embed/[0-9]+""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { match ->
            val url = if (match.value.startsWith("//")) "https:${match.value}" else match.value
            frames.add(url)
        }

        // 4) HTML comments: <!--frame:URL-->
        Regex("<!--\\s*frame:\\s*([^>]+)-->", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            fixUrlNull(match.groupValues[1].trim())?.let { frames.add(it) }
        }

        var found = false

        for (frame in frames) {
            // Try built-in extractors first
            val extracted = runCatching {
                loadExtractor(frame, data, subtitleCallback, callback)
            }.getOrDefault(false)

            if (extracted) {
                found = true
                continue
            }

            // Fallback: open the embed page and look for m3u8 / mp4
            if (frame.contains("player.hintfilmizle.com") || frame.contains("/embed/")) {
                runCatching {
                    val playerDoc = app.get(
                        frame,
                        referer = data,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    ).document
                    val playerHtml = playerDoc.html()

                    // Nested iframes
                    playerDoc.select("iframe[src], iframe[data-src]").forEach { el ->
                        val nested = fixUrlNull(el.attr("src").ifBlank { el.attr("data-src") })
                        if (nested != null) {
                            runCatching { loadExtractor(nested, frame, subtitleCallback, callback) }
                                .onSuccess { if (it) found = true }
                        }
                    }

                    // Direct m3u8 / mp4 links
                    Regex(
                        """(https?://[^"'\\s]+?\.(?:m3u8|mp4)[^"'\\s]*)""",
                        RegexOption.IGNORE_CASE
                    ).findAll(playerHtml).forEach { m ->
                        val stream = m.groupValues[1]
                        callback(
                            newExtractorLink(
                                name,
                                name,
                                stream,
                                type = if (stream.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = frame
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }

                    // file: "..." patterns (JWPlayer etc.)
                    Regex(
                        """(?:file|source|src)\\s*[:=]\\s*["'](https?://[^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    ).findAll(playerHtml).forEach { m ->
                        val stream = m.groupValues[1]
                        if (stream.contains(".m3u8") || stream.contains(".mp4")) {
                            callback(
                                newExtractorLink(
                                    name,
                                    name,
                                    stream,
                                    type = if (stream.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = frame
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                }
            }
        }

        return found || frames.isNotEmpty()
    }
}
