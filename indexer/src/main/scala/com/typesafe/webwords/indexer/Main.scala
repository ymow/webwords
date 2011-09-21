package com.typesafe.webwords.indexer

import akka.actor._
import com.typesafe.webwords.common._
import java.util.concurrent.CountDownLatch
import com.typesafe.webwords.common.AMQPCheck

object Main extends App {
    if (!AMQPCheck.check(WebWordsConfig(), 0))
        throw new Exception("AMQP not working")

    val worker = Actor.actorOf(new WorkerActor(WebWordsConfig()))

    worker.start

    // kind of a hack maybe.
    val waitForever = new CountDownLatch(1)
    waitForever.await
}
