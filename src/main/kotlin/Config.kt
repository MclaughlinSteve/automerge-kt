import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.FileInputStream

fun loadGithubConfig(): List<GithubConfig> {
    val mapper = ObjectMapper(YAMLFactory())
    mapper.registerModule(KotlinModule())

    val (basic, label, repos) = FileInputStream("${System.getProperty("user.dir")}/src/main/resources/config.yml").use {
        mapper.readValue(it, ConfigDto::class.java)
    }

    val headers: Map<String, String> = mapOf(
            "authorization" to basic,
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
        val basic: String,
        val label: String,
        val repos: List<String>
)
