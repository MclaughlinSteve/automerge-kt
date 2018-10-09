import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.fuel.httpDelete
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.result.Result

const val DELIMITER = "/"
const val pullsEndpoint = "/pulls"
const val labelsEndpoint = "/labels"

val config = loadConfig()
val basic = config.basic
val repo = config.repo
val headers = mapOf("authorization" to basic, "accept" to "application/vnd.github.v3+json")

val mapper = jacksonObjectMapper()

//TODO Re-implement using coroutines so we can hit multiple repositories at once
fun main(args: Array<String>) {
    val pull = getOldestLabeledRequest()
}

fun getOldestLabeledRequest(): Pull? {
    val (_, _, result) = (repo + pullsEndpoint).httpGet().header(headers).responseString()
    when (result) {
        is Result.Failure -> {
            val ex = result.getException()
            println("======\n\n\n Something went wrong:\n $ex \n\n\n======")
        }
        is Result.Success -> {
            val pulls: List<Pull> = mapper.readValue(result.get())

            return pulls.lastOrNull { p -> p.labels.isNotEmpty() && p.labels.any { l -> l.name == "Test" } }
        }
    }
    return null
}


@JsonIgnoreProperties(ignoreUnknown = true)
data class Pull(val id: Long, val number: Long, val title: String, val url: String, val labels: List<Label>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Label(val name: String)
