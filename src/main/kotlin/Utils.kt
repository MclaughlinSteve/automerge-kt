import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.FileInputStream

fun loadConfig(): ConfigDto {
    val mapper = ObjectMapper(YAMLFactory())
    mapper.registerModule(KotlinModule())

    return FileInputStream("${System.getProperty("user.dir")}/src/main/resources/config.yml").use {
        mapper.readValue(it, ConfigDto::class.java)
    }
}

data class ConfigDto(val basic: String, val repo: String)
