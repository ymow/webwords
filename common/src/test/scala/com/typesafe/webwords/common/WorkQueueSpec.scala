package com.typesafe.webwords.common

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import akka.dispatch._

class WorkQueueSpec extends FlatSpec with ShouldMatchers {

    private class EchoWorker(amqpUrl: Option[String] = None) extends WorkQueueWorkerActor(amqpUrl) {
        override def handleRequest(request: WorkQueueRequest) = {
            request match {
                case SpiderAndCache(url) =>
                    new AlreadyCompletedFuture[WorkQueueReply](Right(SpideredAndCached(url)))
            }
        }
    }

    behavior of "AMQP url parsing"

    it should "parse a complex url" in {
        val url = "amqp://uname:pwd@host:13029/vhost"
        val params = AbstractWorkQueueActor.parseAmqpUrl(url)
        params.addresses(0).getHost should be("host")
        params.addresses(0).getPort should be(13029)
        params.username should be("uname")
        params.password should be("pwd")
        params.virtualHost should be("vhost")
    }

    it should "parse a default url" in {
        val url = "amqp:///"
        val params = AbstractWorkQueueActor.parseAmqpUrl(url)
        val defaults = akka.amqp.AMQP.ConnectionParameters()
        params.addresses(0).getHost should be(defaults.addresses(0).getHost)
        params.addresses(0).getPort should be(defaults.addresses(0).getPort)
        params.username should be(defaults.username)
        params.password should be(defaults.password)
        params.virtualHost should be(defaults.virtualHost)
    }

    behavior of "serialization"

    it should "serialize and deserialize request" in {
        val request = SpiderAndCache("http://example.com/")
        val binary = WorkQueueRequest.toBinary.toBinary(request)
        val decoded = WorkQueueRequest.fromBinary.fromBinary(binary)
        decoded should be(request)
    }

    it should "serialize and deserialize reply" in {
        val reply = SpideredAndCached("http://example.com/")
        val binary = WorkQueueReply.toBinary.toBinary(reply)
        val decoded = WorkQueueReply.fromBinary.fromBinary(binary)
        decoded should be(reply)
    }

    behavior of "RPC"

    it should "perform a round trip" in {
        // The worker side sets up the exchange, while the client
        // will throw errors if it isn't set up yet. So the
        // worker has to go first.
        val worker = Actor.actorOf(new EchoWorker())
        worker.start

        Thread.sleep(500)

        val client = Actor.actorOf(new WorkQueueClientActor())
        client.start

        val url = "http://example.com/"
        val result = (client ? SpiderAndCache(url)).get

        result should be(SpideredAndCached(url))

        client.stop
        worker.stop
    }
}
