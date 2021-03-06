package eu.kanade.tachiyomi.data.source.online.english

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.network.POST
import eu.kanade.tachiyomi.data.source.model.MangasPage
import eu.kanade.tachiyomi.data.source.model.Page
import eu.kanade.tachiyomi.data.source.online.ParsedOnlineSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.*

class Readmangatoday(override val id: Int) : ParsedOnlineSource() {

    override val name = "ReadMangaToday"

    override val baseUrl = "http://www.readmanga.today"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient get() = network.cloudflareClient

    /**
     * Search only returns data with this set
     */
    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64)")
        add("X-Requested-With", "XMLHttpRequest")
    }

    override fun popularMangaInitialUrl() = "$baseUrl/hot-manga/"

    override fun latestUpdatesInitialUrl() = "$baseUrl/latest-releases/"

    override fun popularMangaSelector() = "div.hot-manga > div.style-list > div.box"

    override fun latestUpdatesSelector() = "div.hot-manga > div.style-grid > div.box"

    override fun popularMangaFromElement(element: Element, manga: Manga) {
        element.select("div.title > h2 > a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.attr("title")
        }
    }

    override fun latestUpdatesFromElement(element: Element, manga: Manga) {
        popularMangaFromElement(element, manga)
    }

    override fun popularMangaNextPageSelector() = "div.hot-manga > ul.pagination > li > a:contains(»)"

    override fun latestUpdatesNextPageSelector(): String = "div.hot-manga > ul.pagination > li > a:contains(»)"

    override fun searchMangaInitialUrl(query: String, filters: List<Filter<*>>) =
            "$baseUrl/service/advanced_search"


    override fun searchMangaRequest(page: MangasPage, query: String, filters: List<Filter<*>>): Request {
        if (page.page == 1) {
            page.url = searchMangaInitialUrl(query, filters)
        }

        val builder = okhttp3.FormBody.Builder()
        builder.add("manga-name", query)
        for (filter in if (filters.isEmpty()) this@Readmangatoday.filters else filters) {
            when (filter) {
                is TextField -> builder.add(filter.key, filter.state)
                is Type -> builder.add("type", arrayOf("all", "japanese", "korean", "chinese")[filter.state])
                is Status -> builder.add("status", arrayOf("both", "completed", "ongoing")[filter.state])
                is Genre -> when (filter.state) {
                    Filter.TriState.STATE_INCLUDE -> builder.add("include[]", filter.id.toString())
                    Filter.TriState.STATE_EXCLUDE -> builder.add("exclude[]", filter.id.toString())

                }
            }
        }
        return POST(page.url, headers, builder.build())
    }

    override fun searchMangaSelector() = "div.style-list > div.box"

    override fun searchMangaFromElement(element: Element, manga: Manga) {
        element.select("div.title > h2 > a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.attr("title")
        }
    }

    override fun searchMangaNextPageSelector() = "div.next-page > a.next"

    override fun mangaDetailsParse(document: Document, manga: Manga) {
        val detailElement = document.select("div.movie-meta").first()

        manga.author = document.select("ul.cast-list li.director > ul a").first()?.text()
        manga.artist = document.select("ul.cast-list li:not(.director) > ul a").first()?.text()
        manga.genre = detailElement.select("dl.dl-horizontal > dd:eq(5)").first()?.text()
        manga.description = detailElement.select("li.movie-detail").first()?.text()
        manga.status = detailElement.select("dl.dl-horizontal > dd:eq(3)").first()?.text().orEmpty().let { parseStatus(it) }
        manga.thumbnail_url = detailElement.select("img.img-responsive").first()?.attr("src")
    }

    private fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> Manga.ONGOING
        status.contains("Completed") -> Manga.COMPLETED
        else -> Manga.UNKNOWN
    }

    override fun chapterListSelector() = "ul.chp_lst > li"

    override fun chapterFromElement(element: Element, chapter: Chapter) {
        val urlElement = element.select("a").first()

        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.select("span.val").text()
        chapter.date_upload = element.select("span.dte").first()?.text()?.let { parseChapterDate(it) } ?: 0
    }

    private fun parseChapterDate(date: String): Long {
        val dateWords: List<String> = date.split(" ")

        if (dateWords.size == 3) {
            val timeAgo = Integer.parseInt(dateWords[0])
            var date: Calendar = Calendar.getInstance()

            if (dateWords[1].contains("Minute")) {
                date.add(Calendar.MINUTE, -timeAgo)
            } else if (dateWords[1].contains("Hour")) {
                date.add(Calendar.HOUR_OF_DAY, -timeAgo)
            } else if (dateWords[1].contains("Day")) {
                date.add(Calendar.DAY_OF_YEAR, -timeAgo)
            } else if (dateWords[1].contains("Week")) {
                date.add(Calendar.WEEK_OF_YEAR, -timeAgo)
            } else if (dateWords[1].contains("Month")) {
                date.add(Calendar.MONTH, -timeAgo)
            } else if (dateWords[1].contains("Year")) {
                date.add(Calendar.YEAR, -timeAgo)
            }

            return date.getTimeInMillis()
        }

        return 0L
    }

    override fun pageListParse(document: Document, pages: MutableList<Page>) {
        document.select("ul.list-switcher-2 > li > select.jump-menu").first().getElementsByTag("option").forEach {
            pages.add(Page(pages.size, it.attr("value")))
        }
        pages.getOrNull(0)?.imageUrl = imageUrlParse(document)
    }

    override fun imageUrlParse(document: Document) = document.select("img.img-responsive-2").first().attr("src")

    private class Status() : Filter.TriState("Completed")
    private class Genre(name: String, val id: Int) : Filter.TriState(name)
    private class TextField(name: String, val key: String) : Filter.Text(name)
    private class Type() : Filter.List<String>("Type", arrayOf("All", "Japanese Manga", "Korean Manhwa", "Chinese Manhua"))

    // [...document.querySelectorAll("ul.manga-cat span")].map(el => `Genre("${el.nextSibling.textContent.trim()}", ${el.getAttribute('data-id')})`).join(',\n')
    // http://www.readmanga.today/advanced-search
    override fun getFilterList(): List<Filter<*>> = listOf(
            TextField("Author", "author-name"),
            TextField("Artist", "artist-name"),
            Type(),
            Status(),
            Filter.Header("Genres"),
            Genre("Action", 2),
            Genre("Adventure", 4),
            Genre("Comedy", 5),
            Genre("Doujinshi", 6),
            Genre("Drama", 7),
            Genre("Ecchi", 8),
            Genre("Fantasy", 9),
            Genre("Gender Bender", 10),
            Genre("Harem", 11),
            Genre("Historical", 12),
            Genre("Horror", 13),
            Genre("Josei", 14),
            Genre("Lolicon", 15),
            Genre("Martial Arts", 16),
            Genre("Mature", 17),
            Genre("Mecha", 18),
            Genre("Mystery", 19),
            Genre("One shot", 20),
            Genre("Psychological", 21),
            Genre("Romance", 22),
            Genre("School Life", 23),
            Genre("Sci-fi", 24),
            Genre("Seinen", 25),
            Genre("Shotacon", 26),
            Genre("Shoujo", 27),
            Genre("Shoujo Ai", 28),
            Genre("Shounen", 29),
            Genre("Shounen Ai", 30),
            Genre("Slice of Life", 31),
            Genre("Smut", 32),
            Genre("Sports", 33),
            Genre("Supernatural", 34),
            Genre("Tragedy", 35),
            Genre("Yaoi", 36),
            Genre("Yuri", 37)
    )
}