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

        val defaultParams = ConnectionParameters() // hack to get at the default values
        val defaultAddress = defaultParams.addresses(0)
        val defaults = URIParts(scheme = "amqp", user = Some(defaultParams.username),
            password = Some(defaultParams.password), host = Some(defaultAddress.getHost), port = Some(defaultAddress.getPort),
            path = Some(defaultParams.virtualHost))

        val parts = expandURI(url, defaults).getOrElse(throw new Exception("Bad AMQP URI: " + url))

        val address = new Address(parts.host.get, parts.port.get)

        // the default vhost is "/" but otherwise things explode if you start with "/"
        val vhost = if (parts.path.get == "") "/" else parts.path.get

        val params = ConnectionParameters(addresses = Array(address),
            username = parts.user.get,
            password = parts.password.get,
            virtualHost = vhost)

        params
    }
}
