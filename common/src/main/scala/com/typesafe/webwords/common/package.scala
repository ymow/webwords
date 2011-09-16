package com.typesafe.webwords

import akka.dispatch.Future
import akka.actor.Channel

package object common {

    // Class that adds replyWith to Akka channels
    class EnhancedChannel[-T](underlying: Channel[T]) {
        /**
         * Replies to a channel with the result or exception from
         * the passed-in future
         */
        def replyWith[A <: T](f: Future[A]) = {
            f.onComplete({ f =>
                f.value.get match {
                    case Left(t) =>
                        underlying.sendException(t)
                    case Right(v) =>
                        underlying.tryTell(v)
                }
            })
        }
    }

    // implicitly create an EnhancedChannel wrapper to add methods to the
    // channel
    implicit def enhanceChannel[T](channel: Channel[T]): EnhancedChannel[T] = {
        new EnhancedChannel(channel)
    }
}
