package com.typesafe.webwords.common

case class WebWordsConfig(amqpURL: Option[String], mongoURL: Option[String], port: Option[Int])

object WebWordsConfig {
    def apply(): WebWordsConfig = {
        val amqpURL = Option(System.getenv("RABBITMQ_URL"))
        val mongoURL = Option(System.getenv("MONGOHQ_URL"))
        val port = Option(System.getenv("PORT")) map { s => Integer.parseInt(s) }
        val config = WebWordsConfig(amqpURL, mongoURL, port)
        // FIXME remove this or use a proper logger
        println("Configuration is: " + config)
        config
    }
}
