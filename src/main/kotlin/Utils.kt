import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.FileInputStream

fun loadGithubConfig(): GithubConfig {
    val mapper = ObjectMapper(YAMLFactory())
    mapper.registerModule(KotlinModule())

    val config = FileInputStream("${System.getProperty("user.dir")}/src/main/resources/config.yml").use {
        mapper.readValue(it, ConfigDto::class.java)
    }

    val basic = config.basic
    val baseUrl = config.repo
    val headers: Map<String, String> = mapOf(
            "authorization" to basic,
            "accept" to "application/vnd.github.v3+json, application/vnd.github.antiope-preview+json",
            "content-type" to "application/json")
    return GithubConfig(baseUrl, headers)
}

data class GithubConfig(
        val baseUrl: String,
        val headers: Map<String, String>
)

data class ConfigDto(
        val basic: String,
        val repo: String
)
