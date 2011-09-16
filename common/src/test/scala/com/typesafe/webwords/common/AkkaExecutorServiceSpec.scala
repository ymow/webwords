package com.typesafe.webwords.common

import scala.collection.JavaConverters._
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import java.util.concurrent.TimeUnit

class AkkaExecutorServiceSpec extends FlatSpec with ShouldMatchers {
    behavior of "AkkaExecutorService"

    private class TestTask(val id: Int) extends Runnable {
        private val _done = new AtomicBoolean(false)
        def done = _done.get()
        override def run() = {
            Thread.sleep(50)
            _done.set(true)
        }
    }

    it should "run tasks and shut down" in {
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

    it should "support shutdownNow" in {
        val executor = new AkkaExecutorService()
        // need lots of tasks because we're testing that
        // we cancel them before we run all of them
        val numberOfTasks = 2000
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
