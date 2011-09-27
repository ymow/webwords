package com.typesafe.webwords.common

import com.rabbitmq.client._
import java.io.IOException

/**
 * This uses the plain Java com.rabbitmq API to be sure we can connect to the
 * AMQP broker. It's a lot easier to debug "failed to start AMQP" with this
 * than with akka-aqmp which fires up multiple connections and repeatedly
 * tries to reconnect - it ends up pretty noisy if the AMQP server is down.
 *
 * Another purpose of this is to let the web process block until the indexer
 * process has started up its queue.
 *
 * As a side effect, this is a nice illustration of using an unmodified
 * Java API from Scala. You could certainly just use this directly
 * rather than the akka-aqmp wrapper, if you wanted to.
 */
object AMQPCheck {

    def check(config: WebWordsConfig, queueToWaitFor: Option[String] = None, timeoutMs: Long = 10000): Boolean = {
        try {
            val factory = new ConnectionFactory()
            val params = AbstractWorkQueueActor.parseAmqpUrl(config.amqpURL.getOrElse("amqp:///"))
            factory.setHost(params.addresses(0).getHost)
            factory.setPort(params.addresses(0).getPort)
            factory.setUsername(params.username)
            factory.setPassword(params.password)
            factory.setVirtualHost(params.virtualHost)

            val connection = factory.newConnection()
            try {
                val channel = connection.createChannel()
                try {
                    // First create a queue just to be sure we can
                    val queueName = "webwords_check"
                    channel.queueDeclare(queueName, false /* durable */ , false /* exclusive */ , true /* autodelete */ , null)
                    val message = "Hello World!"
                    channel.basicPublish("", queueName, null, message.getBytes())
                    // get the message back out or the queue will keep it around forever
                    channel.basicGet(queueName, true /* autoAck */ )

                    // Now if requested, wait up to a few minutes for a desired queue to exist
                    queueToWaitFor foreach { queue =>
                        waitForQueue(connection, queue, timeoutMs)
                    }

                } finally {
                    ignoreCloseException { channel.close() }
                }
            } finally {
                ignoreCloseException { connection.close() }
            }

            true
        } catch {
            case e: IOException =>
                stack(e)
                false
        }
    }

    private def waitForQueue(connection: Connection, name: String, timeoutMs: Long): Unit = {
        if (timeoutMs <= 0)
            throw new IOException("Timed out waiting for AMQP queue '" + name + "' to exist")

        // when queueDeclarePassive() fails to find the queue, it
        // auto-closes the channel instead of just throwing an exception.
        // a bit tricky.
        // so we create a new channel each time.
        val exists = try {
            val channel = connection.createChannel()
            try {
                channel.queueDeclarePassive(name)
            } finally {
                ignoreCloseException { channel.close() }
            }

            true
        } catch {
            case e: IOException =>
                if (!connection.isOpen)
                    throw new IOException("Tried to declare passive queue, but connection was closed", e)
                false
        }

        if (exists) {
            println("AMQP queue '" + name + "' found")
        } else {
            println("AMQP queue '" + name + "' not yet available, waiting another " + (timeoutMs / 1000) + "s")
            val pollTime = 2000
            Thread.sleep(pollTime)
            waitForQueue(connection, name, timeoutMs - pollTime)
        }
    }

    private def stack(exc: Throwable): Unit = {
        println(exc.getStackTraceString)
        println("AMQP not working: " + exc.getClass.getSimpleName + ": " + exc.getMessage)
        if (exc.getCause != null)
            stack(exc.getCause)
        else
            println(" (throwable has no further getCause)")
    }

    private def ignoreCloseException(body: => Unit): Unit = {
        try {
            body
        } catch {
            case e: IOException =>
            case e: AlreadyClosedException =>
        }
    }

}
