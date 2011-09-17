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
 *
 * Also, this whole thing is just an optimization to
 * reduce the number of threads in the app, which probably
 * is not even important. You could use Executors.newCachedThreadPool
 * instead...
 */
class AkkaExecutorService(implicit val dispatcher: MessageDispatcher) extends AbstractExecutorService {
    private final val log = akka.event.EventHandler

    // requests
    private sealed trait ExecutorRequest
    private case class Execute(command: Runnable) extends ExecutorRequest
    private case object Shutdown extends ExecutorRequest
    private case class AwaitTermination(timeoutInMs: Long) extends ExecutorRequest
    private case object ShutdownNow extends ExecutorRequest
    private case object GetStatus extends ExecutorRequest

    // we send these two to ourselves
    private case class Completed(runnable: Runnable, canceled: Boolean) extends ExecutorRequest
    private case object MaybeDie extends ExecutorRequest

    // replies
    private sealed trait ExecutorReply
    private case class Status(shutdown: Boolean, terminated: Boolean) extends ExecutorReply
    private case class TerminationAwaited(status: Status, runnables: Seq[Runnable]) extends ExecutorReply

    private case class Task(future: Future[Completed], runnable: Runnable)

    private class ExecutorActor(cancelRequested: AtomicBoolean) extends Actor {
        // Tasks in flight
        private var pending: Map[Runnable, Task] = Map.empty
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
            pending += (task.runnable -> task)
        }

        private def removePending(task: Task) = {
            pending -= task.runnable
        }

        private def findPending(runnable: Runnable) = pending.get(runnable)

        override def receive = {
            case request: ExecutorRequest =>
                logRequest(request)

                request match {
                    case MaybeDie =>
                        if (isTerminated) {
                            stopActorNotifyingMailbox(self)
                        }
                    case Execute(runnable) =>
                        require(!shutdown) // it isn't allowed to send Execute after Shutdown
                        if (cancelRequested.get) {
                            canceled = canceled.enqueue(runnable)
                        } else {
                            val f = Future[Completed]({
                                val c = if (cancelRequested.get) {
                                    Completed(runnable, true)
                                } else {
                                    runnable.run()
                                    Completed(runnable, false)
                                }
                                // we both send ourselves the Completed as a notification,
                                // and store it in the future for later use
                                self ! c
                                c
                            },
                                // Infinite timeout is needed to match expected ExecutorService semantics
                                Int.MaxValue)
                            val task = Task(f, runnable)
                            addPending(task)
                        }
                    case Completed(runnable, wasCanceled) =>
                        val task = findPending(runnable).get
                        removePending(task)
                        if (wasCanceled) {
                            canceled = canceled.enqueue(runnable)
                        }
                        if (shutdown && pending.isEmpty) {
                            notifyOnTerminated foreach { l =>
                                log.debug(self, " sending a terminated notification")
                                l.complete(Right(terminationAwaitedReply))
                            }
                            notifyOnTerminated = Nil
                            // queue killing the actor
                            self ! MaybeDie
                        }
                    case GetStatus =>
                        self.tryReply(Status(shutdown, isTerminated))
                    case Shutdown =>
                        shutdown = true
                        self.tryReply(Status(shutdown, isTerminated))
                    case ShutdownNow =>
                        shutdown = true
                        awaitTermination(20 * 1000)
                    case AwaitTermination(inMs) =>
                        log.debug(self, "got AwaitTermination pending=" + pending.size)
                        awaitTermination(inMs)
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
            for (task <- pending.values) {
                task.future.await(Duration(remainingTimeMs, TimeUnit.MILLISECONDS))

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
            if (isTerminated) {
                log.debug(self, "Already terminated, sending status")
                self.tryReply(terminationAwaitedReply)
                // schedule death
                self ! MaybeDie
            } else {
                log.debug(self, "Will notify of termination later, pending: " + pending.size)
                val f = new DefaultCompletableFuture[TerminationAwaited]()
                notifyOnTerminated = f :: notifyOnTerminated
                self.channel.replyWith(f)
            }
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

    private def tryAskWithTimeout(message: Any, timeoutMs: Long = Actor.defaultTimeout.duration.toMillis): CompletableFuture[Any] = {
        tryAsk(actor, message)(NullChannel, new Actor.Timeout(Duration(timeoutMs, TimeUnit.MILLISECONDS)))
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

    private def askStatus = handleStatusFuture(tryAsk(actor, GetStatus))

    override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
        log.debug(actor, "outer awaitTermination() method")

        if (!rejecting.get)
            throw new IllegalStateException("Have to shutdown() before you awaitTermination()")

        log.debug(actor, "sending AwaitTermination")

        val timeoutMs = unit.toMillis(timeout)
        val f = tryAskWithTimeout(AwaitTermination(timeoutMs), timeoutMs)
        val statusFuture = f map { v =>
            v match {
                case TerminationAwaited(status, canceled) =>
                    status
            }
        }

        // block on a reply to see if we're terminated
        handleStatusFuture(statusFuture, Some(Duration(timeout, unit))).terminated
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
        log.debug(actor, "shutdownNow() method")

        rejecting.set(true)

        // If we send a message, it won't shutdown "now",
        // it will shutdown after the executor actor drains
        // a potentially long queue including Execute requests.
        // So we have this shared state boolean to let us tell
        // the actor to start canceling any runnables it hasn't
        // run yet, including those in its queue.
        cancelRequested.set(true)

        val f = tryAskWithTimeout(ShutdownNow, 20 * 1000)
        val statusFuture = f map { v =>
            v match {
                case TerminationAwaited(status, canceled) =>
                    status
            }
        }

        // block on a reply to see if we're terminated
        val status = handleStatusFuture(statusFuture)

        // extract list of canceled runnables from the reply
        val canceled = if (f.result.isDefined) {
            f.result.get match {
                case TerminationAwaited(status, canceled) =>
                    canceled
            }
        } else {
            Nil
        }
        canceled.asJava
    }
}
