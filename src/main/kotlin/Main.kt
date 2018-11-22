import com.sun.management.UnixOperatingSystemMXBean
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Logger


const val NUM_REQUESTS = 20
const val TIMEOUT: Long = 8000
val logger: Logger = Logger.getLogger("Main")

fun main(args: Array<String>) {
    //get the PID of the process
    logger.info("PID " + ManagementFactory.getRuntimeMXBean().name)
    val os = ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean

    //calculate start time for logging purposes
    var startTime = System.currentTimeMillis()
    //create a loop to simulate the event loop opening and closing
    (1..100).forEach {
        //create a latch so that we wait for our requests to complete before shutting down the event loop
        val latch = CountDownLatch(1)

        //Create the vert.x object for use in the web client
        val vertxOptions = VertxOptions()
        vertxOptions.eventLoopPoolSize = 1
        val vertx = Vertx.vertx(vertxOptions)

        //create the web client to use
        val webClientOptions = WebClientOptions()
        webClientOptions.isSsl = true
        val webClient = WebClient.create(vertx, webClientOptions)

        try {
            //create NUM_REQUESTS requests to httpbin's delay service
            val requests = (1..NUM_REQUESTS).map { delay ->
                val responseFuture = Future.future<HttpResponse<Buffer>>()
                //this request will not get a response for $delay seconds
                val request = webClient.get(443, "httpbin.org", "/delay/$delay")
                request.timeout(TIMEOUT)
                //send request
                request.send(responseFuture)
                responseFuture
            }
            //wait for all requests to finish
            CompositeFuture.join(requests).setHandler {
                latch.countDown()
            }

            //wait 10 second for futures to complete
            latch.await(10, TimeUnit.SECONDS)

        } finally {
            //ensure the web client will be closed if any exceptions occur
            webClient.close()
            //this close needs to be wrapped in a latch so that it shuts down properly
            vertx.close {
                logger.info("Event loop shutdown Complete.")
            }
        }

        val endtime = System.currentTimeMillis()
        val timeSpent = (endtime - startTime) / 1000F
        startTime = endtime
        //log number of open file descriptors and time spent
        logger.info("Time spent: $timeSpent Seconds. Number of open fd: " + os.openFileDescriptorCount)
    }
}

