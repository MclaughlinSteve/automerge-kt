import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

val githubConfig: List<GithubConfig> = loadGithubConfig()
const val INTERVAL: Long = 60_000

private val logger = KotlinLogging.logger {}

fun main() {
    val services = githubConfig.map { GithubService(it) }
    while (true) {
        runBlocking {
            services.forEach {
                launch {
                    executeAutomerge(it)
                }
            }
        }
        Thread.sleep(INTERVAL)
    }
}

/**
 * Perform the automerge processing for the given service. Each service is specific to a github repository.
 * Multiple repositories can be run in parallel if they are specified in the config file.
 * This includes getting the oldest pull request with the label, determining whether it can be merged,
 * if it hasn't passed all required checks, if it has merge conflicts, if it has requested changes blocking it
 * from merging, if it needs to be updated, or if it's just waiting on status checks. Depending on the status,
 * it will perform the appropriate behavior.
 *
 * @param service the service configured for a specific repository
 */
private fun executeAutomerge(service: GithubService) {
    val pull = service.getOldestLabeledRequest()
    val reviewStatus: MergeState? = pull?.let { service.getReviewStatus(pull) }

    reviewStatus?.let {
        logger.info { "Status is $reviewStatus" }
        when (reviewStatus) {
            MergeState.CLEAN -> service.squashMerge(pull)
            MergeState.BEHIND -> service.updateBranch(pull)
            MergeState.BLOCKED -> service.assessStatusChecks(pull)
            MergeState.UNMERGEABLE -> service.removeLabel(pull, LabelRemovalReason.MERGE_CONFLICTS)
            MergeState.BAD -> service.removeLabel(pull)
            MergeState.WAITING -> Unit // Do nothing
        }
    }
}
