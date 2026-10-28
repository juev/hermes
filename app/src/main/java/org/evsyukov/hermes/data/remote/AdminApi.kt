package org.evsyukov.hermes.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A field of `config.yaml` as `/api/config/schema` describes it. */
data class ConfigField(
    val path: String,
    val type: String,
    val description: String,
    val category: String,
    val options: List<String>,
)

data class ConfigSchema(val fields: List<ConfigField>, val categoryOrder: List<String>)

data class LogQuery(val file: String = "agent", val lines: Int = 200, val level: String = "ALL", val component: String? = null, val search: String? = null)

/** A background command the dashboard started (`hermes gateway restart` and the like). */
data class ActionStatus(val name: String, val running: Boolean, val exitCode: Int?, val lines: List<String>)

/**
 * The management part of the dashboard: status, settings, secrets, models, cron, skills, tools,
 * logs and usage. Answers stay JSON where the screens only show them; the rest is parsed here.
 */
class AdminApi(private val http: HermesHttp) {
    suspend fun status(): JsonObject = http.getJson("api/status").jsonObject

    suspend fun systemStats(): JsonObject = http.getJson("api/system/stats").jsonObject

    suspend fun config(): JsonObject = http.getJson("api/config").jsonObject

    suspend fun schema(): ConfigSchema {
        val root = http.getJson("api/config/schema").jsonObject
        val fields = root["fields"]?.jsonObject.orEmpty().map { (path, value) ->
            val field = value.jsonObject
            ConfigField(
                path = path,
                type = field.string("type") ?: "string",
                description = field.string("description") ?: path,
                category = field.string("category") ?: path.substringBefore('.'),
                options = (field["options"] as? JsonArray)?.mapNotNull { option ->
                    when (option) {
                        is JsonPrimitive -> option.content
                        is JsonObject -> option.string("value") ?: option.string("id")
                        else -> null
                    }
                }.orEmpty(),
            )
        }
        val order = (root["category_order"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
        return ConfigSchema(fields, order)
    }

    /** Saves one key: the dashboard deep-merges the partial config into `config.yaml`. */
    suspend fun setConfig(path: String, value: JsonElement) {
        http.putJson("api/config", buildJsonObject { put("config", nest(path.split('.'), value)) })
    }

    suspend fun rawConfig(): String = http.getJson("api/config/raw").jsonObject.string("yaml").orEmpty()

    suspend fun saveRawConfig(yaml: String) {
        http.putJson("api/config/raw", buildJsonObject { put("yaml_text", yaml) })
    }

    suspend fun env(): JsonObject = http.getJson("api/env").jsonObject

    suspend fun setEnv(key: String, value: String) {
        http.putJson("api/env", buildJsonObject { put("key", key); put("value", value) })
    }

    suspend fun deleteEnv(key: String) {
        http.delete("api/env", buildJsonObject { put("key", key) })
    }

    suspend fun revealEnv(key: String): String =
        http.postJson("api/env/reveal", buildJsonObject { put("key", key) }).jsonObject.string("value").orEmpty()

    suspend fun modelInfo(): JsonObject = http.getJson("api/model/info").jsonObject

    suspend fun modelOptions(refresh: Boolean = false): JsonObject =
        http.getJson("api/model/options", mapOf("refresh" to refresh.takeIf { it })).jsonObject

    /** Switches the main model of new chats; an expensive one first answers with `confirm_required`. */
    suspend fun setModel(provider: String, model: String, confirmExpensive: Boolean = false): JsonObject =
        http.postJson(
            "api/model/set",
            buildJsonObject {
                put("scope", "main")
                put("provider", provider)
                put("model", model)
                if (confirmExpensive) put("confirm_expensive_model", true)
            },
        ).jsonObject

    suspend fun gateway(action: String, interrupt: Boolean = false): JsonObject {
        val path = if (action == "stop" && interrupt) "api/gateway/stop?interrupt=true" else "api/gateway/$action"
        return http.postJson(path).jsonObject
    }

    suspend fun action(name: String, lines: Int = 200): ActionStatus {
        val obj = http.getJson("api/actions/$name/status", mapOf("lines" to lines)).jsonObject
        return ActionStatus(
            name = name,
            running = obj.bool("running") ?: false,
            exitCode = obj.long("exit_code")?.toInt(),
            lines = (obj["lines"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty(),
        )
    }

    suspend fun updateCheck(force: Boolean = false): JsonObject =
        http.getJson("api/hermes/update/check", mapOf("force" to force.takeIf { it })).jsonObject

    suspend fun update(): JsonObject = http.postJson("api/hermes/update").jsonObject

    suspend fun logs(query: LogQuery): List<String> {
        val obj = http.getJson(
            "api/logs",
            mapOf(
                "file" to query.file,
                "lines" to query.lines,
                "level" to query.level,
                "component" to query.component,
                "search" to query.search?.takeIf { it.isNotBlank() },
            ),
        ).jsonObject
        return (obj["lines"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
    }

    suspend fun usage(days: Int): JsonObject = http.getJson("api/analytics/usage", mapOf("days" to days)).jsonObject

    suspend fun cronJobs(): List<JsonObject> = arrayOf(http.getJson("api/cron/jobs"), "jobs")

    suspend fun cronRuns(id: String): List<JsonObject> =
        (http.getJson("api/cron/jobs/$id/runs", mapOf("limit" to 30)).jsonObject["runs"] as? JsonArray)?.map { it.jsonObject }.orEmpty()

    suspend fun cronCommand(id: String, command: String): JsonElement = http.postJson("api/cron/jobs/$id/$command")

    suspend fun deleteCron(id: String) {
        http.delete("api/cron/jobs/$id")
    }

    suspend fun createCron(schedule: String, prompt: String, name: String, deliver: String): JsonObject =
        http.postJson(
            "api/cron/jobs",
            buildJsonObject {
                put("schedule", schedule)
                put("prompt", prompt)
                if (name.isNotBlank()) put("name", name)
                put("deliver", deliver)
            },
        ).jsonObject

    suspend fun updateCron(id: String, updates: JsonObject): JsonObject =
        http.putJson("api/cron/jobs/$id", buildJsonObject { put("updates", updates) }).jsonObject

    suspend fun cronTargets(): List<JsonObject> =
        (http.getJson("api/cron/delivery-targets").jsonObject["targets"] as? JsonArray)?.map { it.jsonObject }.orEmpty()

    suspend fun skills(): List<JsonObject> = arrayOf(http.getJson("api/skills"), "skills")

    suspend fun toggleSkill(name: String, enabled: Boolean) {
        http.putJson("api/skills/toggle", buildJsonObject { put("name", name); put("enabled", enabled) })
    }

    suspend fun skillContent(name: String): String = http.getJson("api/skills/content", mapOf("name" to name)).jsonObject.string("content").orEmpty()

    suspend fun toolsets(): List<JsonObject> = arrayOf(http.getJson("api/tools/toolsets"), "toolsets")

    suspend fun toggleToolset(name: String, enabled: Boolean) {
        http.putJson("api/tools/toolsets/$name", buildJsonObject { put("enabled", enabled) })
    }

    suspend fun mcpServers(): List<JsonObject> = arrayOf(http.getJson("api/mcp/servers"), "servers")

    suspend fun toggleMcp(name: String, enabled: Boolean) {
        http.putJson("api/mcp/servers/$name/enabled", buildJsonObject { put("enabled", enabled) })
    }

    suspend fun testMcp(name: String): JsonObject = http.postJson("api/mcp/servers/$name/test").jsonObject

    suspend fun platforms(): List<JsonObject> = arrayOf(http.getJson("api/messaging/platforms"), "platforms")

    suspend fun testPlatform(id: String): JsonObject = http.postJson("api/messaging/platforms/$id/test").jsonObject

    suspend fun memory(): JsonObject = http.getJson("api/memory").jsonObject

    /** A list answer comes bare or wrapped in an object under [key], depending on the route. */
    private fun arrayOf(root: JsonElement, key: String): List<JsonObject> = when (root) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> (root[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        else -> emptyList()
    }

    companion object {
        /** `a.b.c` and a value become `{"a": {"b": {"c": value}}}`. */
        fun nest(path: List<String>, value: JsonElement): JsonObject =
            if (path.size == 1) JsonObject(mapOf(path[0] to value)) else JsonObject(mapOf(path[0] to nest(path.drop(1), value)))

        /** The value at a dotted path of the config, or [JsonNull] when it is absent. */
        fun valueAt(config: JsonObject, path: String): JsonElement {
            var node: JsonElement = config
            for (part in path.split('.')) {
                node = (node as? JsonObject)?.get(part) ?: return JsonNull
            }
            return node
        }
    }
}
