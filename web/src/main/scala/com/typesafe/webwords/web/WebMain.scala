package com.typesafe.webwords.web

import com.typesafe.webwords.common._

object WebMain extends App {
    val server = new WebServer(WebWordsConfig())
    server.start()
    server.run()
    server.stop()
}
