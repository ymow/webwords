package com.typesafe.webwords.indexer

import scala.collection.JavaConverters._
import akka.actor._
import com.typesafe.webwords.common.CPUBoundActorPool
import java.net.URL
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import scala.collection.parallel.ParSeq

class Index(val url: URL,
    val links: Seq[(String, String)],
    val wordCounts: Seq[(String, Int)])

class IndexerActor
    extends Actor
    with CPUBoundActorPool {

    sealed trait IndexerRequest
    case class IndexHtml(url: URL, doc: String) extends IndexerRequest

    sealed trait IndexerReply
    case class IndexedHtml(index: Index) extends IndexerReply

    override def instance = Actor.actorOf(new Worker())

    override def receive = _route

    private class Worker extends Actor {
        private def links(doc: Document) = {
            val as = doc.select("a").asScala
            val builder = Map.newBuilder[String, String]
            for (a <- as) {
                val href = a.attr("abs:href")
                val text = a.text
                if (href.nonEmpty && text.nonEmpty)
                    builder += (text -> href)
            }
            builder.result.toSeq.sortBy(_._1)
        }

        private def wordCounts(doc: Document) = {
            val body = doc.select("body").first
            // splitWords creates a parallel collection so this is multithreaded!
            val words = IndexerActor.splitWords(body.text)
            IndexerActor.wordCount(words).toSeq.sortBy(_._2) take 20
        }

        override def receive = {
            case request: IndexerRequest => request match {
                case IndexHtml(url, docString) =>
                    val doc = Jsoup.parse(docString, url.toExternalForm)
                    val index = new Index(url, links(doc), wordCounts(doc))
                    self.tryReply(IndexedHtml(index))
            }
        }
    }
}

object IndexerActor {
    private val notWordRegex = """\W""".r

    // this is in the companion object for ease of unit testing
    private[indexer] def splitWords(s: String): ParSeq[String] = {
        val lines = s.split("\\n").toSeq.par
        val words = lines flatMap { line =>
            notWordRegex.split(line) filter { _.nonEmpty }
        }
        words
    }

    // this is in the companion object for ease of unit testing
    private[indexer] def wordCount(words: ParSeq[String]) = {
        words.foldLeft(Map.empty[String, Int])({ (sofar, word) =>
            sofar.get(word) match {
                case Some(old) =>
                    sofar + (word -> (old + 1))
                case None =>
                    sofar + (word -> 1)
            }
        })
    }
}
