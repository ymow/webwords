package com.typesafe.webwords.common

final class Index(val links: Seq[(String, String)],
    val wordCounts: Seq[(String, Int)]) {
    override def toString = {
        "Index(" + links.size + " links," + wordCounts.size + " counts)"
    }

    override def equals(other: Any): Boolean = {
        other match {
            case that: Index => {
                // no canEqual since we're final
                links == that.links &&
                    wordCounts == that.wordCounts
            }
            case _ => false
        }
    }

    override def hashCode: Int = {
        41 * (41 + links.hashCode) + wordCounts.hashCode
    }
}
