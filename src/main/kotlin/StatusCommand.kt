import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.result.Result

class StatusCommand(private val config: GithubConfig, private val pull: Pull) {
    private val baseUrl = config.baseUrl
    private val headers = config.headers
    private val http = Http(headers)

    fun execute() {
        val required = getRequiredStatusAndChecks(pull) ?: return

        if (required.isEmpty()) {
            removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS)
            return
        }

        val statusCheck = getStatusOrChecks<Check, StatusCheck>(pull, SummaryType.CHECK_RUNS) ?: return
        val status = getStatusOrChecks<Status, StatusItem>(pull, SummaryType.STATUS) ?: return

        fun nameToStatusState(name: String) = name to when (name) {
            in statusCheck -> checkState(statusCheck.getValue(name))
            in status -> statusState(status.getValue(name))
            else -> StatusState.PENDING
        }

        val statusMap = required.map { nameToStatusState(it) }.toMap()

        if (statusMap.values.all { it == StatusState.SUCCESS }) {
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
    private fun getRequiredStatusAndChecks(pull: Pull): List<String>? {
        val url = "$baseUrl/$BRANCHES/${pull.base.ref}"
        val (_, _, result) = http.get(url)
        return when (result) {
            is Result.Failure -> {
                logFailure(result, "There was a problem getting the branch protections")
                null
            }
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
    private inline fun <reified StatusOrCheck, reified StatusResponse> getStatusOrChecks(
        pull: Pull,
        summaryType: SummaryType
    ): Map<String, StatusResponse>? {
        val url = "$baseUrl/$COMMITS/${pull.head.sha}/${summaryType.route}"
        val (_, _, result) = http.get(url)
        return when (result) {
            is Result.Failure -> {
                logFailure(result)
                null
            }
            is Result.Success -> {
                val statusOrCheck = mapper.readValue<StatusOrCheck>(result.get())
                when (statusOrCheck) {
                    is Status -> nameToStatus(statusOrCheck)
                    is Check -> nameToCheck(statusOrCheck)
                    else -> null
                }
            }
        }
    }

    private inline fun <reified StatusResponse> nameToCheck(check: Check): Map<String, StatusResponse> =
            check.checkRuns.map { it.name to it as StatusResponse }.toMap()

    private inline fun <reified StatusResponse> nameToStatus(status: Status): Map<String, StatusResponse> =
            status.statuses.map { it.context!! to it as StatusResponse }.toMap()

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
    private fun removeLabels(pull: Pull, reason: LabelRemovalReason) = LabelCommand(config, pull, reason).execute()
}
