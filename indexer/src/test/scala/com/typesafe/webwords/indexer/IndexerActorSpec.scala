package com.typesafe.webwords.indexer

import org.scalatest.matchers._
import org.scalatest._

class IndexerActorSpec extends FlatSpec with ShouldMatchers {
    behavior of "splitWords"

    it should "split one word" in {
        val split = IndexerActor.splitWords("foo").toList
        split should be(List("foo"))
    }

    it should "split two words" in {
        val split = IndexerActor.splitWords("foo bar").toList
        split should be(List("foo", "bar"))
    }

    it should "split words with multiline text" in {
        val split = IndexerActor.splitWords("foo bar\nbaz boo\nhello world\n").toList
        split should be(List("foo", "bar", "baz", "boo", "hello", "world"))
    }

    it should "split a realistic sentence with punctuation" in {
        val split = IndexerActor.splitWords("Hello, this is (some sort of) sentence.").toList
        split should be(List("Hello", "this", "is", "some", "sort", "of", "sentence"))
    }

    behavior of "wordCount"

    private def wordCount(s: String) = {
        IndexerActor.wordCount(IndexerActor.splitWords(s))
    }

    it should "count one word" in {
        val counts = wordCount("foo")
        counts should be(Map("foo" -> 1))
    }

    it should "count a couple words" in {
        val counts = wordCount("foo bar foo")
        counts.get("foo") should be(Some(2))
        counts.get("bar") should be(Some(1))
        counts should have size 2
    }
}
