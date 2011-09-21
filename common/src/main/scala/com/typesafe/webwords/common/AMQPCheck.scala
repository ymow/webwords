package com.typesafe.webwords.common

import com.rabbitmq.client._

object AMQPCheck {
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
                println(e.getStackTraceString)
                println("AMQP not working: " + e.getClass.getSimpleName + ": " + e.getMessage)
                false
        }
    }
}
