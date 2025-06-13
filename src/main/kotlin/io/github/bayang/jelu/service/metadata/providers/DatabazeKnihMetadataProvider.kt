package io.github.bayang.jelu.service.metadata.providers

import io.github.bayang.jelu.dto.BookMetadata
import io.github.bayang.jelu.service.metadata.MetadataProvider
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.net.URLEncoder

@Component
class DatabazeKnihMetadataProvider : MetadataProvider {

    override fun findByIsbn(isbn: String): BookMetadata? {
        return searchDatabazeKnih(isbn)
    }

    override fun findByTitleAndAuthor(title: String, author: String): BookMetadata? {
        val query = "$title $author"
        return searchDatabazeKnih(query)
    }

    private fun searchDatabazeKnih(query: String): BookMetadata? {
        val searchUrl = "https://www.databazeknih.cz/search?in=books&q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = Jsoup.connect(searchUrl).get()

        // If this is a multi-result page, follow first book link
        if (isMultiResult(doc)) {
            val firstBookLink = doc.select("p.new a.new").firstOrNull()?.attr("href")
            if (firstBookLink != null) {
                val bookUrl = "https://www.databazeknih.cz$firstBookLink"
                return parseBookPage(Jsoup.connect(bookUrl).get())
            }
        } else {
            return parseBookPage(doc)
        }
        return null
    }

    private fun isMultiResult(doc: Document): Boolean {
        return doc.title().startsWith("Vyhledávání")
    }

    private fun parseBookPage(doc: Document): BookMetadata? {
        val meta = BookMetadata()
        // Parse title from meta tag
        doc.head().select("meta[property=og:title]").firstOrNull()?.let {
            meta.title = it.attr("content")
        }

        // ISBN, authors, description, publisher, etc.
        for (itemProp in doc.select("[itemprop]")) {
            when (itemProp.attr("itemprop")) {
                "author" -> {
                    val authors = itemProp.select("a").map(Element::text)
                    meta.authors = authors
                }
                "description" -> {
                    val desc = itemProp.nextElementSibling()?.wholeText()?.trim()
                    if (!desc.isNullOrBlank() && desc != "Popis knihy zde zatím bohužel není...") {
                        meta.description = desc
                    }
                }
                "publisher" -> {
                    meta.publisher = itemProp.text()
                }
                "datePublished" -> {
                    meta.publishedDate = itemProp.text()
                }
                "isbn" -> {
                    meta.isbn = itemProp.text()
                }
                // Extend for more fields as needed
            }
        }
        // Parse other details, e.g., cover image, genres, etc.
        doc.head().select("meta[property=og:image]").firstOrNull()?.let {
            meta.coverUrl = it.attr("content")
        }

        // Example: genres/tags
        val genres = doc.select("a.genre").map(Element::text)
        if (genres.isNotEmpty()) {
            meta.tags = genres
        }

        // TODO: Add more parsing as needed for your use case

        // Return null if there’s not enough data
        return if (meta.title != null && meta.authors.isNotEmpty()) meta else null
    }
}
