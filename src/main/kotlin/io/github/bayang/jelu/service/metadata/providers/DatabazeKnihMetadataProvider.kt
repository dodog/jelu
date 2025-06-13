package io.github.bayang.jelu.service.metadata.providers

import io.github.bayang.jelu.dto.BookMetadata
import io.github.bayang.jelu.dto.AuthorDto
import io.github.bayang.jelu.dto.SeriesDto
import io.github.bayang.jelu.service.metadata.MetadataProvider
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.time.LocalDate

@Component
class DatabazeKnihMetadataProvider : MetadataProvider {

    companion object {
        private const val SITE_URL = "https://www.databazeknih.cz"
        private val LANG_MAPPING = mapOf(
            "český" to "ces",
            "slovenský" to "slo",
            "německý" to "deu",
            "polský" to "pol",
            "anglický" to "eng",
            "francouzský" to "fre",
            "španělský" to "spa",
            "italský" to "ita",
            "jiný" to ""
        )
        private const val NO_DESCRIPTION_TEXT = "Popis knihy zde zatím bohužel není..."
        private const val CELY_TEXT = "... celý text"
        private const val EBOOK = "ekniha"
        private const val AUDIOBOOK = "audiokniha"
        private const val CLASSIC_BOOK = "klasická kniha"
    }

    override fun findByIsbn(isbn: String): BookMetadata? = searchDatabazeKnih(isbn)

    override fun findByTitleAndAuthor(title: String, author: String): BookMetadata? =
        searchDatabazeKnih("$title $author")

    private fun searchDatabazeKnih(query: String): BookMetadata? {
        val searchUrl = "$SITE_URL/search?in=books&q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = Jsoup.connect(searchUrl).get()
        // Multi-result page: follow first "a.new" link
        if (isMultiResult(doc)) {
            val firstBookLink = doc.select("p.new a.new").firstOrNull()?.attr("href")
            if (firstBookLink != null) {
                val bookUrl = "$SITE_URL$firstBookLink"
                return parseBookPage(Jsoup.connect(bookUrl).get())
            }
        } else {
            return parseBookPage(doc)
        }
        return null
    }

    private fun isMultiResult(doc: Document): Boolean =
        doc.title().startsWith("Vyhledávání")

    private fun parseBookPage(doc: Document): BookMetadata? {
        val meta = BookMetadata()
        // --- Title & External ID (SID)
        doc.head().select("meta").forEach { metaTag ->
            when (metaTag.attr("property")) {
                "og:title" -> meta.title = metaTag.attr("content")
                "og:url" -> {
                    val url = metaTag.attr("content")
                    val sid = url.substringAfterLast('-', "")
                    if (sid.isNotEmpty()) meta.identifiers = mapOf("databazeknih" to sid)
                }
                "og:image" -> meta.coverUrl = metaTag.attr("content")
            }
        }

        // --- Main [itemprop] fields
        val authors = mutableListOf<AuthorDto>()
        val tags = mutableSetOf<String>()
        val publishers = mutableSetOf<String>()
        var series: SeriesDto? = null
        var description: String? = null
        var rating: Double? = null
        var ratingCount: Int? = null
        var reviewCount: Int? = null
        var pubDate: LocalDate? = null
        var language: String? = null
        var pages: String? = null
        var format: String? = null
        var isbn: String? = null
        var tocEntries: MutableList<String>? = null
        var translatedFromTitle: String? = null
        var firstPublicationDate: LocalDate? = null

        for (item in doc.select("[itemprop]")) {
            when (item.attr("itemprop")) {
                "author" -> {
                    // All <a> links in author
                    item.select("a").forEach { a ->
                        val authorName = a.text().trim()
                        if (authorName.isNotEmpty()) {
                            val url = a.attr("href")
                            val extId = if (url.contains("/autori/") && url.lastIndexOf('-') > 0)
                                url.substring(url.lastIndexOf('-') + 1)
                            else null
                            authors.add(
                                AuthorDto(
                                    name = authorName,
                                    role = "author",
                                    remoteIds = extId?.let { mapOf("databazeknih" to it) } ?: emptyMap()
                                )
                            )
                        }
                    }
                }
                "ilustrator" -> {
                    item.select("a").forEach { a ->
                        val name = a.text().trim()
                        if (name.isNotEmpty()) authors.add(AuthorDto(name = name, role = "artist"))
                    }
                }
                "translator" -> {
                    item.select("a").forEach { a ->
                        val name = a.text().trim()
                        if (name.isNotEmpty()) authors.add(AuthorDto(name = name, role = "translator"))
                    }
                }
                "cover" -> {
                    item.select("a").forEach { a ->
                        val name = a.text().trim()
                        if (name.isNotEmpty()) authors.add(AuthorDto(name = name, role = "cover artist"))
                    }
                }
                "description" -> {
                    val desc = item.nextElementSibling()?.wholeText()?.trim()
                    if (!desc.isNullOrBlank() && desc != NO_DESCRIPTION_TEXT) {
                        val text = desc.split("\n").map { it.trim() }
                            .filter { it != CELY_TEXT }
                            .joinToString("\n")
                            .removeSuffix(CELY_TEXT)
                            .trim()
                        description = text
                    }
                }
                "ratingValue" -> item.text().toDoubleOrNull()?.let { rating = it }
                "genre" -> tags.addAll(item.select("a.genre").map(Element::text))
                "datePublished" -> item.text().let { t ->
                    if (t.isNotEmpty() && t != "?") pubDate = parsePartialDate(t)
                }
                "publisher" -> item.text().takeIf { it.isNotEmpty() }?.let { publishers.add(it) }
                "isbn" -> item.text().takeIf { it.isNotEmpty() }?.let { isbn = it }
                "language" -> item.text().takeIf { it.isNotEmpty() }?.let { l ->
                    language = LANG_MAPPING[l] ?: l
                }
                "numberOfPages" -> item.text().takeIf { it.isNotEmpty() }?.let { pages = it }
            }
        }

        // --- Series
        doc.selectFirst("h3 > a[href^=/serie/]")?.let { serieLink ->
            val seriesName = serieLink.text().trim()
            if (seriesName.isNotEmpty()) {
                // Try to extract series number as well
                val nrElem = doc.selectFirst("span.nowrap > span.odright_pet, span.nowrap > span.odleft_pet")
                val number = nrElem?.text()?.removeSuffix(". díl")?.trim()
                series = SeriesDto(name = seriesName, number = number)
            }
        }

        // --- Translated from original
        doc.selectFirst("span:contains(Originální název:)")?.nextElementSibling()?.let { el ->
            if (el.tagName() == "h4") {
                if (el.childNodeSize() > 0)
                    translatedFromTitle = el.childNode(0).toString().trim()
                if (el.childNodeSize() > 2)
                    firstPublicationDate = parsePartialDate(el.childNode(2).toString())
            }
        }

        // --- Add tags (from <a class="tag">)
        tags.addAll(doc.select("a.tag").map(Element::text))

        // --- Fetch more details page, if possible
        meta.identifiers?.get("databazeknih")?.let { sid ->
            val moreDetailsUrl = "$SITE_URL/book-detail-more-info/$sid"
            val d2 = Jsoup.connect(moreDetailsUrl).get()
            // Language, pages, isbn, illustrators, cover, translators, binding, print run, format
            d2.select("[itemprop]").forEach { item ->
                when (item.attr("itemprop")) {
                    "numberOfPages" -> item.text().takeIf { it.isNotEmpty() }?.let { pages = it }
                    "language" -> item.text().takeIf { it.isNotEmpty() }?.let { l ->
                        language = LANG_MAPPING[l] ?: l
                    }
                    "isbn" -> item.text().takeIf { it.isNotEmpty() }?.let { isbn = it }
                    "ilustrator" -> item.select("a").forEach { a ->
                        val name = a.text().trim()
                        if (name.isNotEmpty()) authors.add(AuthorDto(name = name, role = "artist"))
                    }
                    "cover" -> item.select("a").forEach { a ->
                        val name = a.text().trim()
                        if (name.isNotEmpty()) authors.add(AuthorDto(name = name, role = "cover artist"))
                    }
                    "translator" -> item.select("a").forEach { a ->
                        val name = a.text().trim()
                        if (name.isNotEmpty()) authors.add(AuthorDto(name = name, role = "translator"))
                    }
                }
            }
            // Audio duration (as "pages" if present)
            if (pages == null) {
                d2.selectFirst("span:contains(Délka:)")?.nextSibling()?.let { t ->
                    val v = t.toString().trim()
                    if (v.isNotEmpty()) pages = v
                }
            }
            // 1. vydání originálu: (first publication date)
            if (firstPublicationDate == null) {
                d2.selectFirst("span:contains(1. vydání originálu:)")?.nextSibling()?.let { t ->
                    val v = t.toString().trim()
                    if (v.isNotEmpty()) firstPublicationDate = parsePartialDate(v)
                }
            }
            // Vazba knihy == binding (format)
            d2.selectFirst("span:contains(Vazba knihy:)")?.nextSibling()?.let {
                val v = it.toString().trim()
                if (v.isNotEmpty()) format = v
            }
            // Náklad == circulation (print run)
            d2.selectFirst("span:contains(Náklad:)")?.nextSibling()?.let {
                val v = it.toString().trim()
                if (v.isNotEmpty()) meta.printRun = v
            }
            // Forma == form (format)
            d2.selectFirst("span:contains(Forma:)")?.nextSibling()?.let {
                val v = it.toString().trim()
                if (v.isNotEmpty()) {
                    format = when (v) {
                        EBOOK -> EBOOK
                        AUDIOBOOK -> AUDIOBOOK
                        CLASSIC_BOOK -> CLASSIC_BOOK
                        else -> v
                    }
                }
            }
        }

        // --- Table of Contents (TOC)
        doc.selectFirst("ul#newIcons a[href^=/povidky-z-knihy/]")?.let { tocLink ->
            val tocUrl = "$SITE_URL${tocLink.attr("href")}"
            val tocDoc = Jsoup.connect(tocUrl).get()
            tocDoc.select("table.new.odtop_big td").forEach { td ->
                val a = td.selectFirst("a")
                if (a != null) {
                    val title = a.text()
                    if (!title.isNullOrBlank()) {
                        if (tocEntries == null) tocEntries = mutableListOf()
                        tocEntries!!.add(title)
                    }
                }
            }
        }

        // --- Fill metadata object
        meta.title = meta.title ?: ""
        meta.authors = authors
        meta.tags = tags.toList()
        meta.series = series
        meta.description = description
        meta.rating = rating
        meta.language = language
        meta.publishedDate = pubDate?.toString()
        meta.pageCount = pages?.toIntOrNull()
        meta.format = format
        meta.isbn = isbn
        meta.toc = tocEntries
        meta.translatedFromTitle = translatedFromTitle
        meta.firstPublicationDate = firstPublicationDate?.toString()
        meta.publishers = publishers.toList()
        return if (meta.title?.isNotEmpty() == true) meta else null
    }

    /** Parse partial date strings like "2007" or "10.10.2007" */
    private fun parsePartialDate(text: String): LocalDate? {
        val trimmed = text.trim()
        return try {
            when {
                Regex("""\d{4}-\d{2}-\d{2}""").matches(trimmed) -> LocalDate.parse(trimmed)
                Regex("""\d{2}\.\d{2}\.\d{4}""").matches(trimmed) -> {
                    val parts = trimmed.split('.')
                    LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                }
                Regex("""\d{4}""").matches(trimmed) -> LocalDate.of(trimmed.toInt(), 1, 1)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
