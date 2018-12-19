import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.result.Result
import mu.KotlinLogging
import java.time.Instant
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * Print out some formatted information about errors to the console for debugging purposes
 * @param result the failure information from the http request/response
 * @param message the failure message that will be displayed before the error - default: "Something went wrong"
 */
fun logFailure(result: Result.Failure<String, FuelError>, message: String = "Something went wrong!") =
        logger.error {
            """
            $message
            |======================
            | Time: ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}
            |
            | Exception:
            | ${result.getException()}
            |======================
        """.trimIndent()
        }
