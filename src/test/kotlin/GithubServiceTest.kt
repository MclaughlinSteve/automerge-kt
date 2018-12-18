import com.github.kittinunf.fuel.core.Client
import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.Response
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.HashMap

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GithubServiceTest {
    private val headers = mapOf(
            "authorization" to "Bearer foo",
            "accept" to "application/vnd.github.v3+json, application/vnd.github.antiope-preview+json",
            "content-type" to "application/json")
    private val baseUrl = "http://foo.test/bar"
    private val config = GithubConfig(baseUrl, "Automerge", "Priority Automerge", "squash", headers)
    private val service = spyk(GithubService(config))
    private val client = mockk<Client>()

    @Nested
    inner class GetOldestLabeledRequest {
        @Test
        fun `Get the oldest labeled pull request`() {
            val oldestPull = generateSamplePull(1)
            val newestPull = generateSamplePull(2)
            mockRequest(200, "OK", listOf(newestPull, oldestPull))
            val pull = service.getOldestLabeledRequest()
            assertThat(pull).isEqualTo(oldestPull)
        }

        @Test
        fun `Get a pull request with the priority label`() {
            val oldestPull = generateSamplePull(1)
            val priorityPull = Pull(2, 2, "Test PR", "",
                    listOf(Label("Priority Automerge")), Branch("", ""), Branch("", ""))
            val newestPull = generateSamplePull(3)
            mockRequest(200, "OK", listOf(newestPull, priorityPull, oldestPull))
            val pull = service.getOldestLabeledRequest()
            assertThat(pull).isEqualTo(priorityPull)
        }

        @Test
        fun `Http error trying to get pull requests`() {
            mockRequest(400, "Bad Request")
            val pull = service.getOldestLabeledRequest()
            assertThat(pull).isEqualTo(null)
        }
    }

    @Nested
    inner class GetReviewStatus {
        @Test
        fun `Pull request is not mergeable returns bad state`() {
            mockRequest(200, "OK", MergeStatus(1, false, "unknown"))
            val status = service.getReviewStatus(generateSamplePull(1))
            assertThat(status).isEqualTo(MergeState.UNMERGEABLE)
        }

        @Test
        fun `Pull request mergeable not defined returns waiting state`() {
            mockRequest(200, "OK", MergeStatus(1, null, "unknown"))
            val status = service.getReviewStatus(generateSamplePull(1))
            assertThat(status).isEqualTo(MergeState.WAITING)
        }

        @Test
        fun `Pull request with behind status returns behind state`() {
            mockRequest(200, "OK", MergeStatus(1, true, "behind"))
            val status = service.getReviewStatus(generateSamplePull(1))
            assertThat(status).isEqualTo(MergeState.BEHIND)
        }

        @Test
        fun `Pull request with clean status returns clean state`() {
            mockRequest(200, "OK", MergeStatus(1, true, "clean"))
            val status = service.getReviewStatus(generateSamplePull(1))
            assertThat(status).isEqualTo(MergeState.CLEAN)
        }

        @Test
        fun `Pull request with blocked status returns blocked state`() {
            mockRequest(200, "OK", MergeStatus(1, true, "blocked"))
            val status = service.getReviewStatus(generateSamplePull(1))
            assertThat(status).isEqualTo(MergeState.BLOCKED)
        }

        @Test
        fun `Pull request with has_hooks status returns waiting state`() {
            mockRequest(200, "OK", MergeStatus(1, true, "has_hooks"))
            val status = service.getReviewStatus(generateSamplePull(1))
            assertThat(status).isEqualTo(MergeState.WAITING)
        }

        @Test
        fun `Pull request with unstable status returns waiting state`() {
            mockRequest(200, "OK", MergeStatus(1, true, "unstable"))
            val status = service.getReviewStatus(generateSamplePull(1))
            assertThat(status).isEqualTo(MergeState.WAITING)
        }

        @Test
        fun `Pull request with unknown status returns waiting state`() {
            mockRequest(200, "OK", MergeStatus(1, true, "unknown"))
            val status = service.getReviewStatus(generateSamplePull(1))
            assertThat(status).isEqualTo(MergeState.WAITING)
        }

        @Test
        fun `Pull request with unexpected status returns bad state`() {
            mockRequest(200, "OK", MergeStatus(1, true, "foo"))
            val status = service.getReviewStatus(generateSamplePull(1))
            assertThat(status).isEqualTo(MergeState.BAD)
        }

        @Test
        fun `Http error trying to get the review status returns bad state`() {
            mockRequest(400, "Bad Request")
            val status = service.getReviewStatus(generateSamplePull(1))
            assertThat(status).isEqualTo(MergeState.BAD)
        }
    }

    @Nested
    inner class AssessStatusAndChecks {
        @Test
        fun `No required statuses removes labels and exits early`() {
            val pull = generateSamplePull(100)
            every { service.removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS) } returns Unit
            mockRequest(200, "OK", BranchDetails("foo", false, Protection(false, RequiredStatusChecks(emptyList()))))

            service.assessStatusAndChecks(pull)
            verify(exactly = 1) { service.removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS) }
        }

        @Test
        fun `Error getting required statuses exits early`() {
            val pull = generateSamplePull(101)
            mockRequest(404, "Not Found")
            service.assessStatusAndChecks(pull)

            verify(exactly = 0) { service.removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS) }
        }

        @Test
        fun `PR is blocked and all statuses are successful causes label to be removed`() {
            val pull = generateSamplePull(102)
            every { service.removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS) } returns Unit
            val checkRuns = Check(1, listOf(StatusCheck("completed", "Foo - CI", "success")))
            val status = Status("Success", 1, listOf(StatusItem("success", null, "Status - Check")))
            val statusChecks = listOf("Foo - CI", "Status - Check")
            val branchDetails = BranchDetails("foo", true, Protection(true, RequiredStatusChecks(statusChecks)))
            mockRequests(
                    mapOf(
                            "$baseUrl/$BRANCHES/${pull.base.ref}" to MockResponse(200, "OK", branchDetails),
                            "$baseUrl/$COMMITS/${pull.head.sha}/check-runs" to MockResponse(200, "OK", checkRuns),
                            "$baseUrl/$COMMITS/${pull.head.sha}/status" to MockResponse(200, "OK", status)
                    )
            )

            service.assessStatusAndChecks(pull)

            verify(exactly = 1) { service.removeLabels(pull, LabelRemovalReason.OUTSTANDING_REVIEWS) }
        }

        @Test
        fun `PR is blocked and at least one status is failing causes label to be removed`() {
            val pull = generateSamplePull(103)
            every { service.removeLabels(pull, LabelRemovalReason.STATUS_CHECKS) } returns Unit
            val checkRuns = Check(1, listOf(StatusCheck("completed", "Foo - CI", "success")))
            val status = Status("Success", 1, listOf(StatusItem("failure", null, "Status - Check")))
            val statusChecks = listOf("Foo - CI", "Status - Check")
            val branchDetails = BranchDetails("foo", true, Protection(true, RequiredStatusChecks(statusChecks)))
            val statusUrl = "$baseUrl/$COMMITS/${pull.head.sha}/${SummaryType.STATUS.route}"
            val checksUrl = "$baseUrl/$COMMITS/${pull.head.sha}/${SummaryType.CHECK_RUNS.route}"
            val mockClient = mockRequests(
                    mapOf(
                            "$baseUrl/$BRANCHES/${pull.base.ref}" to MockResponse(200, "OK", branchDetails),
                            checksUrl to MockResponse(200, "OK", checkRuns),
                            statusUrl to MockResponse(200, "OK", status)
                    )
            )

            service.assessStatusAndChecks(pull)
            assertThat(mockClient.getNumberOfCalls(checksUrl)).isEqualTo(1)
            assertThat(mockClient.getNumberOfCalls(statusUrl)).isEqualTo(1)

            verify(exactly = 1) { service.removeLabels(pull, LabelRemovalReason.STATUS_CHECKS) }
        }

        @Test
        fun `PR is blocked and at least one status is pending does not cause label to be removed`() {
            val pull = generateSamplePull(104)
            every { service.removeLabels(pull, any()) } returns Unit
            val checkRuns = Check(1, listOf(StatusCheck("completed", "Foo - CI", "success")))
            val status = Status("Success", 1, listOf(StatusItem("pending", null, "Status - Check")))
            val statusChecks = listOf("Foo - CI", "Status - Check")
            val branchDetails = BranchDetails("foo", true, Protection(true, RequiredStatusChecks(statusChecks)))
            val statusUrl = "$baseUrl/$COMMITS/${pull.head.sha}/${SummaryType.STATUS.route}"
            val checksUrl = "$baseUrl/$COMMITS/${pull.head.sha}/${SummaryType.CHECK_RUNS.route}"
            val mockClient = mockRequests(
                    mapOf(
                            "$baseUrl/$BRANCHES/${pull.base.ref}" to MockResponse(200, "OK", branchDetails),
                            checksUrl to MockResponse(200, "OK", checkRuns),
                            statusUrl to MockResponse(200, "OK", status)
                    )
            )

            service.assessStatusAndChecks(pull)
            assertThat(mockClient.getNumberOfCalls(checksUrl)).isEqualTo(1)
            assertThat(mockClient.getNumberOfCalls(statusUrl)).isEqualTo(1)

            verify(exactly = 0) { service.removeLabels(pull, any()) }
        }
    }

    private fun generateSamplePull(id: Long) =
            Pull(id, id, "Test PR", "", listOf(Label("Automerge")), Branch("", ""), Branch("", ""))

    private fun mockRequest(statusCode: Int, responseMessage: String, data: Any? = null) {
        every { client.executeRequest(any()).httpStatusCode } returns statusCode
        every { client.executeRequest(any()).httpResponseMessage } returns responseMessage
        every { client.executeRequest(any()).data } returns (data ?: "{}").toJsonString().toByteArray()
        FuelManager.instance.client = client
    }

    private fun mockRequests(mockRequests: Map<String, MockResponse>): MockClient {
        val mockClient = MockClient(mockRequests)
        FuelManager.instance.client = mockClient
        return mockClient
    }

    data class MockResponse(val statusCode: Int, val responseMessage: String, val data: Any? = null)

    class MockClient : Client {
        private val mockRequests: Map<String, MockResponse>
        private val urlToCalls: MutableMap<String, Int> = HashMap()

        constructor(mockRequests: Map<String, MockResponse>) {
            this.mockRequests = mockRequests
        }

        override fun executeRequest(request: Request): Response {
            val response = Response()
            val fullUrl = request.url.toString()
            val (statusCode, responseMessage, data) = mockRequests[fullUrl]!!
            urlToCalls.merge(fullUrl, 1, Int::plus)

            response.httpStatusCode = statusCode
            response.httpResponseMessage = responseMessage
            response.data = (data ?: "{}").toJsonString().toByteArray()
            return response
        }

        fun getNumberOfCalls(url: String): Int = urlToCalls[url] ?: 0
    }
}
