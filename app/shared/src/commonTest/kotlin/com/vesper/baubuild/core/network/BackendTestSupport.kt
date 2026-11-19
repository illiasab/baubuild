package com.vesper.baubuild.core.network

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun MockRequestHandleScope.jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

internal fun MockRequestHandleScope.apiError(status: Int, code: String): HttpResponseData =
    jsonResponse("""{"error":{"code":"$code","message":"m"}}""", HttpStatusCode.fromValue(status))

internal fun HttpRequestData.jsonBody(): JsonObject? =
    (body as? TextContent)?.text?.let { Json.parseToJsonElement(it).jsonObject }
        ?: (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()?.let { Json.parseToJsonElement(it).jsonObject }

internal fun userJson(
    id: String = "u1",
    email: String = "sam.carter@stu.bau.edu",
    role: String = "student",
    name: String = "Sam Carter",
) = """{"id":"$id","name":"$name","initials":"SC","avatar":null,"bio":"Hi","role":"$role","department":null,
"skills":["Kotlin"],"classes":["CMPS433"],"email":"$email","new_field":{"x":1}}"""

internal fun tokenJson(access: String, refresh: String, expiresIn: Long = 3600, user: String = userJson()) =
    """{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,"user":$user,
"entitlement":{"tier":"bau_free","expires_at":null}}"""
