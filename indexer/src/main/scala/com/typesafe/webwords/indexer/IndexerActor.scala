package com.typesafe.webwords.indexer

import scala.collection.JavaConverters._
import akka.actor.{ Index => _, _ }
import akka.actor.Actor.actorOf
import com.typesafe.webwords.common.CPUBoundActorPool
import java.net.URL
import java.net.URI
import java.net.URISyntaxException
import java.net.MalformedURLException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import scala.collection.parallel._
import com.typesafe.webwords.common.Index

sealed trait IndexerRequest
case class IndexHtml(url: URL, doc: String) extends IndexerRequest

sealed trait IndexerReply
case class IndexedHtml(index: Index) extends IndexerReply

/**
 * IndexerActor is a CPU-bound actor which parses HTML with the jsoup
 * library and scrapes some data out of it. The code in here illustrates
 * algorithmic code in Scala, in a functional style, including use of
 * parallel collections.
 */
class IndexerActor
    extends Actor
    with CPUBoundActorPool {

    // actorOf[Worker] doesn't work on nested classes
    override def instance = actorOf(new Worker)

    override def receive = _route

    private class Worker extends Actor {
        import IndexerActor._

        private def links(doc: Document) = {
            val as = doc.select("a").asScala
            val builder = Map.newBuilder[String, String]
            for (a <- as) {
                val text = a.text
                val href = try {
                    // be paranoid here and we don't have to worry about it
                    // anywhere else in the code.
                    val maybeInvalid = a.attr("abs:href")
                    if (maybeInvalid.isEmpty)
                        throw new URISyntaxException(maybeInvalid, "empty URI")
                    new URI(maybeInvalid)
                    new URL(maybeInvalid)
                    maybeInvalid
                } catch {
                    case e: URISyntaxException =>
                        ""
                    case e: MalformedURLException =>
                        ""
                }

                if (href.nonEmpty && text.nonEmpty)
                    builder += (text -> href)
            }
            builder.result.toSeq.sortBy(_._1)
        }

        private def wordCounts(doc: Document) = {
            val body = doc.select("body").first
            // splitWords creates a parallel collection so this is multithreaded!
            // in a real app you'd want to profile and see if this makes sense;
            // it may well not depending on workload, number of cores, etc.
            // but it's interesting to see how to do it.
            val words = splitWords(body.text) filter { !boring(_) }
            wordCount(words).toSeq.sortBy(0 - _._2) take 50
        }

        override def receive = {
            case request: IndexerRequest => request match {
                case IndexHtml(url, docString) =>
                    val doc = Jsoup.parse(docString, url.toExternalForm)
                    val index = Index(links(doc), wordCounts(doc))
                    self.tryReply(IndexedHtml(index))
            }
        }
    }
}

object IndexerActor {
    private val notWordRegex = """\W""".r

    private[indexer] def splitWords(s: String): ParSeq[String] = {
        // ".par" is the magic that gives us a parallel algorithm
        val lines = s.split("\\n").toSeq.par
        val words = lines flatMap { line =>
            notWordRegex.split(line) filter { w => w.nonEmpty }
        }
        words
    }

    private[indexer] def mergeCounts(a: Map[String, Int], b: Map[String, Int]): Map[String, Int] = {
        val builder = Map.newBuilder[String, Int]
        val (intersection, notInB) = a partition { kv => b.contains(kv._1) }
        val notInA = b filter { kv => !a.contains(kv._1) }
        for ((key, value) <- intersection.iterator) {
            builder += (key -> (value + b.get(key).get))
        }
        builder ++= notInA
        builder ++= notInB
        builder.result
    }

    private[indexer] def wordCount(words: ParSeq[String]) = {
        // using foldLeft avoids the need for mergeCounts,
        // but foldLeft is inherently sequential.
        // You'd have to benchmark to see which is faster.

        words.aggregate(Map.empty[String, Int])({ (sofar, word) =>
            sofar.get(word) match {
                case Some(old) =>
                    sofar + (word -> (old + 1))
                case None =>
                    sofar + (word -> 1)
            }
        }, mergeCounts)
    }

    // not very scientific or internationalized ;-)
    private val boringEnglishWords = Set(
        "a",
        "also",
        "an",
        "and",
        "are",
        "as",
        "at",
        "be",
        "been",
        "by",
        "can",
        "for",
        "from",
        "has",
        "have",
        "in",
        "it",
        "is",
        "may",
        "not",
        "of",
        "on",
        "or",
        "such",
        "that",
        "the",
        "this",
        "to",
        "was",
        "which",
        "with")
    private[indexer] def boring(word: String) = {
        // no single letters or super-high-frequency words
        word.length == 1 ||
            boringEnglishWords.contains(word.toLowerCase)
    }
}
