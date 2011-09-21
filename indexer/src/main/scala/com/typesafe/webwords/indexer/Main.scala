package com.typesafe.webwords.indexer

import akka.actor._
import com.typesafe.webwords.common._
import java.util.concurrent.CountDownLatch

object Main extends App {
    val worker = Actor.actorOf(new WorkerActor(WebWordsConfig()))

    worker.start

    // kind of a hack maybe.
    val waitForever = new CountDownLatch(1)
    waitForever.await
}
