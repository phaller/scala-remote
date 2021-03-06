/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */


package scala.actors
package remote

import java.io.InputStream

import scala.collection.mutable.{ HashMap, Queue }

private[remote] object ConnectionStatus {
  val Handshaking = 0x1
  val Established = 0x2
  val Terminated  = 0x3
}

private[remote] class DefaultMessageConnection(
                               byteConn: ByteConnection, 
                               serializer: Serializer,
                               override val receiveCallback: MessageReceiveCallback,
                               isServer: Boolean,
                               override val config: Configuration)
  extends MessageConnection {

  import ConnectionStatus._

  // setup termination chain
  byteConn.beforeTerminate { isBottom => doTerminate(isBottom) }

  override def localNode  = byteConn.localNode
  override def remoteNode = byteConn.remoteNode

  override def isEphemeral = byteConn.isEphemeral

  override def doTerminateImpl(isBottom: Boolean) {
    Debug.info(this + ": doTerminateImpl(" + isBottom + ")")
    if (!isBottom && !sendQueue.isEmpty) {
      Debug.info(this + ": waiting 5 seconds for sendQueue to drain")
      terminateLock.wait(5000)
    }
    byteConn.doTerminate(isBottom)
    status = Terminated
  }

  override def mode             = byteConn.mode
  override def activeSerializer = serializer
  override def toString         = "<DefaultMessageConnection using: " + byteConn + ">"
    
  @volatile private var _status = 0
  def status = _status
  private def status_=(newStatus: Int) { _status = newStatus }

  @inline private def isHandshaking = status == Handshaking
  @inline private def isEstablished = status == Established

  override val connectFuture = byteConn.connectFuture

  override val handshakeFuture = new BlockingFuture

  /**
   * Messages which need to be sent out (in order), but could not have been at
   * the time send() was called (due to things like not finishing handshake
   * yet, etc)
   */
  private val sendQueue = new Queue[(Serializer => ByteSequence, Option[RFuture])]
  private val primitiveSerializer = new PrimitiveSerializer

  private val callbackFtch = new ErrorCallbackFuture((e: Throwable) => {
    handshakeFuture.finishWithError(e)
  })

  private val EmptyArray = new Array[Byte](0)

  // bootstrap in CTOR
  bootstrap()

  // WARNING: do not place any variable declarations after this point, because
  // bootstrap runs in the ctor, so any decls below will not be initialized at
  // that point

  assert(status != 0)

  private def bootstrap() {
    require(serializer ne null)

    if (serializer.isHandshaking) {
      // if serializer requires handshake, place in handshake mode...
      status = Handshaking

      // ... and initialize it
      handleNextEvent(StartEvent(remoteNode))
    } else {
      // otherwise, in established mode (ready to send messages)
      status = Established 

      handshakeFuture.finishSuccessfully()
    }
  }


  private def handleNextEvent(evt: ReceivableEvent) {
    assert(isHandshaking)
    def sendIfNecessary(m: TriggerableEvent) {
      (m match {
        case SendEvent(msgs @ _*)             => msgs
        case SendWithSuccessEvent(msgs @ _*)  => msgs 
        case SendWithErrorEvent(_, msgs @ _*) => msgs
        case _ => Seq()
      }) foreach { msg =>
        //Debug.info(this + ": nextHandshakeMessage: " + msg.asInstanceOf[AnyRef])
        val os = new PreallocatedHeaderByteArrayOutputStream(4, primitiveSerializer.sizeOf(msg) + 4)
        primitiveSerializer.serialize(msg, os)
        //Debug.info(this + ": sending in handshake: data: " + java.util.Arrays.toString(data))
        byteConn.send(os.toDiscardableByteSeq, Some(callbackFtch))
      }
    }
    serializer.handleNextEvent(evt).foreach { evt =>
      sendIfNecessary(evt)
      evt match {
        case SendEvent(_*) =>
        case SendWithSuccessEvent(_*) | Success =>
          // done
          status = Established

          Debug.info(this + ": handshake completed")
          handshakeFuture.finishSuccessfully()

          if (!sendQueue.isEmpty) {
            sendQueue.foreach { case (msg, ftch) =>
              Debug.info(this + ": sending " + msg + " from sendQueue")
              byteConn.send(msg(serializer), ftch)
            }
            sendQueue.clear()
            terminateLock.notifyAll()
          }

        case SendWithErrorEvent(reason, _*) =>
          val ex = new IllegalHandshakeStateException(reason)
          handshakeFuture.finishWithError(ex)
          throw ex
        case Error(reason) =>
          val ex = new IllegalHandshakeStateException(reason)
          handshakeFuture.finishWithError(ex)
          throw ex
      }
    }
  }

  def receive(bytes: InputStream) {
    assert(status != 0)
    //Debug.info(this + ": received " + bytes.length + " bytes")
    if (isHandshaking) {
      val msg = primitiveSerializer.deserialize(bytes)
      //Debug.info(this + ": receive() - nextPrimitiveMessage(): " + msg)
      terminateLock.synchronized { 
        if (terminateInitiated) return
        handleNextEvent(RecvEvent(msg))
      }
    } else if (isEstablished) {
      val nextMsg = serializer.read(bytes)
      //Debug.info(this + ": calling receiveMessage with " + nextMsg)
      receiveMessage(serializer, nextMsg)
    } else 
      Debug.error(this + ": received %d bytes but no action can be taken".format(bytes.available))
  }

  override def send(ftch: Option[RFuture])(msg: Serializer => ByteSequence) {
    ensureAlive()
    if (isHandshaking) {
      val repeat = withoutTermination {
        status match {
          case Terminated =>
            throw new IllegalStateException("Cannot send on terminated channel")
          case Handshaking =>
            //Debug.info(this + ": send() - queuing up msg")
            sendQueue += ((msg, ftch)) // queue it up
            false // no need to repeat
          case Established =>
            // we connected somewhere in between checking and grabbing
            // termination lock. we don't want to queue it up then. try again
            true
        }
      }
      if (repeat) 
        send(ftch)(msg)
    } else 
      // call send immediately
      byteConn.send(msg(activeSerializer), ftch)
  }

}

private[remote] class StandardService extends Service {

  override def serviceProviderFor(mode: ServiceMode.Value) = mode match {
    case ServiceMode.NonBlocking => new NonBlockingServiceProvider
    case ServiceMode.Blocking    => new BlockingServiceProvider
  }

  private val recvCall0 = (conn: ByteConnection, inputStream: InputStream) => {
    conn.attachment_!.asInstanceOf[DefaultMessageConnection].receive(inputStream)
  }

  override def connect(node: Node, 
                       config: Configuration,
                       recvCallback: MessageReceiveCallback): MessageConnection = {
    val byteConn = serviceProviderFor0(config.selectMode).connect(node, recvCall0)
    val msgConn = new DefaultMessageConnection(byteConn, config.newSerializer(), recvCallback, false, config)
    byteConn.attach(msgConn)
    msgConn
  }

  override def listen(port: Int, 
                      config: Configuration,
                      connCallback: ConnectionCallback[MessageConnection], 
                      recvCallback: MessageReceiveCallback): Listener = {
    val byteConnCallback = (listener: Listener, byteConn: ByteConnection) => {
      val msgConn = new DefaultMessageConnection(byteConn, config.newSerializer(), recvCallback, true, config)
      byteConn.attach(msgConn)
  		connCallback(listener, msgConn)	
    }
    serviceProviderFor0(config.aliveMode).listen(port, byteConnCallback, recvCall0)
  }

}
