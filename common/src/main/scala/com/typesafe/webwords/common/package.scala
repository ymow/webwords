package com.typesafe.webwords

import akka.actor.Channel
import akka.actor.ActorKilledException
import akka.actor.ActorRef
import akka.actor.LocalActorRef
import akka.dispatch.Future
import akka.dispatch.MessageInvocation
import akka.dispatch.MessageQueue

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

    private def getMailbox(self: ActorRef) = {
        self match {
            // LocalActorRef.mailbox is public but
            // ActorRef.mailbox is not; not sure if
            // it's deliberate or a bug, but we use it...
            // this code can be deleted with newer Akka versions
            // that have fix https://www.assembla.com/spaces/akka/tickets/894
            case local: LocalActorRef =>
                local.mailbox
            case _ =>
                throw new Exception("Can't get mailbox on this ActorRef: " + self)
        }
    }

    private def invocations(mq: MessageQueue): Stream[MessageInvocation] = {
        val mi = mq.dequeue
        if (mi eq null)
            Stream.empty
        else
            Stream.cons(mi, invocations(mq))
    }

    private def sendExceptionsToMailbox(mailbox: AnyRef) = {
        mailbox match {
            case mq: MessageQueue =>
                invocations(mq) foreach { mi =>
                    mi.channel.sendException(new ActorKilledException("Actor is about to suicide"))
                }
            case _ =>
                throw new Exception("Don't know how to iterate over mailbox: " + mailbox)
        }
    }

    // Akka 2.0 has a fix where, on stopping an actor,
    // the sender gets an exception;
    // see https://www.assembla.com/spaces/akka/tickets/894
    // In 1.2, we use this temporary workaround to simulate the 2.0 behavior.
    def stopActorNotifyingMailbox(self: ActorRef) = {
        val mailbox = getMailbox(self)
        self.stop
        sendExceptionsToMailbox(mailbox)
    }
}
