// Adaptado de FlixLatam (CS-Karma) para CineHax.
// Metadata (título, sinopsis, poster, reparto, trailer, recomendadas, episodios) vía TMDB API.
// Links de video vía scraping de CineHax + Unlimplay (EMBEDS).

package com.byayzen

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class CineHax : MainAPI() {
    override var mainUrl = "https://cinehax.com"
    override var name = "CineHax"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImg = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdrop = "https://image.tmdb.org/t/p/w1280"

    override val mainPage = mainPageOf(
        "movie:trending/movie/day" to "Lo más visto hoy",
        "movie:movie/popular" to "Películas populares",
        "movie:movie/top_rated" to "Películas mejor calificadas",
        "upcoming:" to "Próximamente",
        "tv:tv/popular" to "Series populares",
        "tv:tv/top_rated" to "Series mejor calificadas",
        "genre:28" to "Acción",
        "genre:878" to "Ciencia ficción",
        "genre:35" to "Comedia",
        "genre:27" to "Terror",
        "genre:18" to "Drama",
        "genre:53" to "Suspenso",
        "genre:14" to "Fantasía",
        "genre:80" to "Crimen",
        "genre:9648" to "Misterio",
        "genre:12" to "Aventura"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data

        if (data.startsWith("upcoming:")) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val maxDateCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.YEAR, 1) }
            val maxDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(maxDateCal.time)

            val url = "$tmdbApi/discover/movie?api_key=$TMDB_API_KEY&language=es-ES&page=$page&region=PE" +
                    "&sort_by=popularity.desc&primary_release_date.gte=$today&primary_release_date.lte=$maxDate"

            val parsed = tryParseJson<TmdbSearchResponse>(app.get(url).text)
            val items = parsed?.results?.map { movie ->
                val dateLabel = formatReleaseDate(movie.release_date)
                val displayTitle = if (dateLabel != null) "${movie.title} ($dateLabel)" else movie.title
                newMovieSearchResponse(displayTitle, "$mainUrl/watch/?type=movie&id=${movie.id}", TvType.Movie) {
                    this.posterUrl = movie.poster_path?.let { "$tmdbImg$it" }
                    this.score = Score.from10(movie.vote_average)
                }
            } ?: emptyList()

            return newHomePageResponse(request.name, items, hasNext = parsed?.let { it.page < it.total_pages } ?: false)
        }

        val isTv = data.startsWith("tv:")
        val url = if (data.startsWith("genre:")) {
            val genreId = data.substringAfter(":")
            "$tmdbApi/discover/movie?api_key=$TMDB_API_KEY&language=es-ES&page=$page&with_genres=$genreId&sort_by=popularity.desc"
        } else {
            val endpoint = data.substringAfter(":")
            "$tmdbApi/$endpoint?api_key=$TMDB_API_KEY&language=es-ES&page=$page"
        }

        return if (isTv) {
            val parsed = tryParseJson<TmdbTvSearchResponse>(app.get(url).text)
            val items = parsed?.results?.map { it.toSearchResponse() } ?: emptyList()
            newHomePageResponse(request.name, items, hasNext = parsed?.let { it.page < it.total_pages } ?: false)
        } else {
            val parsed = tryParseJson<TmdbSearchResponse>(app.get(url).text)
            val items = parsed?.results?.map { it.toSearchResponse() } ?: emptyList()
            newHomePageResponse(request.name, items, hasNext = parsed?.let { it.page < it.total_pages } ?: false)
        }
    }

    private val mesesEs = listOf(
        "ene", "feb", "mar", "abr", "may", "jun",
        "jul", "ago", "sep", "oct", "nov", "dic"
    )

    private fun formatReleaseDate(dateStr: String?): String? {
        if (dateStr.isNullOrEmpty()) return null
        val parts = dateStr.split("-")
        if (parts.size != 3) return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12) return null
        return "$day ${mesesEs[month - 1]}"
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")

        val movieUrl = "$tmdbApi/search/movie?api_key=$TMDB_API_KEY&query=$encoded&language=es-ES&page=$page&include_adult=false"
        val tvUrl = "$tmdbApi/search/tv?api_key=$TMDB_API_KEY&query=$encoded&language=es-ES&page=$page&include_adult=false"

        val movies = tryParseJson<TmdbSearchResponse>(app.get(movieUrl).text)
        val shows = tryParseJson<TmdbTvSearchResponse>(app.get(tvUrl).text)

        val results = (movies?.results?.map { it.toSearchResponse() } ?: emptyList()) +
                (shows?.results?.map { it.toSearchResponse() } ?: emptyList())

        val hasNext = (movies?.let { it.page < it.total_pages } ?: false) ||
                (shows?.let { it.page < it.total_pages } ?: false)

        return newSearchResponseList(results, hasNext = hasNext)
    }

    private fun TmdbMovie.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(title, "$mainUrl/watch/?type=movie&id=$id", TvType.Movie) {
            this.posterUrl = poster_path?.let { "$tmdbImg$it" }
            this.year = release_date?.take(4)?.toIntOrNull()
            this.score = Score.from10(vote_average)
        }
    }

    private fun TmdbTvShow.toSearchResponse(): SearchResponse {
        return newTvSeriesSearchResponse(name, "$mainUrl/watch/?type=tv&id=$id", TvType.TvSeries) {
            this.posterUrl = poster_path?.let { "$tmdbImg$it" }
            this.year = first_air_date?.take(4)?.toIntOrNull()
            this.score = Score.from10(vote_average)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.get(1) ?: return null

        return if (url.contains("type=tv")) {
            loadTvSeries(id, url)
        } else {
            loadMovie(id, url)
        }
    }

    private suspend fun loadMovie(id: String, url: String): LoadResponse? {
        val detailsUrl = "$tmdbApi/movie/$id?api_key=$TMDB_API_KEY&language=es-ES&append_to_response=credits,videos,recommendations"
        val details = tryParseJson<TmdbMovieDetails>(app.get(detailsUrl).text) ?: return null

        val watchUrl = "$mainUrl/watch/?type=movie&id=$id"

        val castList = details.credits?.cast?.take(10)?.map { member ->
            ActorData(
                Actor(member.name, member.profile_path?.let { "$tmdbImg$it" }),
                roleString = member.character
            )
        }

        val trailerKey = details.videos?.results
            ?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" }
            ?.key
        val trailerUrl = trailerKey?.let { "https://www.youtube.com/watch?v=$it" }

        val recommendations = details.recommendations?.results?.map { it.toSearchResponse() }

        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val isUpcoming = details.release_date.isNullOrEmpty() || details.release_date > today

        return newMovieLoadResponse(details.title, url, TvType.Movie, watchUrl) {
            this.posterUrl = details.poster_path?.let { "$tmdbImg$it" }
            this.backgroundPosterUrl = details.backdrop_path?.let { "$tmdbBackdrop$it" }
            this.plot = details.overview
            this.year = details.release_date?.take(4)?.toIntOrNull()
            this.tags = details.genres?.map { it.name }
            this.score = Score.from10(details.vote_average)
            this.duration = details.runtime
            this.actors = castList
            this.recommendations = recommendations
            this.comingSoon = isUpcoming
            addTMDbId(id)
            if (trailerUrl != null) addTrailer(trailerUrl)
        }
    }

    private suspend fun loadTvSeries(id: String, url: String): LoadResponse? {
        val detailsUrl = "$tmdbApi/tv/$id?api_key=$TMDB_API_KEY&language=es-ES&append_to_response=credits,videos,recommendations"
        val details = tryParseJson<TmdbTvDetails>(app.get(detailsUrl).text) ?: return null

        val episodes = details.seasons
            ?.filter { it.season_number > 0 }
            ?.flatMap { season ->
                val seasonUrl = "$tmdbApi/tv/$id/season/${season.season_number}?api_key=$TMDB_API_KEY&language=es-ES"
                val seasonData = tryParseJson<TmdbSeasonDetails>(app.get(seasonUrl).text)

                seasonData?.episodes?.map { ep ->
                    val episodeUrl = "$mainUrl/ver/?tipo=serie&id=$id&season=${season.season_number}&episode=${ep.episode_number}"
                    newEpisode(episodeUrl) {
                        this.name = ep.name
                        this.season = season.season_number
                        this.episode = ep.episode_number
                        this.posterUrl = ep.still_path?.let { "$tmdbImg$it" }
                        this.description = ep.overview
                    }
                } ?: emptyList()
            } ?: emptyList()

        val castList = details.credits?.cast?.take(10)?.map { member ->
            ActorData(
                Actor(member.name, member.profile_path?.let { "$tmdbImg$it" }),
                roleString = member.character
            )
        }

        val trailerKey = details.videos?.results
            ?.firstOrNull { it.site == "YouTube" && it.type == "Trailer" }
            ?.key
        val trailerUrl = trailerKey?.let { "https://www.youtube.com/watch?v=$it" }

        val recommendations = details.recommendations?.results?.map { it.toSearchResponse() }

        return newTvSeriesLoadResponse(details.name, url, TvType.TvSeries, episodes) {
            this.posterUrl = details.poster_path?.let { "$tmdbImg$it" }
            this.backgroundPosterUrl = details.backdrop_path?.let { "$tmdbBackdrop$it" }
            this.plot = details.overview
            this.year = details.first_air_date?.take(4)?.toIntOrNull()
            this.tags = details.genres?.map { it.name }
            this.score = Score.from10(details.vote_average)
            this.actors = castList
            this.recommendations = recommendations
            addTMDbId(id)
            if (trailerUrl != null) addTrailer(trailerUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("Referer" to mainUrl)).document

        val embedUrl = document.selectFirst("iframe#cx-main-iframe")?.attr("data-src")
            ?: document.selectFirst("[data-url]")?.attr("data-url")
            ?: return false

        val fixedEmbedUrl = fixUrlNull(embedUrl) ?: return false

        // Unlimplay a veces todavía está resolviendo servidores del lado del
        // backend (PHP) cuando llega este request, y devuelve un EMBEDS vacío
        // o incompleto. Reintentamos unas cuantas veces con una pequeña espera
        // antes de darnos por vencidos.
        var embeds: Map<String, Map<String, String>> = emptyMap()
        var attempts = 0
        while (embeds.isEmpty() && attempts < 5) {
            attempts++
            val unlimplayHtml = app.get(fixedEmbedUrl, referer = data).text
            val embedsJson = extractBalancedJson(unlimplayHtml, "const EMBEDS")

            if (embedsJson != null) {
                val rawEmbeds = tryParseJson<Map<String, Any?>>(embedsJson)
                if (rawEmbeds != null) {
                    // Parseo defensivo: descarta cualquier clave que no sea un
                    // mapa de idioma -> servidores (por ej. "searched_names",
                    // que es un array y rompía el parseo estricto anterior).
                    embeds = rawEmbeds.mapNotNull { (lang, value) ->
                        val serverMap = value as? Map<*, *> ?: return@mapNotNull null
                        val filtered = serverMap.entries.mapNotNull { (k, v) ->
                            val key = k as? String ?: return@mapNotNull null
                            val srvUrl = v as? String ?: return@mapNotNull null
                            key to srvUrl
                        }.toMap()
                        if (filtered.isEmpty()) null else lang to filtered
                    }.toMap()
                }
            }

            if (embeds.isEmpty() && attempts < 5) delay(700)
        }

        if (embeds.isEmpty()) return false

        // Prioriza doblaje en español; si un idioma completo falla, recién
        // ahí se pasa al siguiente. Dentro de un mismo idioma, los servidores
        // se resuelven en paralelo (semaphore de 4) para que sea rápido.
        val langOrder = listOf("latino", "español", "castellano", "subtitulado")
        val orderedLangs = embeds.keys.sortedBy { lang ->
            langOrder.indexOfFirst { it.equals(lang, ignoreCase = true) }
                .let { if (it == -1) langOrder.size else it }
        }

        val semaphore = Semaphore(4)
        var anySuccess = false

        for (lang in orderedLangs) {
            val servers = embeds[lang] ?: continue
            val results = coroutineScope {
                servers.values.map { serverUrl ->
                    async {
                        semaphore.withPermit {
                            runCatching {
                                // loadExtractor recibe un callback NO-suspend, así que acá
                                // solo juntamos los links tal cual, sin tocarlos.
                                val collected = mutableListOf<ExtractorLink>()
                                val ok = loadExtractor(serverUrl, fixedEmbedUrl, subtitleCallback) { link ->
                                    collected.add(link)
                                }
                                // Recién acá (ya de vuelta en contexto suspend) los
                                // renombramos con la etiqueta de idioma y los entregamos.
                                collected.forEach { link ->
                                    val renamed = newExtractorLink(
                                        source = link.source,
                                        name = "${link.name} ${langLabel(lang)}",
                                        url = link.url,
                                        type = link.type
                                    ) {
                                        this.referer = link.referer
                                        this.quality = link.quality
                                        this.headers = link.headers
                                    }
                                    callback(renamed)
                                }
                                ok
                            }.getOrDefault(false)
                        }
                    }
                }.awaitAll()
            }
            if (results.any { it }) anySuccess = true
        }

        return anySuccess
    }

    private fun langLabel(lang: String): String = when (lang.lowercase()) {
        "latino" -> "[LAT]"
        "español", "castellano" -> "[CAST]"
        "subtitulado" -> "[SUB-ENG]"
        else -> "[${lang.uppercase()}]"
    }

    private fun extractBalancedJson(html: String, marker: String): String? {
        val markerIndex = html.indexOf(marker)
        if (markerIndex == -1) return null
        val braceStart = html.indexOf("{", markerIndex)
        if (braceStart == -1) return null

        var depth = 0
        for (i in braceStart until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(braceStart, i + 1)
                }
            }
        }
        return null
    }
}

data class TmdbSearchResponse(
    val page: Int,
    val results: List<TmdbMovie>,
    val total_pages: Int
)

data class TmdbMovie(
    val id: Int,
    val title: String,
    val poster_path: String? = null,
    val release_date: String? = null,
    val vote_average: Double? = null
)

data class TmdbMovieDetails(
    val id: Int,
    val title: String,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val runtime: Int? = null,
    val vote_average: Double? = null,
    val genres: List<TmdbGenre>? = null,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideosResponse? = null,
    val recommendations: TmdbSearchResponse? = null
)

data class TmdbTvSearchResponse(
    val page: Int,
    val results: List<TmdbTvShow>,
    val total_pages: Int
)

data class TmdbTvShow(
    val id: Int,
    val name: String,
    val poster_path: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null
)

data class TmdbTvDetails(
    val id: Int,
    val name: String,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
    val genres: List<TmdbGenre>? = null,
    val seasons: List<TmdbSeasonSummary>? = null,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideosResponse? = null,
    val recommendations: TmdbTvSearchResponse? = null
)

data class TmdbSeasonSummary(
    val season_number: Int,
    val episode_count: Int? = null
)

data class TmdbSeasonDetails(
    val episodes: List<TmdbEpisode>? = null
)

data class TmdbEpisode(
    val episode_number: Int,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null
)

data class TmdbGenre(val id: Int, val name: String)

data class TmdbCredits(val cast: List<TmdbCastMember>? = null)

data class TmdbCastMember(
    val name: String,
    val character: String? = null,
    val profile_path: String? = null
)

data class TmdbVideosResponse(val results: List<TmdbVideo>? = null)

data class TmdbVideo(val key: String, val site: String, val type: String)
