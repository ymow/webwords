package com.typesafe.webwords.web

import akka.actor._
import akka.http._
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.servlet.ServletContextHandler
import com.typesafe.webwords.common._

/**
 * This class manually embeds Jetty (see http://wiki.eclipse.org/Jetty/Tutorial/Embedding_Jetty)
 * and forwards requests from it to Akka HTTP. There are many other ways to set things up,
 * for example you could use the Akka Microkernel, or use the standard Jetty distribution's
 * prebuilt servlet container. Whatever you are used to. See also:
 *   http://akka.io/docs/akka/1.2/scala/http.html
 *
 * You don't have to use Akka HTTP either of course, you could just write a subclass of
 * AbstractHandler and handle requests yourself. One advantage of Akka HTTP is that it
 * automatically suspends the Jetty requests so they are truly async (they don't tie
 * up a thread).
 */
class WebServer(config: WebWordsConfig) {
    // to use Akka HTTP, we need a RootEndpoint which is an actor that
    // comes with Akka
    private val rootEndpoint = Actor.actorOf(new RootEndpoint)
    // we register this bootstrap actor with the RootEndpoint, and have
    // it dispatch requests for us
    private val bootstrap = Actor.actorOf(new WebBootstrap(rootEndpoint, config))

    private var maybeServer: Option[Server] = None

    def start(): Unit = {
        if (maybeServer.isDefined)
            throw new IllegalStateException("can't start http server twice")

        rootEndpoint.start
        bootstrap.start

        val server = new Server(config.port.getOrElse(8080))

        // here we pull in the servlet container; if we didn't want to use AkkaMistServlet,
        // we could just subclass AbstractHandler and skip this dependency.
        val handler = new ServletContextHandler(ServletContextHandler.SESSIONS)
        handler.setContextPath("/")
        // AkkaMistServlet forwards requests to the rootEndpoint which
        // in turn forwards them to our bootstrap actor.
        handler.addServlet(new ServletHolder(new AkkaMistServlet()), "/*");

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

        bootstrap.stop
        rootEndpoint.stop
    }
}
