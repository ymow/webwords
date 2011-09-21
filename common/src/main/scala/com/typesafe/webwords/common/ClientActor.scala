package com.typesafe.webwords.common

import java.net.URL

import akka.actor.{ Index => _, _ }
import akka.dispatch._

sealed trait ClientActorIncoming
case class GetIndex(url: String) extends ClientActorIncoming

sealed trait ClientActorOutgoing
case class GotIndex(url: String, index: Option[Index]) extends ClientActorOutgoing

class ClientActor(config: WebWordsConfig) extends Actor {
    import ClientActor._

    private val client = Actor.actorOf(new WorkQueueClientActor(config.amqpURL))
    private val cache = Actor.actorOf(new IndexStorageActor(config.mongoURL))

    override def receive = {
        case incoming: ClientActorIncoming =>
            incoming match {
                case GetIndex(url) =>
                    // we look in the cache, if that fails, ask spider to
                    // spider and then notify us, and then we look in the
                    // cache again.
                    val futureIndexOption = getFromCacheOrElse(cache, url) {
                        getFromWorker(client, url) flatMap { _ =>
                            getFromCacheOrElse(cache, url) {
                                new AlreadyCompletedFuture[Option[Index]](Right(None))
                            }
                        }
                    }

                    val futureGotIndex: Future[ClientActorOutgoing] =
                        futureIndexOption map { indexOption =>
                            GotIndex(url, indexOption)
                        }

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
    private def getFromCacheOrElse(cache: ActorRef, url: String)(fallback: => Future[Option[Index]]): Future[Option[Index]] = {
        cache ? FetchCachedIndex(url) flatMap { fetched =>
            fetched match {
                case CachedIndexFetched(Some(index)) =>
                    new AlreadyCompletedFuture(Right(Some(index)))
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
