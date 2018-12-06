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

/**
 * Extension function to turn any object into a json string
 */
fun Any.toJsonString(): String = mapper.writeValueAsString(this)

/**
 * Service for performing operations related to github
 */
class GithubService(config: GithubConfig) {
    private val baseUrl = config.baseUrl
    private val headers = config.headers
    private val label = config.label

    /**
     * Return the oldest pull request with the specified automerge label
     * @return the oldest labeled pull request or null if there are no labeled pull requests
     */
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

    /**
     * Determine if a given pull request has the specified automerge label
     * @param pull the pull request to check
     * @return true if the pull request has the specified automerge label
     */
    private fun labeledRequest(pull: Pull) = pull.labels.any { it.name == label }

    /**
     * Get the current status of a particular pull request
     * @param pull the pull request for which you are getting the status
     * @return the merge status of the pull request
     */
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

    /**
     * Determine the merge state for a pull request given its merge status object
     *
     * The status from this function is used to determine whether the function should merge,
     *  update the branch, remove the label and try a different branch, or wait for another update.
     *
     * @param mergeStatus the merge_status object sent from github
     * @return the merge status of the pull request
     */
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

    /**
     * Squash merge the specified pull request and delete the branch. If there is a problem merging,
     * the label will be removed from that pull request so the program can attempt to merge another
     * branch that has no issues
     *
     * This function is essentially just hitting the "Squash and merge" button on github
     * and then deleting the branch afterward
     *
     * @param pull the pull request to be merged
     */
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

    /**
     * Deletes the branch for a specified pull request. Used after the pull request has been merged
     *
     * This function is essentially just hitting the "Delete branch" button on github
     *
     * @param pull the pull request for the branch to be deleted
     */
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

    /**
     * Update a branch with changes from the base branch
     *
     * This is essentially hitting the "update branch" button on the github ui
     *
     * @param pull the pull request that contains the current branch and the branch being merged into
     */
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
     * TODO: This function is specific to the applications I'm using this on. Will be updated to be more general soon
     *
     * Checks to see whether there are any outstanding status requests (things like travis builds for example)
     *
     * If the merge status is "BLOCKED" and there are no outstanding status checks, something else is causing
     * the branch to be unable to be merged (Either merge conflicts, or requested changes) and the label will
     * be removed
     *
     * @param pull the pull request for which the statuses are being determined
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

    /**
     * Removes the specified automerge label from a pull request
     *
     * @param pull the pull request for which the label will be removed
     */
    fun removeLabel(pull: Pull) {
        val url = baseUrl + ISSUES + DELIMITER + pull.number + LABELS + DELIMITER + label
        val (_, _, result) = url.httpDelete().header(headers).responseString()
        when (result) {
            is Result.Failure -> logFailure(result)
            is Result.Success -> println("Successfully removed label from PR: ${pull.title}")
        }
    }

    /**
     * TODO: Use a logger instead of doing this stuff myself (Existing github issue)
     *
     * Print out some formatted information about errors to the console for debugging purposes
     * @param result the failure information from the http request/response
     * @param message the failure message that will be displayed before the error - default: "Something went wrong"
     */
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
