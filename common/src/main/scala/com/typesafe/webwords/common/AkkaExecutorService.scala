package com.typesafe.webwords.common

import scala.collection.immutable.Queue
import scala.collection.JavaConverters._
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
    private case object GetStatus extends ExecutorRequest
    private case class Completed(task: Task, canceled: Boolean) extends ExecutorRequest

    // replies
    private sealed trait ExecutorReply
    private case class Status(shutdown: Boolean, terminated: Boolean) extends ExecutorReply
    private case class TerminationAwaited(status: Status, runnables: Seq[Runnable]) extends ExecutorReply
    private sealed trait ExecutorReplyToExecute extends ExecutorReply {
        val command: Runnable
    }
    private case class Executed(override val command: Runnable) extends ExecutorReplyToExecute
    private case class Canceled(override val command: Runnable) extends ExecutorReplyToExecute
    private case class Rejected(override val command: Runnable) extends ExecutorReplyToExecute

    private case class Task(future: Future[ExecutorReplyToExecute], runnable: Runnable)

    private class ExecutorActor(cancelRequested: AtomicBoolean) extends Actor {
        // Tasks in flight
        private var pending: Set[Task] = Set.empty
        // are we shut down
        private var shutdown = false
        // futures to complete when we are terminated
        private var notifyOnTerminated: List[CompletableFuture[TerminationAwaited]] = Nil
        // runnables that we canceled with shutdownNow
        private var canceled = Queue.empty[Runnable]

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
                        if (cancelRequested.get) {
                            canceled = canceled.enqueue(runnable)
                            self.tryReply(Canceled(runnable))
                        } else if (shutdown) {
                            self.tryReply(Rejected(runnable))
                        } else {
                            val f = Future[ExecutorReplyToExecute]({
                                if (cancelRequested.get) {
                                    Canceled(runnable)
                                } else {
                                    runnable.run()
                                    Executed(runnable)
                                }
                            })
                            val task = Task(f, runnable)
                            addPending(task)
                            // notify ourselves when the task is finished
                            f.onComplete({ f =>
                                val wasCanceled = f.result map {
                                    _ match {
                                        case c: Canceled => true
                                        case _ => false
                                    }
                                } getOrElse (false)
                                // CAUTION onComplete handler is in another thread so
                                // we just send a message to ourselves here to avoid
                                // trying to do anything from the other thread
                                self ! Completed(task, wasCanceled)
                            })
                            self.channel.replyWith(f)
                        }
                    case Completed(task, wasCanceled) =>
                        removePending(task)
                        if (wasCanceled) {
                            canceled = canceled.enqueue(task.runnable)
                        }
                        if (shutdown && pending.isEmpty) {
                            notifyOnTerminated foreach { l =>
                                akka.event.EventHandler.info(self, " sending a terminated notification")
                                l.complete(Right(terminationAwaitedReply))
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
                        akka.event.EventHandler.info(self, "processed Shutdown, pending=" + pending.size)
                        self.tryReply(Status(shutdown, isTerminated))
                    case AwaitTermination(inMs) =>
                        akka.event.EventHandler.info(self, "got AwaitTermination pending=" + pending.size)
                        awaitTermination(inMs)
                        if (isTerminated) {
                            akka.event.EventHandler.info(self, "Already terminated, sending status")
                            self.tryReply(terminationAwaitedReply)
                        } else {
                            akka.event.EventHandler.info(self, "Will notify of termination later, pending: " + pending.size)
                            val f = new DefaultCompletableFuture[TerminationAwaited]()
                            notifyOnTerminated = f :: notifyOnTerminated
                            self.channel.replyWith(f)
                        }
                }
        }

        private def terminationAwaitedReply = {
            val tmp = canceled
            canceled = Queue.empty
            TerminationAwaited(Status(shutdown, isTerminated), tmp)
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

    private val cancelRequested = new AtomicBoolean(false)
    private val actor = Actor.actorOf(new ExecutorActor(cancelRequested)).start

    private def tryAsk(message: Any): CompletableFuture[Any] = {
        // "?" will throw by default on a stopped actor; we want to put an exception
        // in the future instead to avoid special cases
        try {
            actor ? message
        } catch {
            case e: ActorInitializationException =>
                akka.event.EventHandler.info(actor, "actor was not running, send failed: " + message)
                val f = new DefaultCompletableFuture[Any]()
                f.completeWithException(new FutureTimeoutException("Actor was not running, immediate timeout"))
                f
        }
    }

    override def execute(command: Runnable): Unit = {
        if (outerShutdown.get || cancelRequested.get) {
            throw new RejectedExecutionException("Executor service has been shut down")
        }
        actor ! Execute(command)
    }

    private def handleStatusFuture(f: Future[Any], duration: Option[Duration] = None): Unit = {
        try {
            // Wait for status reply to arrive. await will throw a timeout exception
            // but not the exception contained in the future.
            if (duration.isDefined)
                f.await(duration.get)
            else
                f.await

            require(f.isCompleted)

            // f.get throws the exception contained in the future, if any.
            // it also does a no-time-limit await but since we are already
            // completed, that should be a no-op.
            f.get match {
                case status: Status =>
                    akka.event.EventHandler.info(actor, "status=" + status)
                    if (status.shutdown)
                        outerShutdown.set(true)
                    if (status.terminated)
                        outerTerminated.set(true)
            }
        } catch {
            case e: Throwable =>
                akka.event.EventHandler.info(actor, "status future threw, actor.isRunning=" + actor.isRunning)
                // If exception is due to actor not running, then shut down
                if (!actor.isRunning) {
                    outerShutdown.set(true)
                    outerTerminated.set(true)
                }
        }
    }

    private def awaitTerminationWithCanceled(timeout: Long, unit: TimeUnit): (Boolean, Seq[Runnable]) = {
        akka.event.EventHandler.info(actor, "awaitTerminationWithCanceled() method")
        if (outerTerminated.get) {
            akka.event.EventHandler.info(actor, "already terminated")
            (true, Nil)
        } else {
            akka.event.EventHandler.info(actor, "sending AwaitTermination")

            val f = tryAsk(AwaitTermination(unit.toMillis(timeout)))
            val statusFuture = f map { v =>
                v match {
                    case TerminationAwaited(status, canceled) =>
                        status
                }
            }

            // block on a reply, setting outerTerminated from it
            handleStatusFuture(statusFuture, Some(Duration(timeout, unit)))

            // extract list of canceled runnables from the reply
            val canceled = if (f.result.isDefined) {
                f.result.get match {
                    case TerminationAwaited(status, canceled) =>
                        canceled
                }
            } else {
                Nil
            }

            (outerTerminated.get, canceled)
        }
    }

    override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
        akka.event.EventHandler.info(actor, "outer awaitTermination() method")
        awaitTerminationWithCanceled(timeout, unit)._1
    }

    override def isShutdown: Boolean = {
        akka.event.EventHandler.info(actor, "isShutdown() method, outerShutdown=" + outerShutdown.get)
        if (outerShutdown.get) {
            true
        } else {
            val f = tryAsk(GetStatus)
            handleStatusFuture(f)
            akka.event.EventHandler.info(actor, "isShutdown() method end, outerShutdown=" + outerShutdown.get)
            outerShutdown.get
        }
    }

    override def isTerminated: Boolean = {
        akka.event.EventHandler.info(actor, "isTerminated() method, outerTerminated=" + outerTerminated.get)
        if (outerTerminated.get) {
            true
        } else {
            val f = tryAsk(GetStatus)
            handleStatusFuture(f)
            akka.event.EventHandler.info(actor, "isTerminated() method end, outerTerminated=" + outerTerminated.get)
            outerTerminated.get
        }
    }

    override def shutdown = {
        if (outerShutdown.get) {
            // nothing to do
        } else {
            val f = tryAsk(Shutdown)
            handleStatusFuture(f)
            require(outerShutdown.get)
        }
    }

    override def shutdownNow: java.util.List[Runnable] = {
        // If we send a message, it won't shutdown "now",
        // it will shutdown after the executor actor drains
        // a potentially long queue including Execute requests.
        // So we have this shared state boolean to let us tell
        // the actor to start canceling any runnables it hasn't
        // run yet, including those in its queue.
        cancelRequested.set(true)

        // shutdown sends a Shutdown message and waits for it to
        // return, so we should know the queue was drained when
        // this returns
        shutdown

        // Now we have to wait for termination so that any tasks
        // that will self-cancel when they dispatch have a chance
        // to do so
        awaitTerminationWithCanceled(20, TimeUnit.SECONDS)._2.asJava
    }
}
