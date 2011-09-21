package com.typesafe.webwords.web

import java.net.URL
import java.net.MalformedURLException
import akka.actor._
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.eclipse.jetty.util.IO
import com.typesafe.webwords.common._

class WebServer(config: WebWordsConfig) {
    private val client = Actor.actorOf(new ClientActor(config))
    private var maybeServer: Option[Server] = None

    private class WebHandler extends AbstractHandler {
        override def handle(target: String, jettyRequest: Request, servletRequest: HttpServletRequest, response: HttpServletResponse) = {
            target match {
                case "/words" => {
                    val url = Option(servletRequest.getParameter("url")) flatMap { string =>
                        try {
                            Some(new URL(string))
                        } catch {
                            case e: MalformedURLException =>
                                None
                        }
                    }
                    if (url.isDefined) {
                        val futureIndexOption = client ? GetIndex(url.get.toExternalForm) map { reply =>
                            reply match {
                                case GotIndex(url, indexOption) =>
                                    indexOption
                            }
                        }

                        // block for prototype purposes, we'll switch to something better later
                        val maybeIndex = futureIndexOption.get

                        maybeIndex match {
                            case Some(index) =>
                                response.setContentType("text/plain")
                                response.setCharacterEncoding("UTF-8")
                                response.setStatus(HttpServletResponse.SC_OK)
                                val writer = response.getWriter()
                                writer.println("Word Counts")
                                writer.println("=====")
                                for ((word, count) <- index.wordCounts) {
                                    writer.println(word + "\t\t" + count)
                                }
                                writer.println("Links")
                                writer.println("=====")
                                for ((text, url) <- index.links) {
                                    writer.println(text + "\t\t" + url)
                                }
                            case None =>
                                response.setContentType("text/plain")
                                response.setStatus(HttpServletResponse.SC_OK)
                                response.getWriter().println("Failed to index url in time")
                        }
                    } else {
                        response.setContentType("text/plain")
                        response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
                        response.getWriter().println("Invalid or missing url parameter")
                    }
                    jettyRequest.setHandled(true)
                }
                case "/hello" => {
                    response.setContentType("text/plain")
                    response.setStatus(HttpServletResponse.SC_OK)
                    response.getWriter().println("Hello")
                    jettyRequest.setHandled(true)
                }
                case _ =>
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND)
                    jettyRequest.setHandled(true)
            }
        }
    }

    def start(): Unit = {
        if (maybeServer.isDefined)
            throw new IllegalStateException("can't start http server twice")

        client.start

        val handler = new WebHandler

        val server = new Server(config.port.getOrElse(8080))
        server.setHandler(handler)
        server.start()

        maybeServer = Some(server)
    }

    def run(): Unit = {
        maybeServer foreach { _.join() }
    }

    def stop() = {
        maybeServer foreach { server =>
            server.stop()
        }
        maybeServer = None
        client.stop
    }
}
