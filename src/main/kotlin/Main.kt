import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.InputStream

val githubConfig: List<GithubConfig> = loadGithubConfig()
const val INTERVAL: Long = 60_000

private val logger = KotlinLogging.logger {}

/**
 * Main function used for running the application locally.
 */
fun main() {
    val services = githubConfig.map { GithubService(it) }
    while (true) {
        launchAutomerger(services)
        Thread.sleep(INTERVAL)
    }
}

/**
 * Used to run this application on AWS Lambda.
 *
 * @param input the input stream sent by aws
 */
fun handleLambda(input: InputStream) {
    val services = githubConfig.map { GithubService(it) }
    launchAutomerger(services)
}

private fun launchAutomerger(services: List<GithubService>) {
    runBlocking {
        services.forEach {
            launch {
                executeAutomerge(it)
            }
        }
    }
}

private fun executeAutomerge(service: GithubService) {
    val pull = service.getOldestLabeledRequest()
    val reviewStatus: MergeState? = pull?.let { service.getReviewStatus(pull) }

    reviewStatus?.let {
        logger.info { "Status is $reviewStatus" }
        when (reviewStatus) {
            MergeState.CLEAN -> service.merge(pull)
            MergeState.BEHIND -> service.updateBranch(pull)
            MergeState.BLOCKED -> service.assessStatusAndChecks(pull)
            MergeState.UNMERGEABLE -> service.removeLabels(pull, LabelRemovalReason.MERGE_CONFLICTS)
            MergeState.BAD -> service.removeLabels(pull)
            MergeState.UNSTABLE -> service.handleUnstableStatus(pull)
            MergeState.WAITING -> Unit // Do nothing
        }
    }
}
