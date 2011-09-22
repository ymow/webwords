package com.typesafe.webwords.web

import com.typesafe.webwords.common._

/**
 * This is the main() object for the web process. It starts up an embedded
 * Jetty web server which delegates all the work to Akka HTTP.
 */
object WebMain extends App {
    val config = WebWordsConfig()

    // we wait for the indexer's AMQP queue to show up.
    if (!AMQPCheck.check(config, queueToWaitFor = Some("webwords_rpc.request.in"), timeoutMs = 60 * 1000 * 10))
        throw new Exception("Unable to connect to AMQP queue")

    val server = new WebServer(config)
    server.start()
    server.run()
    server.stop()
}
