import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * Load any configuration files and environment variables.
 *
 * @return a list of configuration objects that correspond to each repository specified in the config.yml file
 */
fun loadGithubConfig(): List<GithubConfig> {
    val mapper = ObjectMapper(YAMLFactory())
    mapper.registerModule(KotlinModule())

    val basic = System.getenv("GITHUB_USER_TOKEN")
        ?: throw IllegalArgumentException("Missing GITHUB_USER_TOKEN env variable")
    val label = System.getenv("AUTOMERGE_LABEL") ?: "Automerge"
    val priority = System.getenv("PRIORITY_LABEL") ?: "Priority Automerge"
    val mergeType = System.getenv("MERGE_TYPE") ?: "squash"
    val optionalStatuses = System.getenv("OPTIONAL_STATUSES").toBoolean()

    listOf("squash", "merge", "rebase").any { it == mergeType } ||
            throw IllegalArgumentException("Bad MERGE_TYPE env variable")

    val (repos) = Class.forName("ConfigKt").getResourceAsStream("config.yml").use {
        mapper.readValue(it, ConfigDto::class.java)
    }

    val headers: Map<String, String> = mapOf(
            "authorization" to "Bearer $basic",
            "accept" to "application/vnd.github.v3+json, application/vnd.github.antiope-preview+json",
            "content-type" to "application/json")

    return repos.map {
        GithubConfig(it, label, priority, mergeType, headers, optionalStatuses)
    }
}

/**
 * Configuration used by the services to make requests to github.
 *
 * @property baseUrl the base of the endpoints that will be called
 * @property label the label used for automerging
 * @property priority the label used for priority automerges
 * @property mergeType the type of merge that will be used
 * @property headers the http headers that will be included in the github requests
 */
data class GithubConfig(
    val baseUrl: String,
    val label: String,
    val priority: String,
    val mergeType: String,
    val headers: Map<String, String>,
    val optionalStatuses: Boolean
)

/**
 * Representation of data in the config.yml file.
 *
 * @property repos a list of repositories specified in the configuration
 */
data class ConfigDto(
    val repos: List<String>
)
