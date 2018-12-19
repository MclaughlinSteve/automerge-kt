import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.result.Result
import mu.KotlinLogging

const val BRANCHES = "branches"
const val PULLS = "pulls"
const val LABELS = "labels"
const val ISSUES = "issues"
const val MERGES = "merges"
const val MERGE = "merge"
const val COMMITS = "commits"
const val COMMENTS = "comments"

enum class SummaryType(val route: String) {
    STATUS("status"),
    CHECK_RUNS("check-runs")
}

val mapper = jacksonObjectMapper()

/**
 * Extension function to turn any object into a json string
 */
fun Any.toJsonString(): String = mapper.writeValueAsString(this)

/**
 * Service for performing operations related to github
 */
class GithubService(private val config: GithubConfig) {
    private val logger = KotlinLogging.logger {}
    private val baseUrl = config.baseUrl
    private val headers = config.headers
    private val label = config.label
    private val priority = config.priority
    private val mergeType = config.mergeType
    private val http = Http(headers)

    /**
     * Return the oldest pull request with the specified automerge label
     * @return the oldest labeled pull request or null if there are no labeled pull requests
     */
    fun getOldestLabeledRequest(): Pull? {
        val url = "$baseUrl/$PULLS"
        val (_, _, result) = http.get(url)
        return when (result) {
            is Result.Failure -> {
                logFailure(result)
                null
            }
            is Result.Success -> {
                val pulls: List<Pull> = mapper.readValue(result.get())
                pulls.lastOrNull(::priorityRequest) ?: pulls.lastOrNull(::labeledRequest)
            }
        }
    }

    /**
     * Determine if a given pull request has the specified priority label
     * @param pull the pull request to check
     * @return true if the pull request has the specified priority label
     */
    private fun priorityRequest(pull: Pull) = pull.labels.any { it.name == priority }

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
        val url = "$baseUrl/$PULLS/${pull.number}"
        val (_, _, result) = http.get(url)
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
     * Merge the specified pull request and delete the branch. If there is a problem merging,
     * the label will be removed from that pull request so the program can attempt to merge another
     * branch that has no issues
     *
     * This function is essentially just hitting the "Merge" button on github
     * and then deleting the branch afterward
     *
     * @param pull the pull request to be merged
     */
    fun merge(pull: Pull) {
        val url = "$baseUrl/$PULLS/${pull.number}/$MERGE"
        val body = CommitBody(pull.title, mergeType)
        val (request, _, result) = http.put(url, body)
        when (result) {
            is Result.Failure -> {
                logger.error { "Failed to merge $request" }
                removeLabels(pull)
                logFailure(result)
            }
            is Result.Success -> {
                logger.info { "Successfully merged ${pull.title}!" }
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
        val url = "$baseUrl/git/refs/heads/${pull.head.ref}"
        val (_, _, result) = http.delete(url)
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
        val url = "$baseUrl/$MERGES"
        val body = UpdateBody(pull.base.ref, pull.head.ref)
        val (_, _, result) = http.post(url, body)
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
    fun assessStatusAndChecks(pull: Pull) = StatusService(config).assessStatusAndChecks(pull)

    /**
     * Removes the Automerge and Priority labels from a pull request if they exist
     *
     * @param pull the pull request for which the label will be removed
     * @param reason some information about why the label is removed which will be commented on the PR
     */
    fun removeLabels(pull: Pull, reason: LabelRemovalReason = LabelRemovalReason.DEFAULT) =
            LabelService(config).removeLabels(pull, reason)
}
