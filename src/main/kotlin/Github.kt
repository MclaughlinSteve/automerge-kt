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
const val PULLS = "/pulls"
const val LABELS = "/labels"
const val ISSUES = "/issues"
const val MERGES = "/merges"
const val MERGE = "/merge"
const val COMMITS = "/commits"
const val CHECK_RUNS = "/check-runs"

val mapper = jacksonObjectMapper()
fun Any.toJsonString(): String = mapper.writeValueAsString(this)

class GithubService(config: GithubConfig) {
    private val baseUrl = config.baseUrl
    private val headers = config.headers
    private val label = config.label

    fun getOldestLabeledRequest(): Pull? {
        val url = baseUrl + PULLS
        val (_, _, result) = url.httpGet().header(headers).responseString()
        return when (result) {
            is Result.Failure -> {
                logFailure(result)
                null
            }
            is Result.Success -> {
                val pulls: List<Pull> = mapper.readValue(result.get())
                pulls.lastOrNull(::labeledRequest)
            }
        }
    }

    private fun labeledRequest(pull: Pull) = pull.labels.any { it.name == label }

    fun getReviewStatus(pull: Pull): MergeState {
        val url = baseUrl + PULLS + DELIMITER + pull.number
        val (_, _, result) = url.httpGet().header(headers).responseString()
        return when (result) {
            is Result.Failure -> {
                logFailure(result)
                MergeState.BAD
            }
            is Result.Success -> {
                val mergeStatus: MergeStatus = mapper.readValue(result.get())
                determineMergeState(mergeStatus)
            }
        }
    }

    private fun determineMergeState(mergeStatus: MergeStatus): MergeState {
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
            "unstable" -> MergeState.WAITING
            "unknown" -> MergeState.WAITING
            else -> MergeState.BAD
        }
    }

    fun squashMerge(pull: Pull) {
        val url = baseUrl + PULLS + DELIMITER + pull.number + MERGE
        val body = CommitBody(pull.title)
        val (request, _, result) = url.httpPut().body(body.toJsonString()).header(headers).responseString()
        when (result) {
            is Result.Failure -> {
                println("Failed to squash merge $request")
                removeLabel(pull)
                logFailure(result)
            }
            is Result.Success -> {
                println("Successfully squash merged ${pull.title}!")
                deleteBranch(pull)
            }
        }
    }

    private fun deleteBranch(pull: Pull) {
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
        val url = baseUrl + MERGES
        val body = UpdateBody(pull.base.ref, pull.head.ref)
        val (_, _, result) = url.httpPost().body(body.toJsonString()).header(headers).responseString()
        when (result) {
            is Result.Failure -> logFailure(result)
            is Result.Success -> {
                println("Successfully updating branch for PR: ${pull.title}")
            }
        }
    }

    /**
     * Note: This function is probably specific to the applications I'm using this on
     */
    fun assessStatusChecks(pull: Pull) {
        val url = baseUrl + COMMITS + DELIMITER + pull.head.sha + CHECK_RUNS
        val (_, _, result) = url.httpGet().header(headers).responseString()
        when (result) {
            is Result.Failure -> logFailure(result)
            is Result.Success -> {
                val statusCheck: Check = mapper.readValue(result.get())
                if (statusCheck.count == 0 || statusCheck.checkRuns.all { it.status == "completed" }) removeLabel(pull)
            }
        }
    }

    fun removeLabel(pull: Pull) {
        val url = baseUrl + ISSUES + DELIMITER + pull.number + LABELS + DELIMITER + label
        val (_, _, result) = url.httpDelete().header(headers).responseString()
        when (result) {
            is Result.Failure -> logFailure(result)
            is Result.Success -> println("Successfully removed label from PR: ${pull.title}")
        }
    }

    private fun logFailure(result: Result.Failure<String, FuelError>, message: String = "Something went wrong!") =
            println("""
                |======================
                | $message
                | Time: ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}
                |
                | Exception:
                | ${result.getException()}
                |======================
            """.trimIndent())
}
