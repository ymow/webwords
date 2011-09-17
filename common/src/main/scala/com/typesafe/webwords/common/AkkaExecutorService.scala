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
import akka.dispatch.MessageQueue
import akka.dispatch.MessageInvocation

/**
 * The idea here is to use Akka's thread pool to run Runnables,
 * rather than creating a new separate one. An ExecutorService
 * implementation is delegated to an ExecutorActor
 * which in turn has a pool of actors used to run tasks.
 *
 * We could also cheat and create a class in the Akka package that
 * accesses private field ExecutorBasedEventDrivenDispatcher.executorService,
 * but that seems kind of evil, though now that I see this file is so
 * large maybe it was a better idea. There is a problem with that approach
 * which is no way to awaitTermination().
 *
 * There is a ticket to solve this upstream:
 * https://www.assembla.com/spaces/akka/tickets/1208
 * so this whole file could get deleted later on.
 */
class AkkaExecutorService(implicit val dispatcher: MessageDispatcher) extends AbstractExecutorService {
    private final val log = akka.event.EventHandler

    // requests
    private sealed trait ExecutorRequest
    private case class Execute(command: Runnable) extends ExecutorRequest
    private case object Shutdown extends ExecutorRequest
    private case class AwaitTermination(timeoutInMs: Long) extends ExecutorRequest
    private case object GetStatus extends ExecutorRequest
    private case class Completed(task: Task, canceled: Boolean) extends ExecutorRequest
    private case object KeepAlive extends ExecutorRequest
    private case object AllowDeath extends ExecutorRequest
    private case object MaybeDie extends ExecutorRequest

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
        private var keepAlives = 0
        // Tasks in flight
        private var pending: Set[Task] = Set.empty
        // are we shut down
        private var shutdown = false
        // futures to complete when we are terminated
        private var notifyOnTerminated: List[CompletableFuture[TerminationAwaited]] = Nil
        // runnables that we canceled with shutdownNow
        private var canceled = Queue.empty[Runnable]

        private var completedCountToLog = 0
        private var executeCountToLog = 0
        private def logRequest(request: ExecutorRequest) = {
            request match {
                case c: Completed =>
                    completedCountToLog += 1
                case e: Execute =>
                    executeCountToLog += 1
                case _ =>
                    if (completedCountToLog > 0) {
                        log.debug(self, "  request=Completed*" + completedCountToLog)
                        completedCountToLog = 0
                    }
                    if (executeCountToLog > 0) {
                        log.debug(self, "  request=Execute*" + executeCountToLog)
                        executeCountToLog = 0
                    }
                    log.debug(self, "  request=" + request)
            }
        }

        private def addPending(task: Task) = {
            require(!shutdown)
            pending += task
        }

        private def removePending(task: Task) = {
            pending -= task
        }

        override def receive = {
            case request: ExecutorRequest =>
                logRequest(request)

                request match {
                    case KeepAlive =>
                        keepAlives += 1
                    case AllowDeath =>
                        keepAlives -= 1
                        self ! MaybeDie
                    case MaybeDie =>
                        if (keepAlives == 0 && isTerminated) {
                            stopActorNotifyingMailbox(self)
                        }
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
                                log.debug(self, " sending a terminated notification")
                                l.complete(Right(terminationAwaitedReply))
                            }
                            notifyOnTerminated = Nil
                            // queue killing the actor if nobody has us kept alive.
                            // there may still be messages we need to handle.
                            self ! MaybeDie
                        }
                    case GetStatus =>
                        self.tryReply(Status(shutdown, isTerminated))
                    case Shutdown =>
                        shutdown = true
                        log.debug(self, "processed Shutdown, pending=" + pending.size)
                        self.tryReply(Status(shutdown, isTerminated))
                    case AwaitTermination(inMs) =>
                        log.debug(self, "got AwaitTermination pending=" + pending.size)
                        awaitTermination(inMs)
                        if (isTerminated) {
                            log.debug(self, "Already terminated, sending status")
                            self.tryReply(terminationAwaitedReply)
                        } else {
                            log.debug(self, "Will notify of termination later, pending: " + pending.size)
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
            log.debug(self, "awaitTermination pending=" + pending.size)
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
            log.debug(self, "Starting up executor actor")
        }
        override def postStop = {
            log.debug(self, "Shutting down executor actor")
            require(notifyOnTerminated.isEmpty)
            require(pending.isEmpty)
        }
    }

    private val cancelRequested = new AtomicBoolean(false)
    private val actor = Actor.actorOf(new ExecutorActor(cancelRequested)).start
    private val rejecting = new AtomicBoolean(false)

    private def tryAsk(message: Any): CompletableFuture[Any] = {
        // "?" will throw by default on a stopped actor; we want to put an exception
        // in the future instead to avoid special cases
        try {
            log.debug(actor, "Sending to actor with isRunning=" + actor.isRunning + " message=" + message)
            actor ? message
        } catch {
            case e: ActorInitializationException =>
                log.debug(actor, "actor was not running, send failed: " + message)
                val f = new DefaultCompletableFuture[Any]()
                f.completeWithException(new FutureTimeoutException("Actor was not running, immediate timeout"))
                f
        }
    }

    override def execute(command: Runnable): Unit = {
        if (rejecting.get)
            throw new RejectedExecutionException("Executor service has been shut down")
        actor ! Execute(command)
    }

    private def handleStatusFuture(f: Future[Any], duration: Option[Duration] = None): Status = {
        val start = System.currentTimeMillis()
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
                    log.debug(actor, "status=" + status)
                    status
            }
        } catch {
            case e: Throwable =>
                log.debug(actor, "status future threw, actor.isRunning=" + actor.isRunning)
                if (actor.isRunning) {
                    Status(rejecting.get, false)
                } else {
                    // if actor isn't running, we're as shutdown and terminated as we're getting
                    Status(true, true)
                }
        } finally {
            val end = System.currentTimeMillis()
            if ((end - start) > 2) {
                log.debug(actor, "****** waiting for status took " + (end - start) + "ms")
            }
        }
    }

    private def askStatus = handleStatusFuture(tryAsk(GetStatus))

    // this lets us do a series of requests without fear of
    // the actor stopping itself in between them. It is still
    // possible that the actor will be stopped for all of them.
    private def withActorAlive[T](ifStopped: T)(body: => T): T = {
        actor.tryTell(KeepAlive)
        try {
            if (actor.isRunning) {
                body
            } else {
                ifStopped
            }
        } finally {
            actor.tryTell(AllowDeath)
        }
    }

    private def awaitTerminationWithCanceled(timeout: Long, unit: TimeUnit): (Boolean, Seq[Runnable]) = {
        log.debug(actor, "awaitTerminationWithCanceled() method")

        if (!rejecting.get)
            throw new IllegalStateException("Have to shutdown() before you awaitTermination()")

        log.debug(actor, "sending AwaitTermination")

        val f = tryAsk(AwaitTermination(unit.toMillis(timeout)))
        val statusFuture = f map { v =>
            v match {
                case TerminationAwaited(status, canceled) =>
                    status
            }
        }

        // block on a reply to see if we're terminated
        val status = handleStatusFuture(statusFuture, Some(Duration(timeout, unit)))

        // extract list of canceled runnables from the reply
        val canceled = if (f.result.isDefined) {
            f.result.get match {
                case TerminationAwaited(status, canceled) =>
                    canceled
            }
        } else {
            Nil
        }

        (status.terminated, canceled)
    }

    override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
        log.debug(actor, "outer awaitTermination() method")
        awaitTerminationWithCanceled(timeout, unit)._1
    }

    override def isShutdown: Boolean = {
        log.debug(actor, "isShutdown() method, rejecting=" + rejecting.get)
        rejecting.get
    }

    override def isTerminated: Boolean = {
        log.debug(actor, "isTerminated() method")
        rejecting.get && askStatus.terminated
    }

    override def shutdown = {
        if (!rejecting.getAndSet(true)) {
            actor.tryTell(Shutdown)
        }
    }

    override def shutdownNow: java.util.List[Runnable] = {
        val needShutdownMessage = !rejecting.getAndSet(true)

        // If we send a message, it won't shutdown "now",
        // it will shutdown after the executor actor drains
        // a potentially long queue including Execute requests.
        // So we have this shared state boolean to let us tell
        // the actor to start canceling any runnables it hasn't
        // run yet, including those in its queue.
        cancelRequested.set(true)

        withActorAlive(java.util.Collections.emptyList[Runnable]) {
            if (needShutdownMessage) {
                actor.tryTell(Shutdown)
            }

            // the actor may still have a bunch of runnables in its queue,
            // or other messages. There may also be tasks that the
            // actor has already spawned, that will self-cancel and
            // then report themselves as canceled back to the actor.
            // We need to wait for all this stuff to wind down.

            awaitTerminationWithCanceled(20, TimeUnit.SECONDS)._2.asJava
        }
    }
}
