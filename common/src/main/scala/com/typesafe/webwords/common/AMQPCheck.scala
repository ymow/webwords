package com.typesafe.webwords.common

import com.rabbitmq.client._

/**
 * This uses the plain Java com.rabbitmq API to be sure we can connect to the
 * AMQP broker. It's a lot easier to debug "failed to start AMQP" with this
 * than with akka-aqmp which fires up multiple connections and repeatedly
 * tries to reconnect - it ends up pretty noisy if the AMQP server is down.
 *
 * As a side effect, this is a nice illustration of using an unmodified
 * Java API from Scala. You could certainly just use this directly
 * rather than the akka-aqmp wrapper, if you wanted to.
 */
object AMQPCheck {
    private def stack(exc: Throwable): Unit = {
        println(exc.getStackTraceString)
        println("AMQP not working: " + exc.getClass.getSimpleName + ": " + exc.getMessage)
        if (exc.getCause != null)
            stack(exc.getCause)
        else
            println(" (throwable has no further getCause)")
    }

    def check(config: WebWordsConfig, sleepBeforeCloseMs: Long): Boolean = {
        try {
            val factory = new ConnectionFactory()
            val params = AbstractWorkQueueActor.parseAmqpUrl(config.amqpURL.getOrElse("amqp:///"))
            factory.setHost(params.addresses(0).getHost)
            factory.setPort(params.addresses(0).getPort)
            factory.setUsername(params.username)
            factory.setPassword(params.password)
            factory.setVirtualHost(params.virtualHost)

            val connection = factory.newConnection()
            val channel = connection.createChannel()

            val queueName = "webwords_check_queue"

            channel.queueDeclare(queueName, false, false, false, null)
            val message = "Hello World!"
            channel.basicPublish("", queueName, null, message.getBytes())

            Thread.sleep(sleepBeforeCloseMs)

            channel.close()
            connection.close()
            true
        } catch {
            case e: Throwable =>
                stack(e)
                false
        }
    }
}
