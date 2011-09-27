package com.typesafe.webwords.common

import org.scalatest.matchers._
import org.scalatest._
import akka.actor._
import java.net.URL

class IndexStorageActorSpec extends FlatSpec with ShouldMatchers {
    private val sampleIndex = Index(
        links = Seq(
            "dogs" -> "http://dogs.com/",
            "cats" -> "http://cats.com/"),
        wordCounts = Seq(
            "hello" -> 10,
            "world" -> 5,
            "quick" -> 4,
            "brown" -> 3))
    private val anotherIndex = Index(
        links = Seq(
            "pigs" -> "http://pigs.com/",
            "cows" -> "http://cows.com/"),
        wordCounts = Seq(
            "hello" -> 7,
            "world" -> 1,
            "quick" -> 4,
            "brown" -> 2))
    private val emptyIndex = Index(Nil, Nil)
    private val exampleUrl = new URL("http://example.com/")
    private val exampleUrl2 = new URL("http://example2.com/")

    private def newActor = Actor.actorOf(new IndexStorageActor(Some("mongodb://localhost/webwordstest"))).start

    behavior of "IndexStorageActor"

    private def cacheIndex(storage: ActorRef, url: URL, index: Index) = {
        storage ! CacheIndex(url.toExternalForm, index)
    }

    private def fetchIndex(storage: ActorRef, url: URL): Index = {
        (storage ? FetchCachedIndex(url.toExternalForm)).get match {
            case CachedIndexFetched(Some(index)) =>
                index
            case whatever =>
                throw new Exception("failed to get index, got: " + whatever)
        }
    }

    private def cacheSize(storage: ActorRef): Long = {
        (storage ? GetCacheSize).get match {
            case CacheSize(x) => x
            case whatever =>
                throw new Exception("failed to get cache size, got: " + whatever)
        }
    }

    it should "drop the cache in case of leftovers" in {
        val storage = newActor
        storage ! DropCache
        cacheSize(storage) should be(0)
        storage.stop
    }

    it should "store and retrieve an index" in {
        val storage = newActor
        cacheIndex(storage, exampleUrl, sampleIndex)
        val fetched = fetchIndex(storage, exampleUrl)
        fetched should be(sampleIndex)
        storage.stop
    }

    it should "store and retrieve an empty index" in {
        val storage = newActor
        cacheIndex(storage, exampleUrl2, emptyIndex)
        val fetched = fetchIndex(storage, exampleUrl2)
        fetched should be(emptyIndex)
        storage.stop
    }

    it should "use the newest entry" in {
        val storage = newActor
        // check we have leftovers from previous test
        val fetched = fetchIndex(storage, exampleUrl)
        fetched should be(sampleIndex)
        // now replace the leftovers
        cacheIndex(storage, exampleUrl, anotherIndex)
        val newIndex = fetchIndex(storage, exampleUrl)
        newIndex should be(anotherIndex)
        storage.stop
    }

    it should "drop the cache" in {
        val storage = newActor
        // check we have leftovers from a previous test
        val fetched = fetchIndex(storage, exampleUrl)
        fetched should be(anotherIndex)
        storage ! DropCache
        cacheSize(storage) should be(0)
        storage.stop
    }
}
