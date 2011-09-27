package com.typesafe.webwords.common

/**
 * This is the information we scrape about a URL.
 * Links found on the site, and word counts of words on the site.
 */
case class Index(links: Seq[(String, String)],
    wordCounts: Seq[(String, Int)]) {

    // the default toString for a case class would recursively show
    // the entire lists, which is not convenient. so override.
    override def toString = {
        "Index(" + links.size + " links," + wordCounts.size + " counts)"
    }
}
