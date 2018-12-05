import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.FileInputStream

fun loadGithubConfig(): List<GithubConfig> {
    val mapper = ObjectMapper(YAMLFactory())
    mapper.registerModule(KotlinModule())

    val basic = System.getenv("GITHUB_USER_TOKEN") ?: throw Exception("Missing GITHUB_USER_TOKEN env variable")
    val label = System.getenv("AUTOMERGE_LABEL") ?: "Automerge"

    val (repos) = FileInputStream("${System.getProperty("user.dir")}/src/main/resources/config.yml").use {
        mapper.readValue(it, ConfigDto::class.java)
    }

    val headers: Map<String, String> = mapOf(
            "authorization" to "Bearer $basic",
            "accept" to "application/vnd.github.v3+json, application/vnd.github.antiope-preview+json",
            "content-type" to "application/json")

    return repos.map {
        GithubConfig(it, label, headers)
    }
}

data class GithubConfig(
    val baseUrl: String,
    val label: String,
    val headers: Map<String, String>
)

data class ConfigDto(
    val repos: List<String>
)
