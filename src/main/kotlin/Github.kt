import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.fuel.httpDelete
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.fuel.httpPut
import com.github.kittinunf.result.Result
import mu.KotlinLogging
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
const val STATUS = "/status"
const val COMMENTS = "/comments"

const val LABEL_REMOVAL_DEFAULT = """
Uh oh! It looks like there was a problem trying to automerge this pull request.
Here are some possible reasons why the label may have been removed:
- There are outstanding reviews that need to be addressed before merging is possible
- There are merge conflicts with the base branch
- There are status checks failing

If none of those seem like the problem, try looking at the logs for more information.
"""

const val LABEL_REMOVAL_STATUS_CHECKS = """
Uh oh! It looks like there was a problem trying to automerge this pull request.

It seems likely that this is due to a failing status check. Take a look at your statuses and get
them passing before reapplying the automerge label.
"""

const val LABEL_REMOVAL_MERGE_CONFLICTS = """
Uh oh! It looks like there was a problem trying to automerge this pull request.

It seems likely that there are merge conflicts with the base branch that can't automatically be resolved.
Resolve any conflicts with the base branch before reapplying the automerge label.
"""

const val LABEL_REMOVAL_OUTSTANDING_REVIEWS = """
Uh oh! It looks like there was a problem trying to automerge this pull request.

It seems likely that there are some outstanding reviews that still need to be addressed before merging is possible.
Address any remaining reviews before reapplying the automerge label
"""

val mapper = jacksonObjectMapper()

/**
 * Extension function to turn any object into a json string
 */
fun Any.toJsonString(): String = mapper.writeValueAsString(this)

/**
 * Service for performing operations related to github
 */
class GithubService(config: GithubConfig) {
    private val logger = KotlinLogging.logger {}
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
            false -> return MergeState.UNMERGEABLE
        }
        logger.info { "The mergeable state before producing status is: ${mergeStatus.mergeableState}" }
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
                logger.error { "Failed to squash merge $request" }
                removeLabel(pull)
                logFailure(result)
            }
            is Result.Success -> {
                logger.info { "Successfully squash merged ${pull.title}!" }
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
                logger.info { "Successfully deleted ${pull.head.ref}" }
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
                logger.info { "Successfully updating branch for PR: ${pull.title}" }
            }
        }
    }

    /**
     * Checks to see whether there are any outstanding status requests (things like travis builds for example)
     *
     * If the merge status is "BLOCKED" and there are no outstanding status checks, something else is causing
     * the branch to be unable to be merged (Either merge conflicts, or requested changes) and the label will
     * be removed
     *
     * @param pull the pull request for which the statuses are being determined
     */
    fun assessStatusAndChecks(pull: Pull) {
        val statusCheck = getStatusChecks(pull) ?: return
        val status = getStatuses(pull) ?: return

        if (statusCheck.checkRuns.any { it.conclusion == "failure" || it.conclusion == "action_required" }) {
            removeLabel(pull, LabelRemovalReason.STATUS_CHECKS)
        } else if (status.state == "failure" || status.state == "error") {
            removeLabel(pull, LabelRemovalReason.STATUS_CHECKS)
        } else if (checksCompleted(statusCheck) && statusesCompleted(status)) {
            removeLabel(pull, LabelRemovalReason.OUTSTANDING_REVIEWS)
        }
    }

    /**
     * Get the status summary for a pull request
     *
     * @param pull the pull request to get the status summary for
     * @return the status summary for the pull request
     */
    private fun getStatuses(pull: Pull): Status? {
        val url = baseUrl + COMMITS + DELIMITER + pull.head.sha + STATUS
        val (_, _, result) = url.httpGet().header(headers).responseString()
        return when (result) {
            is Result.Failure -> {
                logFailure(result)
                null
            }
            is Result.Success -> mapper.readValue<Status>(result.get())
        }
    }

    /**
     * Get the "check-runs" summary for a pull request
     *
     * @param pull the pull request to get the "check-runs" for
     * @return the "check-runs" summary for the pull request
     */
    private fun getStatusChecks(pull: Pull): Check? {
        val url = baseUrl + COMMITS + DELIMITER + pull.head.sha + CHECK_RUNS
        val (_, _, result) = url.httpGet().header(headers).responseString()
        return when (result) {
            is Result.Failure -> {
                logFailure(result)
                null
            }
            is Result.Success -> mapper.readValue<Check>(result.get())
        }
    }

    /**
     * Check if all "check-runs" are completed.
     *
     * @param statusCheck the check-runs summary data from github
     * @return true if there are no check-runs or if all of the check-runs have completed
     */
    private fun checksCompleted(statusCheck: Check) =
            statusCheck.count == 0 || statusCheck.checkRuns.all { it.status == "completed" }

    /**
     * Check if all statuses are completed
     *
     * @param status the status summary data from github
     * @return true if there are no statuses or if all statuses have completed (a.k.a. are not pending)
     */
    private fun statusesCompleted(status: Status) = status.count == 0 || status.state != "pending"

    /**
     * Removes the specified automerge label from a pull request
     *
     * @param pull the pull request for which the label will be removed
     */
    fun removeLabel(pull: Pull, reason: LabelRemovalReason = LabelRemovalReason.DEFAULT) {
        val url = baseUrl + ISSUES + DELIMITER + pull.number + LABELS + DELIMITER + label
        val (_, _, result) = url.httpDelete().header(headers).responseString()
        when (result) {
            is Result.Failure -> logFailure(result)
            is Result.Success -> {
                logger.info { "Successfully removed label from PR: ${pull.title}" }
                handleLabelRemoval(pull, reason)
            }
        }
    }

    /**
     * Leave a comment on a PR with more information about why a label was removed
     * @param pull the pull request that the label was removed from
     * @param reason the reason that the label was removed from the pull request
     */
    private fun handleLabelRemoval(pull: Pull, reason: LabelRemovalReason) {
        when (reason) {
            LabelRemovalReason.DEFAULT -> postComment(pull, LABEL_REMOVAL_DEFAULT)
            LabelRemovalReason.STATUS_CHECKS -> postComment(pull, LABEL_REMOVAL_STATUS_CHECKS)
            LabelRemovalReason.MERGE_CONFLICTS -> postComment(pull, LABEL_REMOVAL_MERGE_CONFLICTS)
            LabelRemovalReason.OUTSTANDING_REVIEWS -> postComment(pull, LABEL_REMOVAL_OUTSTANDING_REVIEWS)
        }
    }

    /**
     * Post a comment on the specified PR with the given message
     * @param pull the pull request for which the comment will be made
     * @param message the message that will be commented
     */
    private fun postComment(pull: Pull, message: String) {
        val url = baseUrl + ISSUES + DELIMITER + pull.number + COMMENTS
        val commentBody = CommentBody(message)
        val (_, _, result) = url.httpPost().body(commentBody.toJsonString()).header(headers).responseString()
        when (result) {
            is Result.Failure -> logFailure(result, "Unable to post comment")
            is Result.Success -> logger.info { "Successfully commented on PR: ${pull.title} with message $message" }
        }
    }

    /**
     * Print out some formatted information about errors to the console for debugging purposes
     * @param result the failure information from the http request/response
     * @param message the failure message that will be displayed before the error - default: "Something went wrong"
     */
    private fun logFailure(result: Result.Failure<String, FuelError>, message: String = "Something went wrong!") =
            logger.error { """
                $message
                |======================
                | Time: ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}
                |
                | Exception:
                | ${result.getException()}
                |======================
            """.trimIndent() }
}
