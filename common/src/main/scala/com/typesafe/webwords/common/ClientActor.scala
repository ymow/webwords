package com.typesafe.webwords.common

import java.net.URL

import akka.actor.{ Index => _, _ }
import akka.dispatch._

sealed trait ClientActorIncoming
case class GetIndex(url: String, skipCache: Boolean) extends ClientActorIncoming

sealed trait ClientActorOutgoing
case class GotIndex(url: String, index: Option[Index], cacheHit: Boolean) extends ClientActorOutgoing

/**
 * This actor encapsulates:
 *  - checking the cache for an index of a certain URL
 *  - asking the indexer worker process to index the URL if it's not cached
 *  - checking the cache again when the worker is done
 * It coordinates a WorkQueueClientActor and IndexStorageActor to accomplish
 * this.
 */
class ClientActor(config: WebWordsConfig) extends Actor {
    import ClientActor._

    private val client = Actor.actorOf(new WorkQueueClientActor(config.amqpURL))
    private val cache = Actor.actorOf(new IndexStorageActor(config.mongoURL))

    override def receive = {
        case incoming: ClientActorIncoming =>
            incoming match {
                case GetIndex(url, skipCache) =>

                    // we look in the cache, if that fails, ask spider to
                    // spider and then notify us, and then we look in the
                    // cache again.
                    def getWithoutCache = {
                        getFromWorker(client, url) flatMap { _ =>
                            getFromCacheOrElse(cache, url, cacheHit = false) {
                                new AlreadyCompletedFuture[GotIndex](Right(GotIndex(url, index = None, cacheHit = false)))
                            }
                        }
                    }

                    val futureGotIndex = if (skipCache)
                        getWithoutCache
                    else
                        getFromCacheOrElse(cache, url, cacheHit = true) { getWithoutCache }

                    self.channel.replyWith(futureGotIndex)
            }
    }

    override def preStart = {
        client.start
        cache.start
    }

    override def postStop = {
        client.stop
        cache.stop
    }
}

object ClientActor {
    private def getFromCacheOrElse(cache: ActorRef, url: String, cacheHit: Boolean)(fallback: => Future[GotIndex]): Future[GotIndex] = {
        cache ? FetchCachedIndex(url) flatMap { fetched =>
            fetched match {
                case CachedIndexFetched(Some(index)) =>
                    new AlreadyCompletedFuture(Right(GotIndex(url, Some(index), cacheHit)))
                case CachedIndexFetched(None) =>
                    fallback
            }
        }
    }

    private def getFromWorker(client: ActorRef, url: String): Future[Unit] = {
        client ? SpiderAndCache(url) map { spidered =>
            spidered match {
                case SpideredAndCached(returnedUrl) =>
                    Unit
            }
        }
    }
}
