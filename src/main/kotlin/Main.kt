import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

val githubConfig: List<GithubConfig> = loadGithubConfig()
const val INTERVAL: Long = 60_000

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

private fun executeAutomerge(service: GithubService) {
    val pull = service.getOldestLabeledRequest()
    val reviewStatus: MergeState? = pull?.let { service.getReviewStatus(pull) }

    reviewStatus?.let {
        println("Status is $reviewStatus")
        when (reviewStatus) {
            MergeState.CLEAN -> service.squashMerge(pull)
            MergeState.BEHIND -> service.updateBranch(pull)
            MergeState.BLOCKED -> service.assessStatusChecks(pull)
            MergeState.BAD -> service.removeLabel(pull)
            MergeState.WAITING -> Unit // Do nothing
        }
    }
}
