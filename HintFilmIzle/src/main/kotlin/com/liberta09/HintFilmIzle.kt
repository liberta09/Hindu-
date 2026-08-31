package com.liberta09

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film" to "Filmler",
        "$mainUrl/film-izle" to "Filmler & Diziler",
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
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page"
        val document = app.get(url, headers = headers).document
        val results = document.select("article, .film-box, .movie-item, .poster, .movie, .film, .item, .movie-box")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(60)
        val fallback = if (results.isEmpty()) {
            document.select("a[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }.take(60)
        } else results
        val hasNext = document.selectFirst("a.next, a[rel=next], .pagination a.next, .pagination .next") != null || fallback.size >= 10
        return newHomePageResponse(request.name, fallback, hasNext = hasNext)
    }

    private fun Element.findCard(): Element {
        var current: Element? = this
        repeat(6) {
            val value = current ?: return@repeat
            if (value.selectFirst("img") != null && value.select("a[href]").isNotEmpty()) return value
            current = value.parent()
        }
        return this
    }

    private fun imageUrl(element: Element?): String? {
        val image = element?.selectFirst("img") ?: return null
        val raw = listOf("data-src", "data-lazy-src", "data-original", "data-image", "src")
            .firstNotNullOfOrNull { key -> image.attr(key).takeIf { it.isNotBlank() } }
        return fixUrlNull(raw)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val card = findCard()
        val anchor = if (tagName() == "a") this else card.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href").trim()) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val path = href.removePrefix(mainUrl).substringBefore("?").removeSuffix("/")
        if (path.isBlank() || path in setOf(
                "/film", "/film-izle", "/trendler", "/en-iyiler", "/yeni-filmler",
                "/yabanci-dizi-izle", "/koleksiyon", "/forum", "/iletisim", "/haberler"
            ) || path.startsWith("/tur/") || path.startsWith("/kategori/") ||
            path.startsWith("/koleksiyon/") || path.startsWith("/oyuncu/") || path.startsWith("/yonetmen/")) return null

        val poster = imageUrl(card)
        val title = listOf(
            card.selectFirst("h1,h2,h3,h4,h5,.title,.name,.film-name,.movie-title,.truncate")?.text(),
            card.selectFirst("img")?.attr("alt"),
            anchor.attr("title"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() && it.trim().length > 1 }
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?: return null

        val cleanTitle = title
            .replace(Regex("\\s*\\|\\s*HintFilm.*$", RegexOption.IGNORE_CASE), "")
            .removeSuffix(" izle")
            .removeSuffix(" İzle")
            .trim()
        if (cleanTitle.isBlank()) return null

        val year = Regex("\\b(19|20)\\d{2}\\b").find(card.text())?.value?.toIntOrNull()
        val cardText = card.text()
        val isSeries = path.contains("dizi", true) || path.contains("sezon", true) ||
            path.contains("bolum", true) || path.contains("tum-bolum", true) ||
            cardText.contains("dizi", true) || cardText.contains("sezon", true) || cardText.contains("bölüm", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href) {
                posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
            }
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
            val document = runCatching { app.get(url, headers = headers).document }.getOrNull() ?: continue
            val results = document.select("article, .film-box, .movie-item, .poster, .movie, .film, .item, .movie-box")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
                .take(60)
            if (results.isNotEmpty()) return results

            val fallback = document.select("a[href]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }.take(60)
            if (fallback.isNotEmpty()) return fallback
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseEpisodeInfo(href: String, text: String): Pair<Int?, Int>? {
        Regex("""(\\d+)[-_]?sezon[-_](\\d+)[-_]?bolum""", RegexOption.IGNORE_CASE).find(href)?.let {
            return it.groupValues[1].toIntOrNull() to (it.groupValues[2].toIntOrNull() ?: return null)
        }
        Regex("""(\\d+)\\s*[.]?\\s*Bölüm""", RegexOption.IGNORE_CASE).find(text)?.let {
            return null to (it.groupValues[1].toIntOrNull() ?: return null)
        }
        Regex("""Bölüm\\s*(\\d+)""", RegexOption.IGNORE_CASE).find(text)?.let {
            return null to (it.groupValues[1].toIntOrNull() ?: return null)
        }
        Regex("""(\\d+)\\s*sezon\\s*(\\d+)""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toIntOrNull() to (it.groupValues[2].toIntOrNull() ?: return null)
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1, .film h1, .movie-title, .entry-title, .single-title, .post-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore("|").trim()
            ?: url.substringAfterLast("/").replace("-", " ")

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: imageUrl(document.selectFirst(".film, .movie, .poster, .single-poster") ?: document)
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst(".description, .plot, .summary, .synopsis, .film-description, .entry-content")?.text()?.trim()

        val episodes = document.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl) || !href.contains("bolum", true)) return@mapNotNull null
            val text = a.text().replace(Regex("\\s+"), " ").trim()
            val info = parseEpisodeInfo(href, text) ?: return@mapNotNull null
            newEpisode(href) {
                name = text.ifBlank { "Bölüm ${info.second}" }
                episode = info.second
                season = info.first ?: 1
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode }))

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
        val value = raw.trim().trim('"', '\'')
        if (value.startsWith("//")) return "https:$value"
        return fixUrlNull(value)
    }

    private fun collectEmbedUrls(document: Document): List<String> {
        val frames = linkedSetOf<String>()
        val html = document.html()

        document.select("[data-frame], [data-src], [data-url], [data-video]").forEach { el ->
            listOf("data-frame", "data-src", "data-url", "data-video").forEach { key ->
                normalizeEmbed(el.attr(key))?.let { frames.add(it) }
            }
        }
        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src], video[src], video source[src], source[src]").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("data-src").ifBlank { el.attr("data-lazy-src") } }
            normalizeEmbed(raw)?.let { frames.add(it) }
        }

        val urlRegex = Regex("""https?://[^\\s\"'<>]+""", RegexOption.IGNORE_CASE)
        urlRegex.findAll(html).forEach { match ->
            val candidate = normalizeEmbed(match.value) ?: return@forEach
            if (candidate.contains("m3u8", true) || candidate.contains("mp4", true) ||
                candidate.contains("kinescope", true) || candidate.contains("ok.ru", true) ||
                candidate.contains("bitchute", true) || candidate.contains("vidhide", true) ||
                candidate.contains("vidmoly", true) || candidate.contains("streamtape", true) ||
                candidate.contains("voe.sx", true) || candidate.contains("filemoon", true) ||
                candidate.contains("streamwish", true) || candidate.contains("dood", true) ||
                candidate.contains("listeamed", true) || candidate.contains("bysevepoin", true)) {
                frames.add(candidate)
            }
        }

        return frames.filter { it.startsWith("http://") || it.startsWith("https://") }
    }

    private suspend fun loadKinescope(
        iframeUrl: String,
        parentUrl: String,
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
                headers = headers + mapOf("Referer" to parentUrl)
            )
            val manifestUrl = finalRequest?.url?.toString()?.takeIf { it.contains(".m3u8", true) } ?: return false
            val browserHeaders = finalRequest.headers.toMap().toMutableMap()
            if (browserHeaders.keys.none { it.equals("Referer", true) }) browserHeaders["Referer"] = iframeUrl
            M3u8Helper.generateM3u8(
                source = name,
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

    private suspend fun extractOkRu(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val target = embedUrl.replace("/video/", "/videoembed/")
        val html = runCatching { app.get(target, headers = headers + mapOf("Referer" to "https://ok.ru/")).text }.getOrNull() ?: return false
        val videos = Regex("""\"videos\"\\s*:\\s*(\\[[^]]*])""").find(html)?.groupValues?.get(1) ?: return false
        var found = false
        Regex("""\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"url\"\\s*:\\s*\"([^\"]+)\"""").findAll(videos).forEach { m ->
            var url = m.groupValues[2].replace("\\/", "/")
            if (url.startsWith("//")) url = "https:$url"
            callback(newExtractorLink("OkRu", "OkRu ${m.groupValues[1]}", url, ExtractorLinkType.VIDEO) {
                referer = "https://ok.ru/"
                quality = getQualityFromName(m.groupValues[1])
            })
            found = true
        }
        return found
    }

    private suspend fun extractBitchute(embedUrl: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val html = runCatching { app.get(embedUrl, referer = referer, headers = headers).text }.getOrNull() ?: return false
        val mp4 = Regex("""https?://seed[^\"'\\s]+\\.bitchute\\.com/[^\"'\\s]+\\.mp4""", RegexOption.IGNORE_CASE).find(html)?.value ?: return false
        callback(newExtractorLink(name, "BitChute", mp4, ExtractorLinkType.VIDEO) {
            referer = "https://www.bitchute.com/"
            quality = Qualities.Unknown.value
            headers = mapOf("Referer" to "https://www.bitchute.com/")
        })
        return true
    }

    private suspend fun extractGeneric(
        embedUrl: String,
        parentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val response = runCatching { app.get(embedUrl, referer = parentUrl, headers = headers) }.getOrNull() ?: return false
        val doc = response.document

        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { frame ->
            val nested = normalizeEmbed(frame.attr("src").ifBlank { frame.attr("data-src").ifBlank { frame.attr("data-lazy-src") } }) ?: return@forEach
            if (nested != embedUrl && runCatching { loadExtractor(nested, embedUrl, subtitleCallback, callback) }.getOrDefault(false)) found = true
        }

        doc.select("video source[src], video[src], source[src]").forEach { media ->
            val src = normalizeEmbed(media.attr("src")) ?: return@forEach
            if (src.contains(".m3u8", true)) {
                M3u8Helper.generateM3u8(name, src, embedUrl, headers = mapOf("Referer" to embedUrl)).forEach(callback)
                found = true
            } else if (src.contains(".mp4", true)) {
                callback(newExtractorLink(name, "Direct", src, ExtractorLinkType.VIDEO) { referer = embedUrl })
                found = true
            }
        }

        if (!found) {
            val html = response.text
            Regex("""https?://[^\"'<>\\s]+\\.m3u8(?:\\?[^\"'<>\\s]+)?""", RegexOption.IGNORE_CASE).findAll(html).forEach {
                M3u8Helper.generateM3u8(name, it.value, embedUrl, headers = mapOf("Referer" to embedUrl)).forEach(callback)
                found = true
            }
            Regex("""https?://[^\"'<>\\s]+\\.mp4(?:\\?[^\"'<>\\s]+)?""", RegexOption.IGNORE_CASE).findAll(html).forEach {
                callback(newExtractorLink(name, "Direct", it.value, ExtractorLinkType.VIDEO) { referer = embedUrl })
                found = true
            }
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = runCatching { app.get(data, headers = headers).document }.getOrNull() ?: return false
        var found = false
        val embeds = collectEmbedUrls(document)

        for (embed in embeds) {
            when {
                embed.contains("kinescope", true) -> if (loadKinescope(embed, data, callback)) found = true
                embed.contains("ok.ru", true) || embed.contains("odnoklassniki", true) -> if (extractOkRu(embed, callback)) found = true
                embed.contains("bitchute", true) -> if (extractBitchute(embed, data, callback)) found = true
                embed.contains(".m3u8", true) -> {
                    M3u8Helper.generateM3u8(name, embed, data, headers = mapOf("Referer" to data)).forEach(callback)
                    found = true
                }
                embed.contains(".mp4", true) -> {
                    callback(newExtractorLink(name, "Direct", embed, ExtractorLinkType.VIDEO) { referer = data })
                    found = true
                }
                else -> if (runCatching { loadExtractor(embed, data, subtitleCallback, callback) }.getOrDefault(false)) found = true
            }
            if (!found) {
                if (extractGeneric(embed, data, subtitleCallback, callback)) found = true
            }
        }

        // Some versions of the site put the embed URL only inside JavaScript.
        if (!found) {
            val html = document.html()
            Regex("""(?:src|data-frame|data-url|file|source)\\s*[:=]\\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
                .findAll(html).mapNotNull { normalizeEmbed(it.groupValues[1]) }
                .distinct().forEach { embed ->
                    when {
                        embed.contains("kinescope", true) -> if (loadKinescope(embed, data, callback)) found = true
                        embed.contains(".m3u8", true) -> { M3u8Helper.generateM3u8(name, embed, data, headers = mapOf("Referer" to data)).forEach(callback); found = true }
                        embed.contains(".mp4", true) -> { callback(newExtractorLink(name, "Direct", embed, ExtractorLinkType.VIDEO) { referer = data }); found = true }
                        else -> if (runCatching { loadExtractor(embed, data, subtitleCallback, callback) }.getOrDefault(false)) found = true
                    }
                }
        }
        return found
    }
}
