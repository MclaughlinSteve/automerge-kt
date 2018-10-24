import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper


const val DELIMITER = "/"
const val pullsEndpoint = "/pulls"
const val labelsEndpoint = "/labels"
const val issuesEndpoint = "/issues"
const val mergesEndpoint = "/merges"
const val mergeEndpoint = "/merge"
const val commitsEndpoint = "/commits"
const val checkRunsEndpoint = "/check-runs"

const val LABEL = "Automerge"

val config = loadConfig()
val basic = config.basic
val baseUrl = config.repo
val headers = mapOf(
        "authorization" to basic,
        "accept" to "application/vnd.github.v3+json, application/vnd.github.antiope-preview+json",
        "content-type" to "application/json")

val mapper = jacksonObjectMapper()

//TODO Re-implement using coroutines so we can hit multiple repositories at once
fun main(args: Array<String>) {
    while (true) {
        val pull = getOldestLabeledRequest()
        val reviewStatus: MergeState? = pull?.let { getReviewStatus(pull) }

        reviewStatus?.let {
            println("Status is $reviewStatus")
            when (reviewStatus) {
                MergeState.CLEAN -> squashMerge(pull)
                MergeState.BEHIND -> updateBranch(pull)
                MergeState.BLOCKED -> assessStatusChecks(pull)
                MergeState.BAD -> removeLabel(pull)
                MergeState.WAITING -> Unit // Do nothing
            }
        }
        Thread.sleep(60_000)
    }
}

