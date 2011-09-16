package com.typesafe.webwords.common

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
        executor.shutdownNow()
        executor.isShutdown() should be(true)
        executor.awaitTermination(60, TimeUnit.SECONDS)
        executor.isTerminated() should be(true)

        val numberDone = tasks.foldLeft(0)({ (sofar, t) =>
            if (t.done)
                sofar + 1
            else
                sofar
        })
        // No real guarantees here, shutdownNow may have
        // managed to cancel some of them but it doesn't
        // have to.
        numberDone should not be (numberOfTasks)
    }
}
