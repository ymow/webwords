package com.typesafe.webwords.web

import com.typesafe.webwords.common._

object WebMain extends App {
    // This is a temporary hack to help the web process start later than the worker
    // and keep AMQP from freaking out.
    Thread.sleep(5000)
    val server = new WebServer(WebWordsConfig())
    server.start()
    server.run()
    server.stop()
}
