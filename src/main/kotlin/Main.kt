const val LABEL = "Automerge" // TODO Move this to config

val githubConfig = loadGithubConfig()

//TODO Re-implement using coroutines so we can hit multiple repositories at once
fun main(args: Array<String>) {
    while (true) {
        val service = GithubService(githubConfig)
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
        Thread.sleep(60_000)
    }
}

