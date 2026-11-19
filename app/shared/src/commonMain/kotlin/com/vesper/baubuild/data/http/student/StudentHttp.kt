package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.data.http.student.dto.PageDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlin.time.Clock
import kotlin.time.Instant

/** Clock used for relative dates ("due in 3 days"); tests pin it. */
typealias NowProvider = () -> Instant

internal val systemNow: NowProvider = { Clock.System.now() }

/** Upper bound on pages followed for one list (20 items per page on the backend → 500 items). */
internal const val MAX_PAGES = 25

/**
 * Follows `next_cursor` from `GET [path]` until the backend stops sending one or [MAX_PAGES] is reached.
 * Paths are relative (no leading slash) so they resolve against `ApiConfig.baseUrl`.
 */
internal suspend inline fun <reified T> HttpClient.getAllPages(
    path: String,
    crossinline configure: HttpRequestBuilder.() -> Unit = {},
): List<T> {
    val all = mutableListOf<T>()
    var cursor: String? = null
    var pages = 0
    do {
        val page: PageDto<T> = apiCall {
            get(path) {
                configure()
                cursor?.let { parameter("cursor", it) }
            }.body()
        }
        all += page.items
        val next = page.nextCursor?.takeIf { it.isNotBlank() && it != cursor }
        cursor = next
        pages++
    } while (cursor != null && pages < MAX_PAGES)
    return all
}
