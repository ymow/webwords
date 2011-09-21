package com.typesafe.webwords.indexer

import akka.actor._
import java.util.concurrent.CountDownLatch

object Main extends App {
    val mongoURL = Option(System.getenv("MONGOHQ_URL"))
    val amqpURL = Option(System.getenv("RABBITMQ_URL"))
    val worker = Actor.actorOf(new WorkerActor(amqpURL, mongoURL))

    worker.start

    // kind of a hack maybe.
    val waitForever = new CountDownLatch(1)
    waitForever.await
}
