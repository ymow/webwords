package com.typesafe.webwords.common

import scala.collection.JavaConverters._
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

class AkkaExecutorServiceSpec extends FlatSpec with ShouldMatchers {
    behavior of "AkkaExecutorService"

    private class HighWaterMark {
        private val current = new AtomicInteger(0)
        private val max = new AtomicInteger(0)

        def highWater = max.get

        def increment: Unit = {
            val v = current.incrementAndGet
            setMax(v)
        }

        // ensure _high is >= v
        private def setMax(v: Int): Unit = {
            val old = max.get
            if (old < v) {
                if (!max.compareAndSet(old, v))
                    setMax(v)
            }
            assert(max.get >= v)
        }

        def decrement: Unit = {
            current.decrementAndGet
        }

        def reset: Unit = {
            current.set(0)
            max.set(0)
        }
    }

    private val liveTasks = new HighWaterMark

    private class TestTask(val id: Int, val sleepTimeMs: Long = 15) extends Runnable {
        private val _done = new AtomicBoolean(false)
        def done = _done.get()
        override def run() = {
            try {
                liveTasks.increment
                // simulate taking some time
                Thread.sleep(sleepTimeMs)
                _done.set(true)
            } finally {
                liveTasks.decrement
            }
        }
        override def toString = "TestTask(" + id + ")"
    }

    private def repeat(n: Int)(body: => Unit) = {
        for (i <- 1 to n) {
            body
        }
    }

    it should "run tasks and shut down" in {
        repeat(5) {
            val executor = new AkkaExecutorService()
            val tasks = for (i <- 1 to 200)
                yield new TestTask(i)
            tasks foreach { t =>
                t.done should be(false)
                executor.execute(t)
            }
            // stop new tasks from being submitted
            executor.shutdown()
            executor.isShutdown() should be(true)
            executor.awaitTermination(60, TimeUnit.SECONDS)
            executor.isTerminated() should be(true)

            tasks foreach { t =>
                t.done should be(true)
            }
        }
    }

    it should "support shutdownNow" in {
        repeat(5) {
            val executor = new AkkaExecutorService()
            // need lots of tasks because we're testing that
            // we cancel them before we run all of them
            val numberOfTasks = 1000
            val tasks = for (i <- 1 to numberOfTasks)
                yield new TestTask(i)
            tasks foreach { t =>
                t.done should be(false)
                executor.execute(t)
            }
            // stop new tasks from being submitted and
            // cancel existing ones when possible
            val notRun = executor.shutdownNow().asScala
            executor.isShutdown() should be(true)
            executor.awaitTermination(60, TimeUnit.SECONDS)
            executor.isTerminated() should be(true)

            val numberRun = tasks.foldLeft(0)({ (sofar, t) =>
                if (t.done)
                    sofar + 1
                else
                    sofar
            })

            val numberNotRun = notRun.size

            // a little song and dance to get nice output on failure
            def formatEquation(x: Int, y: Int, z: Int) =
                "%d+%d=%d".format(x, y, z)
            val expected = formatEquation(numberRun, numberOfTasks - numberRun, numberOfTasks)
            formatEquation(numberRun, numberNotRun, numberOfTasks) should be(expected)

            // this is not strictly guaranteed but we should make numberOfTasks
            // high enough that it always happens in the test or else we aren't
            // getting good coverage.
            numberNotRun should not be (0)
        }
    }

    it should "reject tasks after shutdown" in {
        val executor = new AkkaExecutorService()
        val tasks = for (i <- 1 to 10)
            yield new TestTask(i)
        tasks foreach { t =>
            t.done should be(false)
            executor.execute(t)
        }
        // stop new tasks from being submitted
        executor.shutdown()

        val reject = new TestTask(11)
        evaluating {
            executor.execute(reject)
        } should produce[RejectedExecutionException]

        executor.awaitTermination(60, TimeUnit.SECONDS)
        executor.isTerminated() should be(true)

        tasks foreach { t =>
            t.done should be(true)
        }

        reject.done should be(false)
    }

    private def countThreadsUsed(maxThreads: Int) = {
        liveTasks.reset

        val executor = new AkkaExecutorService(maxThreads)
        val tasks = for (i <- 1 to 200)
            yield new TestTask(i, 4) // only 4ms so this doesn't take forever
        tasks foreach { t =>
            t.done should be(false)
            executor.execute(t)
        }
        // stop new tasks from being submitted
        executor.shutdown()
        executor.isShutdown() should be(true)
        executor.awaitTermination(60, TimeUnit.SECONDS)
        executor.isTerminated() should be(true)

        tasks foreach { t =>
            t.done should be(true)
        }

        liveTasks.highWater
    }

    it should "obey the requested max threads" in {
        // note that a max over the Akka dispatcher's max won't work
        val n2 = countThreadsUsed(2)
        val n4 = countThreadsUsed(4)
        val n7 = countThreadsUsed(7)
        n2 should be(2)
        n4 should be(4)
        n7 should be(7)
    }

    // this test sort of inherently takes forever, unfortunately
    it should "wait for tasks that take longer than Akka timeout" in {
        val executor = new AkkaExecutorService()
        val longerTimeout = Actor.defaultTimeout.duration.toMillis + 5000
        val tasks = for (i <- 1 to 5)
            yield new TestTask(i, longerTimeout)
        tasks foreach { t =>
            t.done should be(false)
            executor.execute(t)
        }

        executor.shutdown()
        executor.isShutdown() should be(true)
        // we're testing that when awaitTermination returns, the
        // tasks are done.
        executor.awaitTermination(60, TimeUnit.SECONDS)
        executor.isTerminated() should be(true)

        tasks foreach { t =>
            t.done should be(true)
        }
    }
}
