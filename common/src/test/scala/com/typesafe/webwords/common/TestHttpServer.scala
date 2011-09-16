package com.typesafe.webwords.common

import java.net.URL
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.net.URI
import java.util.Random

class TestHttpServer {
    private var maybeServer: Option[Server] = None

    // this bool is just to detect bugs, it's not thread-safe
    private var latencyPushed = false
    // this is max latency (we pick a random amount up to this)
    private var latencyMs: Int = 0

    private val random = new Random()
    private def randomInRange(min: Int, max: Int) = {
        random.nextInt(max - min + 1) + min
    }

    private class TestHandler extends AbstractHandler {
        override def handle(target: String, jettyRequest: Request, servletRequest: HttpServletRequest, response: HttpServletResponse) = {
            if (latencyMs > 0) {
                Thread.sleep(randomInRange((latencyMs * 0.1).intValue, latencyMs))
            }

            target match {
                case "/hello" => {
                    response.setContentType("text/plain")
                    response.setStatus(HttpServletResponse.SC_OK)
                    response.getWriter().println("Hello")
                    jettyRequest.setHandled(true)
                }
                case "/echo" => {
                    val what = servletRequest.getParameter("what")
                    if (what != null) {
                        response.setContentType("text/plain")
                        response.setStatus(HttpServletResponse.SC_OK)
                        response.getWriter().print(what)
                    } else {
                        response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
                    }
                    jettyRequest.setHandled(true)
                }
                case _ =>
            }
        }
    }

    def start(): Unit = {
        if (maybeServer.isDefined)
            throw new IllegalStateException("can't start http server twice")

        import scala.collection.JavaConverters._

        val handler = new TestHandler

        val server = new Server(0)
        server.setHandler(handler)
        server.start()

        maybeServer = Some(server)
    }

    def stop() = {
        maybeServer foreach { server =>
            server.stop()
        }
        maybeServer = None
    }

    def url: URL = {
        maybeServer map { server =>
            val ports = for (c <- server.getConnectors()) yield c.getLocalPort()
            val port = ports.head
            new URL("http://127.0.0.1:" + port + "/")
        } get
    }

    def resolve(path: String): URL = {
        url.toURI.resolve(path).toURL
    }

    def resolve(path: String, params: String*): URL = {
        val uri = url.toURI.resolve(path)
        val pairs = params.sliding(2) map { p =>
            require(p.length == 2)
            java.net.URLEncoder.encode(p(0), "utf-8") + "=" +
                java.net.URLEncoder.encode(p(1), "utf-8")
        }
        val query = pairs.mkString("", "&", "")
        val uriWithQuery = new URI(uri.getScheme(),
            uri.getAuthority(),
            uri.getPath(),
            query,
            uri.getFragment())
        uriWithQuery.toURL
    }

    def withRandomLatency[T](maxMs: Int)(body: => T): T = {
        require(!latencyPushed)
        val old = latencyMs
        latencyPushed = true // just to detect bugs
        latencyMs = maxMs
        try {
            body
        } finally {
            latencyMs = old
            latencyPushed = false
        }
    }
}
