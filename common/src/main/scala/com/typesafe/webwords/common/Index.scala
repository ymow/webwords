package com.typesafe.webwords.common

import java.net.URL

class Index(val url: URL,
    val links: Seq[(String, String)],
    val wordCounts: Seq[(String, Int)]) {
    override def toString = {
        "Index(" + url + "," + links.size + " links," + wordCounts.size + " counts)"
    }
}

