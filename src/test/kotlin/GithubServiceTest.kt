import com.github.kittinunf.fuel.core.Client
import com.github.kittinunf.fuel.core.FuelManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GithubServiceTest {
    private val headers = mapOf(
            "authorization" to "Bearer foo",
            "accept" to "application/vnd.github.v3+json, application/vnd.github.antiope-preview+json",
            "content-type" to "application/json")
    private val config = GithubConfig("http://foo.test/bar", "Automerge", headers)
    private val service = GithubService(config)
    private val client = mockk<Client>()
    private val serviceMock = spyk(service)

    init {
        every { serviceMock.removeLabel(any()) } returns Unit
    }


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
            assertThat(status).isEqualTo(MergeState.BAD)
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
    inner class squashMerge {
        @Test
        fun `Successfully squash merge a pull request`() {
            val pull = generateSamplePull(1)
            mockRequest(200, "OK")
            service.squashMerge(pull)
        }
    }

    private fun generateSamplePull(id: Long) =
            Pull(id, id, "Test PR", "", listOf(Label("Automerge")), Branch("", ""), Branch("", ""))

    private fun mockRequest(statusCode: Int, responseMessage: String, data: Any? = null) {
        every { client.executeRequest(any()).httpStatusCode } returns statusCode
        every { client.executeRequest(any()).httpResponseMessage } returns responseMessage
        data?.let {
            every { client.executeRequest(any()).data } returns data.toJsonString().toByteArray()
        }
        FuelManager.instance.client = client
    }
}

