package com.typesafe.webwords.common

import java.net.URL

import akka.actor.{ Index => _, _ }
import akka.dispatch._

sealed trait ClientActorIncoming
case class GetIndex(url: String) extends ClientActorIncoming

sealed trait ClientActorOutgoing
case class GotIndex(url: String, index: Option[Index]) extends ClientActorOutgoing

class ClientActor(amqpURL: Option[String], mongoURL: Option[String]) extends Actor {

    private val client = Actor.actorOf(new WorkQueueClientActor(amqpURL))
    private val cache = Actor.actorOf(new IndexStorageActor(mongoURL))

    override def receive = {
        case incoming: ClientActorIncoming =>
            incoming match {
                case GetIndex(url) =>
                    val futureSpideredAck =
                        client ? SpiderAndCache(url) map { spidered =>
                            spidered match {
                                case SpideredAndCached(url) =>
                                    url
                            }
                        }
                    val futureGotIndex: Future[ClientActorOutgoing] =
                        futureSpideredAck flatMap { url =>
                            cache ? FetchCachedIndex(url) map { fetched =>
                                fetched match {
                                    case CachedIndexFetched(indexOption) =>
                                        GotIndex(url, indexOption)
                                }
                            }
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
