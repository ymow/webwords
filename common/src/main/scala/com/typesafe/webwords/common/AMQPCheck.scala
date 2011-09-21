package com.typesafe.webwords.common

import com.rabbitmq.client._

object AMQPCheck {
    private def stripSlash(s: String) =
        if (s.startsWith("/")) s.substring(1) else s

    private def stack(exc: Throwable): Unit = {
        println(exc.getStackTraceString)
        println("AMQP not working: " + exc.getClass.getSimpleName + ": " + exc.getMessage)
        if (exc.getCause != null)
            stack(exc.getCause)
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

            val connection = try {
                factory.newConnection()
            } catch {
                case e: Throwable =>
                    stack(e)
                    println("AMQP failed with virtual host '" + params.virtualHost + "' trying with no /")
                    factory.setVirtualHost(stripSlash(params.virtualHost))
                    factory.newConnection()
            }
            val channel = connection.createChannel()

            val QUEUE_NAME = "test_queue_checking"

            channel.queueDeclare(QUEUE_NAME, false, false, false, null)
            val message = "Hello World!"
            channel.basicPublish("", QUEUE_NAME, null, message.getBytes())

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
