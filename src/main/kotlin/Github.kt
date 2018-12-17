import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.result.Result
import mu.KotlinLogging
import java.time.Instant
import java.time.format.DateTimeFormatter

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
class GithubService(config: GithubConfig) {
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
    fun assessStatusAndChecks(pull: Pull) {
        val required = getRequiredStatusAndChecks(pull)

        val statusCheck = getStatusOrChecks<Check>(pull, SummaryType.CHECK_RUNS) ?: return
        val status = getStatusOrChecks<Status>(pull, SummaryType.STATUS) ?: return

        val checks = statusCheck.checkRuns.filter { required.contains(it.name) }.map { it.name to checkState(it) }
        val statuses = status.statuses.filter { required.contains(it.context) }.map { it.context!! to statusState(it) }
        val knownStatuses = checks.union(statuses).toMap()
        val unknownStatuses = required.filter { !knownStatuses.keys.contains(it) }.map { it to StatusState.PENDING }
        val statusMap: Map<String, StatusState> = checks.union(statuses).union(unknownStatuses).toMap()

        if (statusMap.keys.isEmpty() || statusMap.values.all { it == StatusState.SUCCESS }) {
            removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS)
        } else if (statusMap.containsValue(StatusState.FAILURE)) {
            removeLabels(pull, LabelRemovalReason.STATUS_CHECKS)
        }
    }

    /**
     * Gets a list of the required status and checks for the branch being merged into
     *
     * @param pull the pull request being evaluated. It will contain the branch being merged into
     */
    private fun getRequiredStatusAndChecks(pull: Pull): List<String> {
        val url = "$baseUrl/$BRANCHES/${pull.base.ref}"
        val (_, _, result) = http.get(url)
        return when (result) {
            is Result.Failure -> emptyList()
            is Result.Success -> {
                val branchDetails = mapper.readValue<BranchDetails>(result.get())
                if (!branchDetails.protected) {
                    emptyList()
                } else {
                    branchDetails.protection.requiredStatusChecks.contexts
                }
            }
        }
    }

    /**
     * Get the status summary or "check-runs" summary for a pull request
     *
     * @param pull the pull request to get the status or "check-runs" for
     * @param summaryType the type that we're getting (Status or Check_runs)
     * @return the status summary or "check-runs" summary for the pull request
     */
    private inline fun <reified StatusOrCheck> getStatusOrChecks(pull: Pull, summaryType: SummaryType): StatusOrCheck? {
        val url = "$baseUrl/$COMMITS/${pull.head.sha}/${summaryType.route}"
        val (_, _, result) = http.get(url)
        return when (result) {
            is Result.Failure -> {
                logFailure(result)
                null
            }
            is Result.Success -> mapper.readValue<StatusOrCheck>(result.get())
        }
    }

    /**
     * Determine the state of the check
     *
     * @param item the check-run provided by github
     * @return the state of the check
     */
    private fun checkState(item: StatusCheck): StatusState {
        val failureStates = listOf("failure", "action_required", "cancelled", "timed_out")
        return when {
            failureStates.contains(item.conclusion) -> StatusState.FAILURE
            item.status == "completed" -> StatusState.SUCCESS
            else -> StatusState.PENDING
        }
    }

    /**
     * Determine the state of the status
     *
     * @param item the status provided by github
     * @return the state of the status
     */
    private fun statusState(item: StatusItem): StatusState {
        return when (item.state) {
            "failure" -> StatusState.FAILURE
            "error" -> StatusState.FAILURE
            "pending" -> StatusState.PENDING
            else -> StatusState.SUCCESS
        }
    }

    /**
     * Removes the Automerge and Priority labels from a pull request if they exist
     *
     * @param pull the pull request for which the label will be removed
     * @param reason some information about why the label is removed which will be commented on the PR
     */
    fun removeLabels(pull: Pull, reason: LabelRemovalReason = LabelRemovalReason.DEFAULT) {
        val url = "$baseUrl/$ISSUES/${pull.number}/$LABELS"
        val (_, _, result) = http.get(url)
        when (result) {
            is Result.Failure -> logFailure(result)
            is Result.Success -> {
                val labels: List<Label> = mapper.readValue(result.get())
                val labelsRemoved = listOf(label, priority).map { removeLabelIfExists(labels, pull, it) }
                if (labelsRemoved.any()) {
                    handleLabelRemoval(pull, reason)
                }
            }
        }
    }

    /**
     * Remove a specified label from a pull request if it exists
     *
     * @param labels the list of labels on the pull request
     * @param pull the pull request to remove any labels from
     * @param labelName the name of the label to remove if it exists
     * @return true if the label was successfully removed
     */
    private fun removeLabelIfExists(labels: List<Label>, pull: Pull, labelName: String) =
        labels.any { it.name == labelName } && removeLabel(pull, labelName)

    /**
     * Removes the specified label from a pull request
     *
     * @param pull the pull request for which the label will be removed
     * @param label the label that will be removed
     * @return true if removing the label was successful, otherwise false
     */
    private fun removeLabel(pull: Pull, label: String): Boolean {
        val url = "$baseUrl/$ISSUES/${pull.number}/$LABELS/$label"
        val (_, _, result) = http.delete(url)
        return when (result) {
            is Result.Failure -> {
                logFailure(result)
                false
            }
            is Result.Success -> {
                logger.info { "Successfully removed label $label from PR: ${pull.title}" }
                true
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
        val url = "$baseUrl/$ISSUES/${pull.number}/$COMMENTS"
        val commentBody = CommentBody(message)
        val (_, _, result) = http.post(url, commentBody)
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
