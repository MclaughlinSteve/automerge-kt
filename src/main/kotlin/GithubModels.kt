import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

enum class MergeState {
    CLEAN,
    BEHIND,
    BLOCKED,
    WAITING,
    BAD
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class StatusCheck(
        val status: String,
        val conclusion: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Check(
        @JsonProperty("total_count") val count: Int,
        @JsonProperty("check_runs") val checkRuns: List<StatusCheck>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MergeStatus(
        val number: Long,
        val mergeable: Boolean?,
        @JsonProperty("mergeable_state") val mergeableState: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Branch(
        val ref: String,
        val sha: String
)

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

@JsonIgnoreProperties(ignoreUnknown = true)
data class Label(val name: String)
