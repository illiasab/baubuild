package com.vesper.baubuild.data.http.staff.dto

import com.vesper.baubuild.core.model.SeniorEngineer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// Senior engineers are parsed by hand from the JSON tree: the backend is being rewritten (Python → Go), so field
// names and shapes are read leniently (snake_case or camelCase, string or {name} skills, `avatar` or `avatar_url`).
//
// Proposed list item (`GET /v1/senior-engineers` → `{items:[...]}`, a bare array is accepted too):
//   {"id","name","title","bio","avatar_url","skills":["Kotlin"],"github_username","email","roles":["lead","interviewer"]}
// Public profile (`GET /v1/users/{id}`, exists today — no email, no title):
//   {"id","name","initials","avatar","bio","role","department","skills":[...],"classes":[...]}

/** `{items:[...]}` or `[...]` → engineers; items without an id or name are dropped. */
internal fun parseSeniorEngineerList(root: JsonElement): List<SeniorEngineer> {
    val items = when (root) {
        is JsonArray -> root
        is JsonObject -> root["items"] as? JsonArray ?: root["senior_engineers"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return items.mapNotNull { (it as? JsonObject)?.toSeniorEngineer(fallback = null) }
}

/**
 * One engineer object → [SeniorEngineer]. Missing or blank fields fall back to [fallback] (what the list already
 * showed), so a sparse public profile never erases the title, email or interviewer flag. Null when there is no
 * id or name at all.
 */
internal fun JsonObject.toSeniorEngineer(fallback: SeniorEngineer?): SeniorEngineer? {
    val id = string("id") ?: fallback?.id ?: return null
    val name = string("name", "display_name", "displayName") ?: fallback?.name ?: return null
    val roles = roles()
    return SeniorEngineer(
        id = id,
        name = name,
        title = string("title", "job_title", "jobTitle", "headline") ?: fallback?.title ?: titleFromRoles(roles).orEmpty(),
        bio = string("bio", "about") ?: fallback?.bio.orEmpty(),
        avatarUrl = string("avatar_url", "avatarUrl", "avatar", "photo_url") ?: fallback?.avatarUrl,
        skills = skills().ifEmpty { fallback?.skills.orEmpty() },
        githubUsername = (string("github_username", "githubUsername", "github_login", "github")
            ?.removePrefix("@")?.removePrefix("https://github.com/")?.trim('/')?.takeIf { it.isNotBlank() })
            ?: fallback?.githubUsername,
        email = string("email") ?: fallback?.email,
        isInterviewer = (this["is_interviewer"] as? JsonPrimitive)?.booleanOrNull
            ?: (this["isInterviewer"] as? JsonPrimitive)?.booleanOrNull
            ?: roles?.let { "interviewer" in it || "lead" in it }
            ?: fallback?.isInterviewer
            ?: true,
    )
}

/** First non-blank string among [keys]. */
private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

/** `roles: [..]` and/or `role: ".."`, lower-cased; null when the payload says nothing about roles. */
private fun JsonObject.roles(): Set<String>? {
    val list = (this["roles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
    val single = string("role")?.lowercase()
    if (list == null && single == null) return null
    return (list.orEmpty() + listOfNotNull(single)).toSet()
}

/** `["Kotlin"]`, `[{"name":"Kotlin"}]` or `"Kotlin, Go"`. */
private fun JsonObject.skills(): List<String> = when (val s = this["skills"]) {
    is JsonArray -> s.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull
            is JsonObject -> (item["name"] as? JsonPrimitive)?.contentOrNull ?: (item["label"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }
    is JsonPrimitive -> s.contentOrNull?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    else -> emptyList()
}

private fun titleFromRoles(roles: Set<String>?): String? = when {
    roles == null -> null
    "lead" in roles -> "Lead Engineer"
    else -> null
}
