package com.typesafe.webwords.indexer

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import java.net.URL
import com.typesafe.webwords.common._

class WorkerActorSpec extends FlatSpec with ShouldMatchers with BeforeAndAfterAll {
    var httpServer: TestHttpServer = null

    override def beforeAll = {
        httpServer = new TestHttpServer(Some(this.getClass))
        httpServer.start()
    }

    override def afterAll = {
        httpServer.stop()
        httpServer = null
    }

    behavior of "WorkerActor"

    it should "get an index" in {
        val testdb = Some("mongodb://localhost/webwordsworkertest")
        val url = httpServer.resolve("/resource/ToSpider.html")
        val worker = Actor.actorOf(new WorkerActor(None, testdb)).start
        Thread.sleep(500) // help ensure worker's amqp exchange is set up
        val client = Actor.actorOf(new ClientActor(None, testdb)).start
        val indexFuture = (client ? GetIndex(url.toExternalForm)) map { result =>
            result match {
                case GotIndex(url, Some(index)) =>
                    index
                case _ =>
                    throw new Exception("Got bad result from worker: " + result)
            }
        }
        val index = indexFuture.get

        index.wordCounts.size should be(50)
        val nowheres = (index.links filter { link => link._2.endsWith("/nowhere") } map { _._1 }).sorted
        nowheres should be(Seq("a", "d", "e", "f", "g", "h", "j", "k", "m", "o"))
    }
}
