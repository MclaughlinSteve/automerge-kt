import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.result.Result

/**
 * Service for performing actions related to github statuses.
 */
class StatusService(private val config: GithubConfig) {
    private val baseUrl = config.baseUrl
    private val headers = config.headers
    private val http = Http(headers)

    /**
     * Checks to see whether there are any outstanding status requests (things like travis builds for example).
     *
     * If the merge status is "BLOCKED" and there are no outstanding status checks, something else is causing
     * the branch to be unable to be merged (Either merge conflicts, or requested changes) and the label will
     * be removed
     *
     * @param pull the pull request for which the statuses are being determined
     */
    fun assessStatusAndChecks(pull: Pull): Boolean {
        getRequiredStatusAndChecks(pull)?.let {
            return if (it.isEmpty()) {
                removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS)
            } else {
                val statusCheck = getStatusOrChecks<Check, StatusCheck>(pull, SummaryType.CHECK_RUNS)
                val status = getStatusOrChecks<Status, StatusItem>(pull, SummaryType.STATUS)
                when {
                    statusCheck == null -> false
                    status == null -> false
                    else -> handleCompletedStatuses(pull, it, statusCheck, status)
                }
            }
        }
    }

    /**
     * Remove the label if there are any failing statuses or checks. If this function is called,
     * it is likely an optional status that will be failing.
     *
     * @param pull the pull request to assess
     */
    fun removeLabelOrWait(pull: Pull): Boolean {
        val checks = getStatusOrChecks<Check, StatusCheck>(pull, SummaryType.CHECK_RUNS)
        val status = getStatusOrChecks<Status, StatusItem>(pull, SummaryType.STATUS)
        return when {
            checks == null -> false
            status == null -> false
            else ->
                return if (status.values.map { statusState(it) }.plus(checks.values.map { checkState(it) }).any(::failure)) {
                    removeLabels(pull, LabelRemovalReason.OPTIONAL_CHECKS)
                } else {
                    false
                }
        }
    }

    private fun failure(status: StatusState) = status == StatusState.FAILURE

    private fun handleCompletedStatuses(
        pull: Pull,
        required: List<String>,
        statusCheck: Map<String, StatusCheck>,
        status: Map<String, StatusItem>
    ): Boolean {
        val statusMap = required.associateWith { nameToStatusState(it, statusCheck, status) }

        return when {
            statusMap.values.all { it == StatusState.SUCCESS } ->
                removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS)
            statusMap.containsValue(StatusState.FAILURE) -> removeLabels(pull, LabelRemovalReason.STATUS_CHECKS)
            else -> false
        }
    }

    private fun nameToStatusState(
        name: String,
        statusCheck: Map<String, StatusCheck>,
        status: Map<String, StatusItem>
    ): StatusState {
        return when (name) {
            in statusCheck -> checkState(statusCheck.getValue(name))
            in status -> statusState(status.getValue(name))
            else -> StatusState.PENDING
        }
    }

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
                nameToStatusInfo(statusOrCheck)
            }
        }
    }

    private inline fun <reified StatusOrCheck, reified StatusResponse> nameToStatusInfo(
        statusOrCheck: StatusOrCheck
    ): Map<String, StatusResponse>? =
        when (statusOrCheck) {
            is Status -> statusOrCheck.statuses.associateBy({ it.context }, { it as StatusResponse })
            is Check -> statusOrCheck.checkRuns.associateBy({ it.name }, { it as StatusResponse })
            else -> null
        }

    private fun checkState(item: StatusCheck): StatusState {
        val failureStates = listOf("failure", "action_required", "cancelled", "timed_out")
        return when {
            failureStates.contains(item.conclusion) -> StatusState.FAILURE
            item.status == "completed" -> StatusState.SUCCESS
            else -> StatusState.PENDING
        }
    }

    private fun statusState(item: StatusItem): StatusState {
        return when (item.state) {
            "failure" -> StatusState.FAILURE
            "error" -> StatusState.FAILURE
            "pending" -> StatusState.PENDING
            else -> StatusState.SUCCESS
        }
    }

    private fun removeLabels(pull: Pull, reason: LabelRemovalReason) = LabelService(config).removeLabels(pull, reason)
}
