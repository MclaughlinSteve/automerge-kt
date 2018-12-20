import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.result.Result
import mu.KotlinLogging

/**
 * Service for performing actions related to github statuses.
 */
class LabelService(config: GithubConfig) {
    private val logger = KotlinLogging.logger {}
    private val baseUrl = config.baseUrl
    private val headers = config.headers
    private val label = config.label
    private val priority = config.priority
    private val http = Http(headers)

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
     *
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
     *
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
}
