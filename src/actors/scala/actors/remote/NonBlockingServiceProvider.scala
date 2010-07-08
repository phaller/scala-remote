/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */



package scala.actors
package remote

import java.io.{ ByteArrayOutputStream,IOException }
import java.net.{ InetAddress, InetSocketAddress, Socket }
import java.nio.ByteBuffer
import java.nio.channels.{ SelectionKey, Selector, 
                           ServerSocketChannel, SocketChannel }
import java.nio.channels.spi.{ AbstractSelectableChannel, SelectorProvider }

import java.util.concurrent.{ Executors, LinkedBlockingQueue }
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.{ ArrayBuffer, HashMap, ListBuffer, Queue }

// receiving message state management 

sealed trait MessageStateEnum
/** In the middle of reading the message size header (4 bytes) */
case object ReadingSize extends MessageStateEnum
/** In the middle of reading the variable size message */
case object ReadingMessage extends MessageStateEnum

private class MessageState {
  private var _sizeBuffer = ByteBuffer.allocate(4) // cached version for reading sizes

  var buffer = _sizeBuffer 
  var state: MessageStateEnum = ReadingSize
  var messageSize  = 4
  val messageQueue = new Queue[Array[Byte]]

  def consume(bytes: Array[Byte]) {
    import scala.math.min
    var idx = 0
    while (idx < bytes.length) {
      val bytesToPut = min(bytes.length - idx, bytesLeftInState)
      buffer.put(bytes, idx, bytesToPut)     
      idx += bytesToPut
      if (isFull) {
        buffer.flip
        state match {
          case ReadingSize =>
            val size = buffer.getInt
            startReadingMessage(size)
          case ReadingMessage =>
            val msg = new Array[Byte](messageSize)
            buffer.get(msg)
            messageQueue += msg
            startReadingSize
        } 
      }
    }
  }

  def startReadingSize {
    _sizeBuffer.clear()
    buffer      = _sizeBuffer
    state       = ReadingSize
    messageSize = 4
  }

  def startReadingMessage(size: Int) {
    buffer      = ByteBuffer.allocate(size) 
    state       = ReadingMessage
    messageSize = size 
  }

  def reset {
    messageQueue.clear
    startReadingSize
  }

  /** The number of bytes left needed to read before a state change */
  def bytesLeftInState = messageSize - buffer.position

  /** IS the buffer full */
  def isFull = bytesLeftInState == 0

  def hasMessage = !messageQueue.isEmpty

  def nextMessage = {
    assert(hasMessage)
    messageQueue.dequeue()
  }


}

object InterestOpUtil {
    // for debug purposes
    sealed trait InterestOp {
      val op: Int
      def isInterested(_op: Int) = { (op & _op) != 0 }
    }

    case object OP_READ    extends InterestOp {
      val op = SelectionKey.OP_READ
    }

    case object OP_WRITE   extends InterestOp {
      val op = SelectionKey.OP_WRITE
    }

    case object OP_CONNECT extends InterestOp {
      val op = SelectionKey.OP_CONNECT
    }

    case object OP_ACCEPT  extends InterestOp {
      val op = SelectionKey.OP_ACCEPT
    }

    private val Ops = List(OP_READ, OP_WRITE, OP_CONNECT, OP_ACCEPT)

    def enumerateSet(ops: Int) = Ops.filter(_.isInterested(ops))
}

object NonBlockingServiceProvider {
  val NumSelectLoops = Runtime.getRuntime.availableProcessors * 4
}


class NonBlockingServiceProvider 
  extends ServiceProvider
  with    BytesReceiveCallbackAware
  with    ConnectionCallbackAware {

  import NonBlockingServiceProvider._

  override def mode = ServiceMode.NonBlocking

  private def nextSelectLoop() = {
    val num = selectLoopIdx.getAndIncrement()
    val idx = num % selectLoops.length
    selectLoops(idx)
  }

  private val executor = Executors.newCachedThreadPool()

  private val selectLoops = new Array[SelectorLoop](NumSelectLoops) 
  initializeSelectLoops()

  private def initializeSelectLoops() {
    var idx = 0
    while (idx < selectLoops.length) {
      val selectLoop = new SelectorLoop(idx)
      executor.execute(selectLoop) 
      selectLoops(idx) = selectLoop
      idx += 1
    }
  }

  private val selectLoopIdx = new AtomicInteger

  class SelectorLoop(id: Int) extends Runnable {

    private val selector = SelectorProvider.provider.openSelector

    // selector key registration management

    type Operation = Function0[Unit]

    class ChangeInterestOp(socket: SocketChannel, op: Int) extends Operation {
      def apply() {
        import InterestOpUtil._
        Debug.info("setting channel " + socket + " to be interested in: " + enumerateSet(op))
        socket.keyFor(selector).interestOps(op)
      }
    }

    class RegisterChannel(socket: AbstractSelectableChannel, 
                          op: Int, 
                          attachment: Option[AnyRef]) extends Operation {
      def apply() {
        socket.register(selector, op, attachment.getOrElse(null)) 
      }
    }

    private val operationQueue = new ArrayBuffer[Operation]

    private def addOperation(op: Operation) {
      operationQueue.synchronized {
        operationQueue += op
      }
      selector.wakeup()
    }

    class NonBlockingServiceConnection(
        socketChannel: SocketChannel,
        override val receiveCallback: BytesReceiveCallback)
      extends Connection 
      with    TerminateOnError 
      with    EncodingHelpers {

      private val so = socketChannel.socket

      // TODO: for correctness, will need to wait until the socket channel is
      // connected before allowing the following two values to be computed
      override lazy val remoteNode = Node(so.getInetAddress.getHostName,  so.getPort) 
      override lazy val localNode  = Node(so.getLocalAddress.getHostName, so.getLocalPort)

      private val messageState = new MessageState

      private val writeQueue = new Queue[Array[ByteBuffer]]

      private var isWriting = false

      override def mode = ServiceMode.NonBlocking

      override def doTerminate() {
        // cancel the key
        socketChannel.keyFor(selector).cancel()

        // close the socket
        socketChannel.close()
      }

      private val WriteChangeOp = new ChangeInterestOp(socketChannel, SelectionKey.OP_WRITE)
      private val ChangeToWriteMode = () => addOperation(WriteChangeOp)
      private val DoNothing = () => ()

      override def send(bytes: Array[Array[Byte]]) {
        val todo = writeQueue.synchronized {
          writeQueue += encodeToByteBuffers(bytes)
          if (!isWriting && socketChannel.isConnected) {
            isWriting = true
            ChangeToWriteMode
          } else DoNothing
        }
        todo()
      }

      def doRead(key: SelectionKey) {
        assert(!isWriting)
        readBuffer.clear()
        var totalBytesRead = 0
        val shouldTerminate = 
          try {
            var continue = true
            var cnt = 0
            while (continue) {
              cnt = socketChannel.read(readBuffer)
              if (cnt <= 0)
                continue = false
              else
                totalBytesRead += cnt
            }
            cnt == -1
          } catch {
            case e: IOException => 
              Debug.error(this + ": Exception " + e.getMessage + " when reading")
              Debug.doError { e.printStackTrace }
              true
          }

        Debug.info(this + ": totalBytesRead is " + totalBytesRead)

        if (totalBytesRead > 0) {
          val chunk = new Array[Byte](totalBytesRead)
          readBuffer.rewind
          readBuffer.get(chunk) /** copy what we just read into chunk */
          messageState.consume(chunk)
          while (messageState.hasMessage) {
            val nextMsg = messageState.nextMessage
            receiveBytes(nextMsg)
          }
        }

        if (shouldTerminate) 
          terminate()
      }

      def doWrite(key: SelectionKey) {
        assert(isWriting)
        terminateOnError {
          writeQueue.synchronized {
            var socketFull = false
            while (!writeQueue.isEmpty && !socketFull) {
              val curEntry = writeQueue.head
              val bytesWritten = socketChannel.write(curEntry)
              Debug.info(this + ": doWrite() - wrote " + bytesWritten + " bytes to " + key.channel)
              val finished = curEntry(curEntry.length - 1).remaining == 0
              if (finished) { 
                writeQueue.dequeue
                Debug.info(this + ": doWrite() - finished, so dequeuing - queue length is now " + writeQueue.size)
              }
              if (bytesWritten == 0 && !finished) {
                socketFull = true
                Debug.info(this + ": doWrite() - socket is full")
              }
            }
            if (writeQueue.isEmpty) {
              // back to reading
              Debug.info(this + ": doWrite() - setting " + key.channel + " back to OP_READ interest")
              isWriting = false
              key.interestOps(SelectionKey.OP_READ)
            }
          }
        }
      }

      def doFinishConnect(key: SelectionKey) {
        terminateOnError { 
          socketChannel.finishConnect()
        }
        
        // check write queue. if it is not empty and we're not in write mode,
        // put us in write mode. otherwise, put us in read mode
        writeQueue.synchronized {
          if (!writeQueue.isEmpty && !isWriting) {
            isWriting = true
            key.interestOps(SelectionKey.OP_WRITE)
          } else key.interestOps(SelectionKey.OP_READ)
        }
      }

      override def toString = "<NonBlockingServiceConnection: " + socketChannel + ">"

    }

    class NonBlockingServiceListener(
        override val port: Int, 
        serverSocketChannel: ServerSocketChannel,
        override val connectionCallback: ConnectionCallback,
        receiveCallback: BytesReceiveCallback)
      extends Listener {

      override def mode = ServiceMode.NonBlocking

      def doAccept(key: SelectionKey) {
        /** For a channel to receive an accept even, it must be a server channel */
        val serverSocketChannel = key.channel.asInstanceOf[ServerSocketChannel]
        val clientSocketChannel = serverSocketChannel.accept
        nextSelectLoop().finishAccept(clientSocketChannel, receiveCallback, this, connectionCallback)
      }

      override def doTerminate() {
        serverSocketChannel.close()
      }

      override def toString = "<NonBlockingServiceListener: " + serverSocketChannel + ">"

    }

    def finishAccept(clientSocketChannel: SocketChannel, 
                     receiveCallback: BytesReceiveCallback,
                     listener: Listener,
                     connectionCallback: ConnectionCallback) {
      Debug.info(this + ": processAccept from " + clientSocketChannel)
      clientSocketChannel.configureBlocking(false)
      val conn = new NonBlockingServiceConnection(clientSocketChannel, receiveCallback)
      addOperation(new RegisterChannel(clientSocketChannel, SelectionKey.OP_READ, Some(conn)))
      connectionCallback(listener, conn)
    }

    override def run() {
      while (true) {
        try {
          operationQueue.synchronized {
            for (op <- operationQueue) { op() }
            operationQueue.clear
          }
          Debug.info(this + ": selectLoop calling select()")
          selector.select() /** This is a blocking operation */
          Debug.info(this + ": selectLoop awaken from select()")
          val selectedKeys = selector.selectedKeys.iterator
          while (selectedKeys.hasNext) {
            val key = selectedKeys.next
            selectedKeys.remove()
            if (key.isValid)
              if (key.isAcceptable)
                processAccept(key)
              else if (key.isConnectable)
                processConnect(key)
              else if (key.isWritable)
                processWrite(key)
              else if (key.isReadable)
                processRead(key)
            else
              Debug.error(this + ": Invalid key found: " + key)
          }
        } catch {
          case e: Exception =>
            Debug.error(this + " caught exception in select loop: " + e.getMessage)
            Debug.doError { e.printStackTrace }
        }
      }
    }

    // handle accepts

    private def processAccept(key: SelectionKey) {
      key.attachment.asInstanceOf[NonBlockingServiceListener].doAccept(key)
    }

    /** Warning: direct byte buffers are not guaranteed to be backed by array */
    private val readBuffer = ByteBuffer.allocateDirect(8192) 

    // handle reads

    private def processRead(key: SelectionKey) {
      key.attachment.asInstanceOf[NonBlockingServiceConnection].doRead(key)
    }

    // handle writes

    private def processWrite(key: SelectionKey) {
      key.attachment.asInstanceOf[NonBlockingServiceConnection].doWrite(key)
    }

    // handle connects

    private def processConnect(key: SelectionKey) {
      key.attachment.asInstanceOf[NonBlockingServiceConnection].doFinishConnect(key)
    }

    def connect(node: Node, receiveCallback: BytesReceiveCallback) = {
      val clientSocket = SocketChannel.open
      clientSocket.configureBlocking(false)
      val connected = clientSocket.connect(new InetSocketAddress(node.address, node.port))
      val interestOp = if (connected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT
      val conn = new NonBlockingServiceConnection(clientSocket, receiveCallback)
      addOperation(new RegisterChannel(clientSocket, interestOp, Some(conn))) 
      conn
    }

    def listen(port: Int, connectionCallback: ConnectionCallback, receiveCallback: BytesReceiveCallback) = {
      val serverSocketChannel = ServerSocketChannel.open
      serverSocketChannel.configureBlocking(false)
      serverSocketChannel.socket.bind(new InetSocketAddress(port))
      val listener = new NonBlockingServiceListener(port, serverSocketChannel, connectionCallback, receiveCallback)
      addOperation(new RegisterChannel(serverSocketChannel, SelectionKey.OP_ACCEPT, Some(listener)))    
      listener
    }

    override def toString = "<SelectorLoop " + id + ">"

    def terminate() {

    }

  }

  override def connect(node: Node, receiveCallback: BytesReceiveCallback) = {
    nextSelectLoop().connect(node, receiveCallback)
  }

  override def listen(port: Int, connectionCallback: ConnectionCallback, receiveCallback: BytesReceiveCallback) = {
    nextSelectLoop().listen(port, connectionCallback, receiveCallback)
  }

  override def terminate() {
    selectLoops foreach { _.terminate() }
  }

}
