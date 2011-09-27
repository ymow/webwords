package com.typesafe.webwords.common

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._

class IndexSpec extends FlatSpec with ShouldMatchers {
    private val sampleIndex = Index(
        links = Seq(
            "dogs" -> "http://dogs.com/",
            "cats" -> "http://cats.com/"),
        wordCounts = Seq(
            "hello" -> 10,
            "world" -> 5,
            "quick" -> 4,
            "brown" -> 3))
    private val copyOfSampleIndex = Index(links = sampleIndex.links, wordCounts = sampleIndex.wordCounts)
    private val sampleIndexDifferentOrder = Index(
        links = Seq(
            "cats" -> "http://cats.com/",
            "dogs" -> "http://dogs.com/"),
        wordCounts = Seq(
            "world" -> 5,
            "hello" -> 10,
            "brown" -> 3,
            "quick" -> 4))
    private val emptyIndex = Index(Nil, Nil)

    behavior of "Index"

    it should "have working equals and hashCode" in {
        sampleIndex should be(sampleIndex)
        sampleIndex.hashCode should be(sampleIndex.hashCode)
        sampleIndex should be(copyOfSampleIndex)
        sampleIndex.hashCode should be(copyOfSampleIndex.hashCode)
        emptyIndex should be(emptyIndex)
        emptyIndex.hashCode should be(emptyIndex.hashCode)

        sampleIndex should not be (new Object)

        sampleIndex should not be (sampleIndexDifferentOrder)
        sampleIndex.hashCode should not be (sampleIndexDifferentOrder.hashCode)
        sampleIndex should not be (emptyIndex)
        sampleIndex.hashCode should not be (emptyIndex.hashCode)
    }
}
