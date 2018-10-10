import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.fuel.httpDelete
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.result.Result

const val DELIMITER = "/"
const val pullsEndpoint = "/pulls"
const val labelsEndpoint = "/labels"
const val issuesEndpoint = "/issues"

const val LABEL = "Test"

val config = loadConfig()
val basic = config.basic
val repo = config.repo
val headers = mapOf("authorization" to basic, "accept" to "application/vnd.github.v3+json")

val mapper = jacksonObjectMapper()

//TODO Re-implement using coroutines so we can hit multiple repositories at once
fun main(args: Array<String>) {
    val pull = getOldestLabeledRequest()
    //if (pull != null) deleteLabel(pull)
}

fun getOldestLabeledRequest(): Pull? {
    val url = repo + pullsEndpoint
    val (_, _, result) = url.httpGet().header(headers).responseString()
    when (result) {
        is Result.Failure -> logFailure(result)
        is Result.Success -> {
            val pulls: List<Pull> = mapper.readValue(result.get())

            return pulls.lastOrNull { p -> p.labels.isNotEmpty() && p.labels.any { l -> l.name == LABEL } }
        }
    }
    return null
}

private fun logFailure(result: Result.Failure<String, FuelError>) =
        println("======\n\n\n Something went wrong:\n ${result.getException()} \n\n\n======")

fun deleteLabel(pull: Pull) {
    val url = repo + issuesEndpoint + DELIMITER + pull.number + labelsEndpoint + DELIMITER + LABEL
    val (_, _, result) = url.httpDelete().header(headers).responseString()
    when (result) {
        is Result.Failure -> logFailure(result)
        is Result.Success -> println("Successfully removed label")
    }
}


@JsonIgnoreProperties(ignoreUnknown = true)
data class Pull(val id: Long, val number: Long, val title: String, val url: String, val labels: List<Label>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Label(val name: String)
