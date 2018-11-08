import io.netty.util.ThreadDeathWatcher
import io.netty.util.concurrent.GlobalEventExecutor
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.client.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import java.lang.management.ManagementFactory
import java.util.logging.Logger

const val NUM_REQUESTS = 15
const val TIMEOUT:Long = 9000
val logger: Logger = Logger.getLogger("Main")

fun main(args: Array<String>){

    logger.info("STARTING...")
    //get the PID of the process
    logger.info("PID " + ManagementFactory.getRuntimeMXBean().name)
    (1..100).forEach {
        println("Running iteration $it")
        // .use will auto-close the session and clean up any netty things
        Session().use {
            it.run { vertx ->
                val webClientOptions = WebClientOptions()
                webClientOptions.isSsl = true

                val webClient = WebClient.create(vertx, webClientOptions)

                CompositeFuture.join((1..NUM_REQUESTS).map {
                    val responseFuture = Future.future<HttpResponse<Buffer>>()
                    val mappedFuture = Future.future<HttpResponse<Buffer>>()
                    responseFuture.setHandler {
                        logger.info("response returned with success? ${it.succeeded()}. cause: ${it.cause()}")
                        mappedFuture.complete()
                    }
                    val request = webClient.get(443, "httpbin.org", "/delay/${it % 10}")
                    request.timeout(TIMEOUT)
                    request.send(responseFuture)
                    mappedFuture
                })
            }
        }
        logger.info("Ending iteration $it")
    }
}


/**
 * An object for management of the creation and destruction lifecycle of a Vert.x event loop instance.
 *
 * @param closeTimeout amount of time to wait for each Netty object to shut down
 */
class Session(private val closeTimeout: Timeout = Timeout(5, TimeUnit.SECONDS)) : AutoCloseable {
    private val logger = Logger.getLogger("Session")
    private val vertx: Vertx
    // Since vertx.close() is asynchronous; wait until the close completes by using a latch. This is used in
    // awaitTermination().
    private val closeLatch = CountDownLatch(1)

    private data class ShutdownObj(val name: String, val awaitInactivity: (Long, TimeUnit) -> (Boolean))

    /**
     * List of Netty objects that need to be shut down before the Vert.x wrapper is truly unloaded.
     * [GlobalEventExecutor] is a part of Netty's event loop responsible for executing the actual events.
     * [ThreadDeathWatcher] watches for thread termination and informs subscribers that the thread is dead.
     * Both are seemingly necessary parts of using Netty that we need to wait for to die before moving on.
     */
    private val shutdownObjs = listOf(
            ShutdownObj("GlobalEventExecutor") { timeout, unit ->
                GlobalEventExecutor.INSTANCE.awaitInactivity(timeout, unit)
            },
            ShutdownObj("ThreadDeathWatcher") { timeout, unit ->
                ThreadDeathWatcher.awaitInactivity(timeout, unit)
            }
    )

    init {
        vertx = time("Initializing Vert.x event loop") {
            Vertx.vertx(VertxOptions().apply {
                eventLoopPoolSize = 1
            })
        }
    }

    /**
     * Run the given block with the created [Vertx] instance, waiting for the returned [Future] to be completed.
     *
     * @param body block to run with the created [Vertx] instance
     * @return future that the user code will eventually complete
     */
    fun <T> run(body: (Vertx) -> Future<T>): T? {
        var result: T? = null
        var cause: Throwable? = null
        var success = true
        // Unlike some other reactors that block the main thread, Vert.x's event loop spawns in a different thread. We must
        // therefore use a latch to wait for the return value to be computed. This is used in run() and exit().
        val resultLatch = CountDownLatch(1)
        time("Running Vert.x user code") {
            body(vertx).setHandler { asyncResult ->
                success = asyncResult.succeeded()
                result = asyncResult.result()
                cause = asyncResult.cause()
                // Release the user code result latch
                resultLatch.countDown()
                if(resultLatch.count == 0L){
                    // Initiate shutdown of the event loop
                    logger.info(CLOSE_ID)
                    vertx.close {
                        logger.info("Event loop shutdown complete.")
                        closeLatch.countDown()
                    }
                } else{
                    logger.info("waiting to shut down event loop...")
                }
            }
            logger.info("Waiting for Vert.x user code Future to complete")
            resultLatch.await()
        }
        // Note: We use the !! operator here because we know that result and cause will be populated if the Future
        // succeeded or failed, respectively.
        if (success) {
            logger.info("Vert.x user code completed successfully")
            return result!!
        }
        logger.info("Vert.x user code completed with error")
        //we don't care about the result, but print the cause if exists
        logger.info(cause.toString())
        return null
    }

    /**
     * Wait for Vert.x and Netty to terminate.
     */
    override fun close() {
        logger.info("Waiting for Vert.x event loop to close")
        closeLatch.await()
        logger.info(CLOSE_ID)
        time("Shutting down Netty") {
            // See here: https://github.com/netty/netty/issues/2084#issuecomment-44822314
            shutdownObjs.forEach { obj ->
                if (time("Waiting up to ${closeTimeout.value} ${closeTimeout.unit.toString().toLowerCase()} for ${obj.name} to shut down") {
                            obj.awaitInactivity(closeTimeout.value, closeTimeout.unit)
                        }) {
                    logger.info("${obj.name} shut down successfully")
                } else {
                    logger.info("${obj.name} failed to shut down!")
                }
            }
        }
    }

    companion object {
        private const val CLOSE_ID = "Closing Vert.x event loop"
    }
}

data class Timeout(val value: Long, val unit: TimeUnit) {
    override fun toString() = "$value ${unit.toString().toLowerCase()}"
}

fun <T> time(message: String, body: () -> T): T{
    logger.info("Starting $message")
    return try{
        body()
    } finally {
        logger.info("Ending $message")
    }
}

