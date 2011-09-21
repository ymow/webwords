package com.typesafe.webwords.web

import java.net.URL
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.net.URI
import org.eclipse.jetty.util.IO
import com.typesafe.webwords.common._

class WebServer(config: WebWordsConfig) {
    private var maybeServer: Option[Server] = None

    private class WebHandler extends AbstractHandler {
        override def handle(target: String, jettyRequest: Request, servletRequest: HttpServletRequest, response: HttpServletResponse) = {
            target match {
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

        import scala.collection.JavaConverters._

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
    }
}
