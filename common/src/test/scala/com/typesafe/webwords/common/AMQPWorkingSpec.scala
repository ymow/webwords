package com.typesafe.webwords.common

import org.scalatest.matchers._
import org.scalatest._

class AMQPWorkingSpec extends FlatSpec with ShouldMatchers {
    // This test is basically just here so people will understand what's wrong
    // if they haven't started up RabbitMQ
    it should "be possible to connect to the AMQP broker" in {
        AMQPCheck.check(WebWordsConfig(), 0) should be(true)
    }
}
