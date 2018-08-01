package com.apex.network

import java.net.InetSocketAddress
import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props, SupervisorStrategy}
import akka.io.Tcp
import akka.io.Tcp._
import akka.util.{ByteString, CompactByteString}
import com.apex.common.ApexLogging
import com.google.common.primitives.Ints
import com.apex.core.app.Version
import com.apex.network.PeerConnectionHandler.{AwaitingHandshake, WorkingCycle}
import com.apex.network.message.{Message, MessageHandler}
import com.apex.network.message.MessageSpec
import com.apex.core.settings.NetworkSettings
import com.apex.core.utils.NetworkTimeProvider
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}


sealed trait ConnectionType
case object Incoming extends ConnectionType
case object Outgoing extends ConnectionType


case class ConnectedPeer(socketAddress: InetSocketAddress,
                         handlerRef: ActorRef,
                         direction: ConnectionType,
                         handshake: Handshake) {

  import shapeless.syntax.typeable._

  def publicPeer: Boolean = handshake.declaredAddress.contains(socketAddress)

  override def hashCode(): Int = socketAddress.hashCode()

  override def equals(obj: Any): Boolean =
    obj.cast[ConnectedPeer].exists(p => p.socketAddress == this.socketAddress && p.direction == this.direction)

  override def toString: String = s"ConnectedPeer($socketAddress)"
}


case object Ack extends Event


class PeerConnectionHandler(val settings: NetworkSettings,
                            networkControllerRef: ActorRef,
                            peerManagerRef: ActorRef,
                            messagesHandler: MessageHandler,
                            connection: ActorRef,
                            direction: ConnectionType,
                            ownSocketAddress: Option[InetSocketAddress],
                            remote: InetSocketAddress,
                            timeProvider: NetworkTimeProvider)(implicit ec: ExecutionContext)
  extends Actor with DataBuffering with ApexLogging {

  import PeerConnectionHandler.ReceivableMessages._
  import com.apex.network.peer.PeerManager.ReceivableMessages.{AddToBlacklist, Disconnected, DoConnecting, Handshaked}

  context watch connection

  override val supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

  //接收握手
  private var receivedHandshake: Option[Handshake] = None
  private var selfPeer: Option[ConnectedPeer] = None

  private def handshakeGot = receivedHandshake.isDefined

  private var handshakeSent = false
  
  //握手超时取消选择
  private var handshakeTimeoutCancellableOpt: Option[Cancellable] = None

  private var chunksBuffer: ByteString = CompactByteString()


  private def handshake: Receive =
    startInteraction orElse
      receivedData orElse
      handshakeTimeout orElse
      handshakeDone orElse
      processErrors(AwaitingHandshake.toString)

  private def processErrors(stateName: String): Receive = {
    case CommandFailed(w: Write) =>
      log.warn(s"写入失败 :$w " + remote + s" 状态 $stateName")
      connection ! Close
      connection ! ResumeReading
      connection ! ResumeWriting

    case cc: ConnectionClosed =>
      peerManagerRef ! Disconnected(remote)
      log.info("连接关闭 : " + remote + ": " + cc.getErrorCause + s" 状态  $stateName")
      context stop self

    case CloseConnection =>
      log.info(s"强制中止通信: " + remote + s" 状态  $stateName")
      connection ! Close

    case CommandFailed(cmd: Tcp.Command) =>
      log.info("执行命令失败 : " + cmd + s" 状态 $stateName")
      connection ! ResumeReading
  }

  private def startInteraction: Receive = {
    case StartInteraction =>
      val hb = Handshake(settings.agentName,
        Version(settings.appVersion), settings.nodeName,
        ownSocketAddress, timeProvider.time()).bytes

      connection ! Tcp.Write(ByteString(hb))
      log.info(s"发送握手到: $remote")
      handshakeSent = true
      if (handshakeGot && handshakeSent) self ! HandshakeDone
  }

  private def receivedData: Receive = {
    case Received(data) =>
      HandshakeSerializer.parseBytes(data.toArray) match {
        case Success(handshake) =>
          receivedHandshake = Some(handshake)
          log.info(s"获得握手: $remote")
          connection ! ResumeReading
          if (handshakeGot && handshakeSent) self ! HandshakeDone
        case Failure(t) =>
          log.info(s"Error during parsing a handshake", t)
          self ! CloseConnection
      }
  }

  private def handshakeTimeout: Receive = {
    case HandshakeTimeout =>
      log.info(s"与远程 $remote 握手超时, 将删除连接")
      self ! CloseConnection
  }

  private def handshakeDone: Receive = {
    case HandshakeDone =>
      require(receivedHandshake.isDefined)

      @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
      val peer = ConnectedPeer(remote, self, direction, receivedHandshake.get)
      selfPeer = Some(peer)

      peerManagerRef ! Handshaked(peer)
      handshakeTimeoutCancellableOpt.map(_.cancel())
      connection ! ResumeReading
      context become workingCycle
  }

  def workingCycleLocalInterface: Receive = {
    case msg: message.Message[_] =>
      def sendOutMessage() {
        val bytes = msg.bytes
        log.info("发送消息 " + msg.spec + " 到 " + remote)
        connection ! Write(ByteString(Ints.toByteArray(bytes.length) ++ bytes))
      }

      //模拟网络时延
      settings.addedMaxDelay match {
        case Some(delay) =>
          context.system.scheduler.scheduleOnce(Random.nextInt(delay.toMillis.toInt).millis)(sendOutMessage())
        case None =>
          sendOutMessage()
      }

    case Blacklist =>
      log.info(s"加入黑名单 " + remote)
      peerManagerRef ! AddToBlacklist(remote)
      connection ! Close
  }

  def workingCycleRemoteInterface: Receive = {
    case Received(data) =>

      val t = getPacket(chunksBuffer ++ data)
      chunksBuffer = t._2

      t._1.find { packet =>
        messagesHandler.parseBytes(packet.toByteBuffer, selfPeer) match {
          case Success(message) =>
            log.info(" 从 " + remote + "收到消息 " + message.spec )
            networkControllerRef ! message
            false

          case Failure(e) =>
            log.info(s"损坏的数据来自: " + remote, e)
            true
        }
      }
      connection ! ResumeReading
  }

  private def reportStrangeInput: Receive= {
      case nonsense: Any =>
        log.warn(s"未知的错误: $nonsense")
  }

  def workingCycle: Receive =
    workingCycleLocalInterface orElse
      workingCycleRemoteInterface orElse
      processErrors(WorkingCycle.toString) orElse
      reportStrangeInput

  override def preStart: Unit = {
    peerManagerRef ! DoConnecting(remote, direction)
    handshakeTimeoutCancellableOpt = Some(context.system.scheduler.scheduleOnce(settings.handshakeTimeout)
    (self ! HandshakeTimeout))
    connection ! Register(self, keepOpenOnPeerClosed = false, useResumeWriting = true)
    connection ! ResumeReading
  }

  override def receive: Receive = handshake

  override def postStop(): Unit = log.info(s"Peer handler to $remote destroyed")
}

object PeerConnectionHandler {


  sealed trait CommunicationState
  case object AwaitingHandshake extends CommunicationState
  case object WorkingCycle extends CommunicationState

  object ReceivableMessages {
    private[PeerConnectionHandler] object HandshakeDone
    case object StartInteraction
    case object HandshakeTimeout
    case object CloseConnection
    case object Blacklist
  }
}

object PeerConnectionHandlerRef {
  def props(settings: NetworkSettings,
            networkControllerRef: ActorRef,
            peerManagerRef: ActorRef,
            messagesHandler: MessageHandler,
            connection: ActorRef,
            direction: ConnectionType,
            ownSocketAddress: Option[InetSocketAddress],
            remote: InetSocketAddress,
            timeProvider: NetworkTimeProvider)(implicit ec: ExecutionContext): Props =
    Props(new PeerConnectionHandler(settings, networkControllerRef, peerManagerRef, messagesHandler,
                                    connection, direction, ownSocketAddress, remote, timeProvider))

  def apply(settings: NetworkSettings,
            networkControllerRef: ActorRef,
            peerManagerRef: ActorRef,
            messagesHandler: MessageHandler,
            connection: ActorRef,
            direction: ConnectionType,
            ownSocketAddress: Option[InetSocketAddress],
            remote: InetSocketAddress,
            timeProvider: NetworkTimeProvider)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef =
    system.actorOf(props(settings, networkControllerRef, peerManagerRef, messagesHandler,
                         connection, direction, ownSocketAddress, remote, timeProvider))

  def apply(name: String,
            settings: NetworkSettings,
            networkControllerRef: ActorRef,
            peerManagerRef: ActorRef,
            messagesHandler: MessageHandler,
            connection: ActorRef,
            direction: ConnectionType,
            ownSocketAddress: Option[InetSocketAddress],
            remote: InetSocketAddress,
            timeProvider: NetworkTimeProvider)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef =
    system.actorOf(props(settings, networkControllerRef, peerManagerRef, messagesHandler,
                         connection, direction, ownSocketAddress, remote, timeProvider), name)
}
