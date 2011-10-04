package com.typesafe.webwords.indexer

import akka.actor.{ Index => _, _ }
import akka.actor.Actor.actorOf
import akka.dispatch._
import akka.event.EventHandler
import com.typesafe.webwords.common._
import java.net.URL

/**
 * This actor listens to the work queue, spiders and caches results.
 * It's the "root" actor of the indexer process.
 */
class WorkerActor(config: WebWordsConfig)
    extends WorkQueueWorkerActor(config.amqpURL) {
    private val spider = actorOf[SpiderActor]
    private val cache = actorOf(new IndexStorageActor(config.mongoURL))

    override def handleRequest(request: WorkQueueRequest): Future[WorkQueueReply] = {
        request match {
            case SpiderAndCache(url) =>
                // This "neverFailsFuture" is sort of a hacky hotfix; AMQP setup
                // doesn't react well to returning an exception here, which happens
                // when there's a bug typically.
                // We could do various nicer things like send the exception over
                // the wire cleanly, or configure AMQP differently, but requires
                // some time to work out. Hotfixing with this.
                val neverFailsFuture = new DefaultCompletableFuture[WorkQueueReply]
                val futureIndex = spider ? Spider(new URL(url)) map {
                    _ match { case Spidered(url, index) => index }
                }
                futureIndex flatMap { index =>
                    cache ? CacheIndex(url, index) map { cacheAck =>
                        SpideredAndCached(url)
                    }
                } onResult {
                    case reply: WorkQueueReply =>
                        neverFailsFuture.completeWithResult(reply)
                } onException {
                    case e =>
                        EventHandler.info(this, "Exception spidering '" + url + "': " + e.getClass.getSimpleName + ": " + e.getMessage)
                        neverFailsFuture.completeWithResult(SpideredAndCached(url))
                }
                neverFailsFuture
        }
    }

    override def preStart = {
        super.preStart
        spider.start
        cache.start
    }

    override def postStop = {
        super.postStop
        spider.stop
        cache.stop
    }
}
