package com.typesafe.webwords.indexer

import akka.actor.{ Index => _, _ }
import akka.actor.Actor.actorOf
import akka.dispatch._
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
                val futureIndex = spider ? Spider(new URL(url)) map {
                    _ match { case Spidered(url, index) => index }
                }
                futureIndex flatMap { index =>
                    cache ? CacheIndex(url, index) map { cacheAck =>
                        SpideredAndCached(url)
                    }
                }
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
