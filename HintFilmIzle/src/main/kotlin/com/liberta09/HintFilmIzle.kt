package com.liberta09

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HintFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hintfilmizle.com"
    override var name = "HintFilmİzle"
    override val lang = "tr"
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
        val items = app.get(url).document.select("a[href]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .take(40)
        return newHomePageResponse(request.name, items, hasNext = items.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get(mainUrl + "/?s=" + query.urlEncode()).document.select("a[href]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .take(40)

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = absUrl("href").takeIf { it.startsWith(mainUrl) } ?: return null
        val path = href.removePrefix(mainUrl).substringBefore("?").removeSuffix("/")

        if (path.isBlank() ||
            path in setOf(
                "/film", "/film-izle", "/trendler", "/en-iyiler", "/yeni-filmler",
                "/yabanci-dizi-izle", "/koleksiyon", "/forum", "/iletisim", "/haberler"
            ) ||
            path.startsWith("/kategori/") ||
            path.startsWith("/tur/") ||
            path.startsWith("/koleksiyon/") ||
            path.startsWith("/oyuncu/") ||
            path.startsWith("/yonetmen/")
        ) return null

        // Sadece gerçekten poster taşıyan kart bağlantılarını kabul et.
        // Böylece filtrelerdeki "Dizi", menü bağlantıları ve diğer metin
        // bağlantıları katalog öğesi olarak yanlışlıkla eklenmez.
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

        val isSeries = href.contains("dizi", true) || href.contains("sezon", true) ||
            href.contains("bolum", true) || title.contains("dizi", true)

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
            val m = Regex(
                "(\\d+)\\.?\\s*Bölüm.*?(\\d+)\\.?\\s*Sezon",
                RegexOption.IGNORE_CASE
            ).find(text)

            if (m != null && href.startsWith(mainUrl)) {
                Episode(
                    name = text,
                    season = m.groupValues[2].toIntOrNull() ?: 1,
                    episode = m.groupValues[1].toIntOrNull() ?: 1,
                    data = href
                )
            } else null
        }.distinctBy { it.data }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    /**
     * HintFilmIzle'nin Kinescope oynatıcında gerçek HLS manifesti JavaScript
     * tarafından imzalanarak oluşturuluyor. Normal HTTP GET ile iframe'i
     * okumak bu yüzden yeterli değil. WebViewResolver tarayıcı isteğini
     * yakalayıp imzalı .m3u8 URL'sini ve gerekli header'ları alıyor.
     */
    private suspend fun loadKinescope(
        iframeUrl: String,
        parentUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\\.m3u8(?:\\?|$)"""),
                additionalUrls = listOf(
                    Regex("""kinescopecdn\\.net/hls/""")
                ),
                userAgent = null,
                useOkhttp = false,
                timeout = 45_000L
            )

            val requestHeaders = mapOf(
                "Referer" to parentUrl,
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            )

            val (finalRequest, _) = resolver.resolveUsingWebView(
                url = iframeUrl,
                referer = parentUrl,
                headers = requestHeaders
            )

            if (finalRequest == null) return false

            val manifestUrl = finalRequest.url.toString()
            if (!manifestUrl.contains(".m3u8", ignoreCase = true)) return false

            val browserHeaders = finalRequest.headers.toMap().toMutableMap()
            if (browserHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
                browserHeaders["Referer"] = iframeUrl
            }

            M3u8Helper.generateM3u8(
                name = "HintFilmİzle - Kinescope",
                streamUrl = manifestUrl,
                referer = iframeUrl,
                headers = browserHeaders
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
            val src = it.absUrl("src").ifBlank { it.absUrl("data-src") }
                .takeIf { value -> value.isNotBlank() } ?: return@forEach

            if (src.contains("kinescope", ignoreCase = true) ||
                src.contains("kinescopecdn.net", ignoreCase = true)
            ) {
                if (loadKinescope(src, data, subtitleCallback, callback)) {
                    found = true
                    return@forEach
                }
            }

            if (runCatching {
                loadExtractor(src, data, subtitleCallback, callback)
            }.getOrDefault(false)) {
                found = true
            }
        }

        document.select("a[href]").forEach {
            val href = it.absUrl("href")
            if (href.contains("vidmoly", true) ||
                href.contains("vidhide", true) ||
                href.contains("streamtape", true) ||
                href.contains("voe.sx", true) ||
                href.contains("ok.ru", true)
            ) {
                if (runCatching {
                    loadExtractor(href, data, subtitleCallback, callback)
                }.getOrDefault(false)) {
                    found = true
                }
            }
        }

        return found
    }
}
