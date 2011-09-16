package com.typesafe.webwords.common

import java.lang.IllegalStateException
import java.lang.Runnable
import java.lang.System
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import akka.actor._
import akka.dispatch.Future
import akka.dispatch.MessageDispatcher
import akka.util.Duration
import akka.dispatch.FutureTimeoutException
import akka.dispatch.CompletableFuture
import akka.dispatch.DefaultCompletableFuture

/**
 * The idea here is to use Akka's thread pool to run Runnables,
 * rather than creating a new separate one.
 *
 * We could also cheat and create a class in the Akka package that
 * accesses private field ExecutorBasedEventDrivenDispatcher.executorService,
 * but that seems kind of evil, though now that I see this file is so
 * large maybe it was a better idea.
 *
 * Akka 2.0 has a better way so in 2.0 this class should disappear.
 *
 * The ExecutorService implementation is delegated to an ExecutorActor
 * which in turn has a pool of actors used to run tasks.
 */
class AkkaExecutorService(implicit val dispatcher: MessageDispatcher) extends AbstractExecutorService {
    // we track this state on the "outside" of the actor to avoid some complexity
    // in trying to talk to the actor when it might be stopped.
    private val outerShutdown = new AtomicBoolean(false)
    private val outerTerminated = new AtomicBoolean(false)

    // requests
    private sealed trait ExecutorRequest
    private case class Execute(command: Runnable) extends ExecutorRequest
    private case object Shutdown extends ExecutorRequest
    private case class AwaitTermination(timeoutInMs: Long) extends ExecutorRequest
    private case object ShutdownNow extends ExecutorRequest
    private case object GetStatus extends ExecutorRequest
    private case class Completed(task: Task) extends ExecutorRequest

    // replies
    private case class Status(shutdown: Boolean, terminated: Boolean)
    private case object Executed

    private case class Task(future: Future[Executed.type], canceled: AtomicBoolean)

    private class ExecutorActor extends Actor {
        // Tasks in flight
        private var pending: Set[Task] = Set.empty
        // are we shut down
        private var shutdown = false
        // futures to complete when we are terminated
        private var notifyOnTerminated: List[CompletableFuture[Any]] = Nil

        private def addPending(task: Task) = {
            require(!shutdown)
            pending += task
        }

        private def removePending(task: Task) = {
            pending -= task
        }

        override def receive = {
            case request: ExecutorRequest =>
                request match {
                    case Execute(runnable) =>
                        if (shutdown) {
                            throw new RejectedExecutionException("Executor service has been shut down")
                        }
                        val canceled = new AtomicBoolean(false)
                        val f = Future[Executed.type]({
                            if (canceled.get) {
                                throw new Exception("Canceled")
                            } else {
                                runnable.run()
                                Executed
                            }
                        })
                        val task = Task(f, canceled)
                        addPending(task)
                        f.onComplete({ f =>
                            // CAUTION onComplete handler is in another thread so
                            // we just send a message here and then we can keep the
                            // actor single-threaded
                            self ! Completed(task)
                        })
                        self.channel.replyWith(f)
                    case Completed(task) =>
                        removePending(task)

                        if (shutdown && pending.isEmpty) {
                            notifyOnTerminated foreach { l =>
                                akka.event.EventHandler.info(self, " sending a terminated notification")
                                l.complete(Right(Status(shutdown, isTerminated)))
                            }
                            notifyOnTerminated = Nil
                            // commit suicide (by sending a poison pill, so only after we
                            // process anything still in our mailbox - notably if there's
                            // an AwaitTermination there we want to reply to it, not leave it
                            // to time out)
                            self ! PoisonPill
                        }
                    case GetStatus =>
                        self.tryReply(Status(shutdown, isTerminated))
                    case Shutdown =>
                        shutdown = true
                        self.tryReply(Status(shutdown, isTerminated))
                    case ShutdownNow =>
                        akka.event.EventHandler.info(self, "ShutdownNow pending=" + pending.size)
                        shutdown = true
                        // try to cancel our futures (best effort)
                        pending foreach { p =>
                            p.canceled.set(true)
                            p.future match {
                                case completable: CompletableFuture[_] if !completable.isCompleted =>
                                    completable.completeWithException(new Exception("Canceled"))
                                case f if f.isCompleted =>
                                case f =>
                                    throw new Exception("Don't know how to complete future: " + f)
                            }
                        }
                        self.tryReply(Status(shutdown, isTerminated))
                    case AwaitTermination(inMs) =>
                        akka.event.EventHandler.info(self, "got AwaitTermination pending=" + pending.size)
                        awaitTermination(inMs)
                        if (isTerminated) {
                            akka.event.EventHandler.info(self, "Already terminated, sending status")
                            self.tryReply(Status(shutdown, isTerminated))
                        } else {
                            akka.event.EventHandler.info(self, "Will notify of termination, pending: " + pending.size)
                            val f = new DefaultCompletableFuture[Any]()
                            notifyOnTerminated = f :: notifyOnTerminated
                            self.channel.replyWith(f)
                        }
                }
        }

        private def isTerminated = {
            shutdown && pending.isEmpty
        }

        private def awaitTermination(timeoutInMs: Long): Unit = {
            akka.event.EventHandler.info(self, "awaitTermination pending=" + pending.size)
            if (!shutdown) {
                throw new IllegalStateException("must shutdown to awaitTermination")
            }

            val start = System.currentTimeMillis()
            var remainingTimeMs = timeoutInMs
            for (p <- pending) {
                p.future.await(Duration(remainingTimeMs, TimeUnit.MILLISECONDS))

                val elapsed = System.currentTimeMillis() - start
                remainingTimeMs = timeoutInMs - elapsed
                if (remainingTimeMs < 0) {
                    // we'll still await() all the futures, but with timeout of 0,
                    // so if they're complete already they will finish up.
                    remainingTimeMs = 0
                }
            }
            // At this point, all the futures hopefully completed within the timeout,
            // but all the onComplete probably did NOT run yet to drain "pending".
            // We should get Completed messages from the still-pending tasks
            // which will cause us to finally reply to the AwaitTermination
            // message
        }

        override def preStart = {
            akka.event.EventHandler.info(self, "Starting up executor actor")
        }
        override def postStop = {
            akka.event.EventHandler.info(self, "Shutting down executor actor")
            require(notifyOnTerminated.isEmpty)
            require(pending.isEmpty)
        }
    }

    private val actor = Actor.actorOf(new ExecutorActor).start

    private def tryAsk(message: Any): CompletableFuture[Any] = {
        // "?" will throw by default on a stopped actor; we want to put an exception
        // in the future instead to avoid special cases
        try {
            actor ? message
        } catch {
            case e: ActorInitializationException =>
                akka.event.EventHandler.info(actor, "actor was not running, send failed")
                val f = new DefaultCompletableFuture[Any]()
                f.completeWithException(new FutureTimeoutException("Actor was not running, immediate timeout"))
                f
        }
    }

    override def execute(command: Runnable): Unit = {
        if (outerShutdown.get) {
            throw new RejectedExecutionException("Executor service has been shut down")
        }
        actor ! Execute(command)
    }

    private def handleStatusFuture(f: Future[Any]): Unit = {
        if (f.isCompleted) {
            f.value.get match {
                case Left(t: Throwable) =>
                    // If exception is due to actor not running, then shut down
                    if (!actor.isRunning) {
                        outerShutdown.set(true)
                        outerTerminated.set(true)
                    }
                case Right(Status(shutdown, terminated)) =>
                    if (shutdown)
                        outerShutdown.set(true)
                    if (terminated)
                        outerTerminated.set(true)
                case _ =>
                    throw new IllegalStateException("got wrong reply expected Status")
            }
        }
    }

    override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
        akka.event.EventHandler.info(actor, "outer awaitTermination() method")
        if (outerTerminated.get) {
            akka.event.EventHandler.info(actor, "already terminated")
            true
        } else {
            akka.event.EventHandler.info(actor, "sending AwaitTermination")
            val f = tryAsk(AwaitTermination(unit.toMillis(timeout)))
            f.await(Duration(timeout, unit))
            handleStatusFuture(f)
            outerTerminated.get
        }
    }

    override def isShutdown: Boolean = {
        if (outerShutdown.get) {
            true
        } else {
            val f = tryAsk(GetStatus).await
            handleStatusFuture(f)
            outerShutdown.get
        }
    }

    override def isTerminated: Boolean = {
        if (outerTerminated.get) {
            true
        } else {
            val f = tryAsk(GetStatus).await
            handleStatusFuture(f)
            outerTerminated.get
        }
    }

    override def shutdown = {
        if (outerShutdown.get) {
            // nothing to do
        } else {
            val f = tryAsk(Shutdown).await
            handleStatusFuture(f)
            require(outerShutdown.get)
        }
    }

    override def shutdownNow: java.util.List[Runnable] = {
        shutdown

        val f = tryAsk(ShutdownNow).await
        handleStatusFuture(f)

        // Since we send everything to an actor immediately, we don't know
        // if the actor already did runnable.run() but we have to assume it did
        // and return an empty list here. The API contract for shutdownNow()
        // is documented as "best effort" so this should be fine.
        // we can stop all the actors so if they haven't done run() yet their
        // futures should just come back with an error.
        java.util.Collections.emptyList()
    }
}
