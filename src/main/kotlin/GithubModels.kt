import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Enumerations used to represent states that a github pull request can be in
 */
enum class MergeState {
    CLEAN,
    BEHIND,
    BLOCKED,
    WAITING,
    UNMERGEABLE,
    BAD
}

/**
 * Enumerations used to provide more information about why a pull request was unable to be merged
 *
 */
enum class LabelRemovalReason {
    DEFAULT,
    STATUS_CHECKS,
    MERGE_CONFLICTS,
    OUTSTANDING_REVIEWS
}

/**
 * Data class used to represent information about github's status checks
 * @property status information about whether a check has completed or not
 * @property conclusion information about whether a completed check was successful or not
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StatusCheck(
    val status: String,
    val conclusion: String?
)

/**
 * Interface for statuses and check-runs used for better type bounding
 */
interface StatusOrCheck

/**
 * Data class used to represent information about github's checks (e.g. travis status checks)
 * @property count the number of checks for a given pull request
 * @property checkRuns a list of status checks that have or are being run
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Check(
    @JsonProperty("total_count") val count: Int,
    @JsonProperty("check_runs") val checkRuns: List<StatusCheck>
) : StatusOrCheck

/**
 * Data class used to represent information about a github status
 * (Note: description and context may not need to be nullable)
 * @property state the state of the status ("success", "pending", "failure", or "error"
 * @property description A short description of the status
 * @property context A string label to differentiate this status from the status of other systems
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StatusItem(
    val state: String,
    val description: String?,
    val context: String?
)

/**
 * Data class used to represent information about a github status summary. It has a roll-up of information
 * about all of the statuses for a particular pull request
 * @property state the state of the statuses
 * @property count the number of statuses for a given pull request
 * @property statuses a list of statuses that have or are being run
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Status(
    val state: String,
    @JsonProperty("total_count") val count: Int,
    val statuses: List<StatusItem>
) : StatusOrCheck

/**
 * Data class used to represent relevant information about a pull request's merge status
 * @property number the pull request number
 * @property mergeable a field that gives some information about whether a PR is or is not mergeable
 * @property mergeableState a field that gives more detail about whether a PR can merge or can't merge and why
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MergeStatus(
    val number: Long,
    val mergeable: Boolean?,
    @JsonProperty("mergeable_state") val mergeableState: String
)

/**
 * Data class used to represent relevant information about github's branches
 * @property ref a name reference for the branch (e.g. 'master')
 * @property sha the commit sha for the specified branch
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Branch(
    val ref: String,
    val sha: String
)

/**
 * Data class used to represent relevant information about github's pull request
 * @property id the id of the pull request
 * @property number the pull request number
 * @property title the title of the pull request
 * @property url the url for the pull request
 * @property labels a list of labels associated with the pull request
 * @property base the branch the pull request is being merged into
 * @property head the branch that the pull request originates from
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Pull(
    val id: Long,
    val number: Long,
    val title: String,
    val url: String,
    val labels: List<Label>,
    val base: Branch,
    val head: Branch
)

/**
 * Data class used to represent github's Label
 * @property name the name of the label
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Label(val name: String)

/**
 * The body of the request used to perform a merge into the base branch
 * @property commitTitle the title of the commit that will be merged
 * @property mergeMethod the method of merging to be used
 */
data class CommitBody(
    @JsonProperty("commit_title") val commitTitle: String,
    @JsonProperty("merge_method") val mergeMethod: String
)

/**
 * The body of the request used to update a branch with changes from the base branch
 * @property head the branch with the changes
 * @property base the branch you are merging the changes into
 * @property commitMessage a message for the update commit - Default: "Update branch"
 */
data class UpdateBody(
    val head: String,
    val base: String,
    @JsonProperty("commit_message") val commitMessage: String = "Update branch"
)

/**
 * The body of the request used to make a comment on a PR
 * @property body the content of the comment message
 */
data class CommentBody(val body: String)
