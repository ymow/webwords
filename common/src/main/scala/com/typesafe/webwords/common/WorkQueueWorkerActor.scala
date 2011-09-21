package com.typesafe.webwords.common

import akka.actor._
import akka.dispatch.Future
import akka.amqp
import akka.amqp.AMQP
import akka.amqp.rpc.RPC

abstract class WorkQueueWorkerActor(url: Option[String] = None)
    extends AbstractWorkQueueActor(url) {

    private[this] var rpcServer: Option[RPC.RpcServerHandle] = None

    protected def handleRequest(request: WorkQueueRequest): Future[WorkQueueReply]

    override def receive = {
        case request: WorkQueueRequest =>
            self.channel.replyWith(handleRequest(request))

        case m =>
            super.receive.apply(m)
    }

    override def createRpc(connectionActor: ActorRef) = {
        val serializer =
            new RPC.RpcServerSerializer[WorkQueueRequest, WorkQueueReply](WorkQueueRequest.fromBinary, WorkQueueReply.toBinary)
        def requestHandler(request: WorkQueueRequest): WorkQueueReply = {
            // having to block here is not ideal
            // https://www.assembla.com/spaces/akka/tickets/1217
            (self ? request).as[WorkQueueReply].get
        }
        // the need for poolSize>1 is an artifact of having to block in requestHandler above 
        rpcServer = Some(RPC.newRpcServer(connectionActor, rpcExchangeName, serializer, requestHandler, poolSize = 8))
    }

    override def destroyRpc = {
        rpcServer foreach { _.stop }
        rpcServer = None
    }
}
