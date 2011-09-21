package com.typesafe.webwords.common

import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import akka.actor._
import akka.amqp
import akka.amqp.AMQP
import akka.amqp.AMQP.ConnectionParameters
import akka.amqp.rpc.RPC
import com.rabbitmq.client.Address

sealed trait WorkQueueMessage {
    self: Product =>

    private[common] def toBinary: Array[Byte] = {
        val fields = this.productIterator map { _.toString }
        WorkQueueMessage.packed(this.getClass.getSimpleName :: fields.toList)
    }
}

object WorkQueueMessage {
    private def stringPack(args: Traversable[String]): String = {
        val encoded = for (a <- args)
            yield URLEncoder.encode(a, "UTF-8")
        encoded.mkString("", ":", "")
    }

    private def stringUnpack(s: String): Traversable[String] = {
        val encoded = s.split(":")
        for (e <- encoded)
            yield URLDecoder.decode(e, "UTF-8")
    }

    private[common] def unpacked(bytes: Array[Byte]): Traversable[String] = {
        stringUnpack(new String(bytes, "UTF-8"))
    }

    private[common] def packed(args: Traversable[String]): Array[Byte] = {
        stringPack(args).getBytes("UTF-8")
    }
}

sealed trait WorkQueueRequest extends WorkQueueMessage {
    self: Product =>
}
case class SpiderAndCache(url: String) extends WorkQueueRequest

object WorkQueueRequest {
    private[common] val toBinary = new AMQP.ToBinary[WorkQueueRequest] {
        override def toBinary(request: WorkQueueRequest) = request.toBinary
    }

    private[common] val fromBinary = new AMQP.FromBinary[WorkQueueRequest] {
        override def fromBinary(bytes: Array[Byte]) = {
            WorkQueueMessage.unpacked(bytes).toList match {
                case "SpiderAndCache" :: url :: Nil =>
                    SpiderAndCache(url)
                case whatever =>
                    throw new Exception("Bad message: " + whatever)
            }
        }
    }
}

sealed trait WorkQueueReply extends WorkQueueMessage {
    self: Product =>
}
case class SpideredAndCached(url: String) extends WorkQueueReply

object WorkQueueReply {
    private[common] val toBinary = new AMQP.ToBinary[WorkQueueReply] {
        override def toBinary(reply: WorkQueueReply) = reply.toBinary
    }

    private[common] val fromBinary = new AMQP.FromBinary[WorkQueueReply] {
        override def fromBinary(bytes: Array[Byte]) = {
            WorkQueueMessage.unpacked(bytes).toList match {
                case "SpideredAndCached" :: url :: Nil =>
                    SpideredAndCached(url)
                case whatever =>
                    throw new Exception("Bad message: " + whatever)
            }
        }
    }
}

/**
 * We use AMQP to run a work queue between the web frontend and worker
 * processes.
 * To understand AMQP a good resource is:
 * http://www.rabbitmq.com/tutorials/amqp-concepts.html
 */
abstract class AbstractWorkQueueActor(amqpUrl: Option[String])
    extends Actor {
    protected[this] val info = akka.event.EventHandler.info(this, _: String)

    private[this] var connectionActor: Option[ActorRef] = None

    override def receive = {

        // Messages from the connection ("connection callback")
        case amqp.Connected => info("Connected to AMQP")
        case amqp.Reconnecting => info("Reconnecting to AMQP")
        case amqp.Disconnected => info("Disconnected from AMQP")
    }

    protected def createRpc(connection: ActorRef): Unit
    protected def destroyRpc: Unit

    protected val rpcExchangeName = "webwords_rpc"

    override def preStart = {
        val params = AbstractWorkQueueActor.parseAmqpUrl(amqpUrl.getOrElse(AbstractWorkQueueActor.DEFAULT_AMQP_URL))
        connectionActor = Some(AMQP.newConnection(params.copy(connectionCallback = Some(self))))
        createRpc(connectionActor.get)
    }

    override def postStop = {
        destroyRpc
        connectionActor foreach { _.stop }
        connectionActor = None
    }
}

object AbstractWorkQueueActor {
    private val DEFAULT_AMQP_URL = "amqp:///"

    // surely the rabbitmq library or something has this somewhere but I can't find it.
    private[common] def parseAmqpUrl(url: String): ConnectionParameters = {
        // Example: amqp://uname:pwd@host:13029/vhost

        val defaults = ConnectionParameters() // hack to get at the default values
        val defaultAddress = defaults.addresses(0)

        val uri = new URI(url)

        val host = Option(uri.getHost).getOrElse(defaultAddress.getHost)
        val port = if (uri.getPort == -1) defaultAddress.getPort else uri.getPort
        val address = new Address(host, port)

        val vhost = Option(uri.getPath).getOrElse(defaults.virtualHost)

        val userInfo = Option(uri.getUserInfo)
        val (user, password) = userInfo map { ui =>
            if (ui.contains(":")) {
                val a = ui.split(":", 2)
                (a(0) -> a(1))
            } else {
                (ui -> defaults.password)
            }
        } getOrElse (defaults.username -> defaults.password)

        val params = ConnectionParameters(addresses = Array(address),
            username = user,
            password = password,
            virtualHost = vhost)
        // FIXME remove this debug logging
        println("amqp params=" + params)
        println("amqp params addresses=" + address)
        println("amqp params address.host" + address.getHost)
        println("amqp params address.port" + address.getPort)
        params
    }
}
