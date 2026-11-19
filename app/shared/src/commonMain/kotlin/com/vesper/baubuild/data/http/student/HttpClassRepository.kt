package com.vesper.baubuild.data.http.student

import com.vesper.baubuild.core.model.BauClass
import com.vesper.baubuild.core.network.apiCall
import com.vesper.baubuild.data.http.student.dto.ClassDto
import com.vesper.baubuild.data.http.student.dto.PageDto
import com.vesper.baubuild.data.repository.ClassRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/** [ClassRepository] on `GET /v1/me/classes` → `{"items": [{id, code, name, term, instructor, open_project_count}]}`. */
class HttpClassRepository(private val client: HttpClient) : ClassRepository {
    override suspend fun myClasses(): List<BauClass> =
        apiCall { client.get("v1/me/classes").body<PageDto<ClassDto>>() }.items.map { it.toDomain() }
}
