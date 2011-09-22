package com.typesafe.webwords.web

import com.typesafe.webwords.common._

/**
 * This is the main() object for the web process. It starts up an embedded
 * Jetty web server which delegates all the work to Akka HTTP.
 */
object WebMain extends App {
    // This is a hack to help the web process start later than the worker
    // and keep AMQP from freaking out.
    Thread.sleep(5000)
    val server = new WebServer(WebWordsConfig())
    server.start()
    server.run()
    server.stop()
}
