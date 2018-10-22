import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.fuel.httpDelete
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.fuel.httpPut
import com.github.kittinunf.result.Result
import java.time.Instant
import java.time.format.DateTimeFormatter


const val DELIMITER = "/"
const val pullsEndpoint = "/pulls"
const val labelsEndpoint = "/labels"
const val issuesEndpoint = "/issues"
const val mergesEndpoint = "/merges"
const val mergeEndpoint = "/merge"
const val commitsEndpoint = "/commits"
const val checkRunsEndpoint = "/check-runs"

const val LABEL = "Automerge"

val config = loadConfig()
val basic = config.basic
val baseUrl = config.repo
val headers = mapOf(
        "authorization" to basic,
        "accept" to "application/vnd.github.v3+json, application/vnd.github.antiope-preview+json",
        "content-type" to "application/json")

val mapper = jacksonObjectMapper()

//TODO Re-implement using coroutines so we can hit multiple repositories at once
fun main(args: Array<String>) {
    while (true) {
        val pull = getOldestLabeledRequest()
        val reviewStatus: MergeState? = pull?.let { getReviewStatus(pull) }

        reviewStatus?.let {
            println("Status is $reviewStatus")
            when (reviewStatus) {
                MergeState.CLEAN -> squashMerge(pull)
                MergeState.BEHIND -> updateBranch(pull)
                MergeState.BLOCKED -> assessStatusChecks(pull)
                MergeState.BAD -> removeLabel(pull)
                MergeState.WAITING -> println("Waiting...")
            }
        }
        Thread.sleep(60_000)
    }
}

fun getOldestLabeledRequest(): Pull? {
    val url = baseUrl + pullsEndpoint
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

fun squashMerge(pull: Pull) {
    val url = baseUrl + pullsEndpoint + DELIMITER + pull.number + mergeEndpoint
    val body = "{ \"commit_title\" : \"${pull.title}\", \"merge_method\" : \"squash\" }"
    val (request, _, result) = url.httpPut().body(body).header(headers).responseString()
    when (result) {
        is Result.Failure -> {
            println("Failed to squash merge $request")
            removeLabel(pull)
            logFailure(result)
        }
        is Result.Success -> {
            println("Successfully squash merged ${pull.title} to master")
            deleteBranch(pull)
        }
    }
}

fun deleteBranch(pull: Pull) {
    val url = baseUrl + "/git/refs/heads/" + pull.head.ref
    val (_, _, result) = url.httpDelete().header(headers).responseString()
    when (result) {
        is Result.Failure -> logFailure(result)
        is Result.Success -> {
            println("Successfully deleted ${pull.head.ref}")
        }
    }
}

fun updateBranch(pull: Pull) {
    val url = baseUrl + mergesEndpoint
    val body = "{ \"head\" : \"${pull.base.ref}\", \"base\" : \"${pull.head.ref}\", \"commit_message\" : \"Merge master into branch\" }"
    val (_, _, result) = url.httpPost().body(body).header(headers).responseString()
    when (result) {
        is Result.Failure -> logFailure(result)
        is Result.Success -> {
            println("Successfully updating branch for PR: ${pull.title}")
        }
    }
}

fun getReviewStatus(pull: Pull): MergeState {
    val url = baseUrl + pullsEndpoint + DELIMITER + pull.number
    val (_, _, result) = url.httpGet().header(headers).responseString()
    when (result) {
        is Result.Failure -> logFailure(result)
        is Result.Success -> {
            val mergeStatus: MergeStatus = mapper.readValue(result.get())
            return determineMergeState(mergeStatus)
        }
    }
    return MergeState.BAD
}

fun assessStatusChecks(pull: Pull) {
    val url = baseUrl + commitsEndpoint + DELIMITER + pull.head.sha + checkRunsEndpoint
    val (_, _, result) = url.httpGet().header(headers).responseString()
    when (result) {
        is Result.Failure -> logFailure(result)
        is Result.Success -> {
            val statusCheck: Check = mapper.readValue(result.get())
            if (statusCheck.count == 0 || statusCheck.checkRuns.all { it.status == "completed" }) removeLabel(pull)
        }
    }
}

fun determineMergeState(mergeStatus: MergeStatus): MergeState {
    when (mergeStatus.mergeable) {
        null -> return MergeState.WAITING
        false -> return MergeState.BAD
    }
    println("The mergeable state before producing status is: ${mergeStatus.mergeableState}")
    return when (mergeStatus.mergeableState) {
        "behind" -> MergeState.BEHIND
        "clean" -> MergeState.CLEAN
        "blocked" -> MergeState.BLOCKED
        "has_hooks" -> MergeState.WAITING
        "unstable" -> MergeState.WAITING // TODO Watch out for things getting stuck in these states
        "unknown" -> MergeState.WAITING
        else -> MergeState.BAD
    }
}

private fun logFailure(result: Result.Failure<String, FuelError>) =
        println("======\n\n\n ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}Something went wrong:\n ${result.getException()} \n\n\n======")

fun removeLabel(pull: Pull) {
    val url = baseUrl + issuesEndpoint + DELIMITER + pull.number + labelsEndpoint + DELIMITER + LABEL
    val (_, _, result) = url.httpDelete().header(headers).responseString()
    when (result) {
        is Result.Failure -> logFailure(result)
        is Result.Success -> println("Successfully removed label from PR: ${pull.title}")
    }
}

enum class MergeState {
    CLEAN,
    BEHIND,
    BLOCKED,
    WAITING,
    BAD
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class StatusCheck(val status: String, val conclusion: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Check(@JsonProperty("total_count") val count: Int, @JsonProperty("check_runs") val checkRuns: List<StatusCheck>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MergeStatus(val number: Long, val mergeable: Boolean?, @JsonProperty("mergeable_state") val mergeableState: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Branch(val ref: String, val sha: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Pull(val id: Long, val number: Long, val title: String, val url: String, val labels: List<Label>, val base: Branch, val head: Branch)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Label(val name: String)
