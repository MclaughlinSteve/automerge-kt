import com.github.kittinunf.fuel.httpDelete
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.fuel.httpPut

/**
 * Wrapper class to simplify fuel usage
 */
class Http(private val headers: Map<String, String>) {

    /**
     * Helper function for making http GET requests
     * @param url the url to make the request on
     * @return Triple<Request, Response, Result<String, FuelError>>
     */
    fun get(url: String) = url.httpGet().header(headers).responseString()

    /**
     * Helper function for making http DELETE requests
     * @param url the url to make the request on
     * @return Triple<Request, Response, Result<String, FuelError>>
     */
    fun delete(url: String) = url.httpDelete().header(headers).responseString()

    /**
     * Helper function for making http PUT requests
     * @param url the url to make the request on
     * @param body the body of the request
     * @return Triple<Request, Response, Result<String, FuelError>>
     */
    fun put(url: String, body: Any) = url.httpPut().body(body.toJsonString()).header(headers).responseString()

    /**
     * Helper function for making http POST requests
     * @param url the url to make the request on
     * @param body the body of the request
     * @return Triple<Request, Response, Result<String, FuelError>>
     */
    fun post(url: String, body: Any) = url.httpPost().body(body.toJsonString()).header(headers).responseString()
}
