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
        val tasks = for (i <- 1 to 200)
            yield new TestTask(i)
        tasks foreach { t =>
            t.done should be(false)
            executor.execute(t)
        }
        // stop new tasks from being submitted
        executor.shutdownNow()
        executor.isShutdown() should be(true)
        executor.awaitTermination(60, TimeUnit.SECONDS)
        executor.isTerminated() should be(true)

        tasks foreach { t =>
            t.done should be(true)
        }
    }
}
