/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */



package scala.actors
package remote


import java.io.{ ByteArrayInputStream, DataInputStream, 
                 DataOutputStream, IOException }
import java.net.{ InetSocketAddress, ServerSocket, Socket, 
                  SocketException, UnknownHostException }
import java.util.concurrent.{ ConcurrentHashMap, Executors }

import scala.collection.mutable.HashMap


private[remote] class BlockingServiceProvider extends ServiceProvider {

  private val executor = Executors.newCachedThreadPool()

  class BlockingServiceWorker(
      so: Socket, 
      override val isEphemeral: Boolean,
      override val receiveCallback: BytesReceiveCallback)
    extends Runnable
    with    ByteConnection {

    override def mode = ServiceMode.Blocking

    private val datain  = new DataInputStream(so.getInputStream)
    private val dataout = new DataOutputStream(so.getOutputStream)

    override val remoteNode = Node(so.getInetAddress.getHostName,  so.getPort) 
    override val localNode  = Node(so.getLocalAddress.getHostName, so.getLocalPort)

    override val connectFuture = NoOpFuture /** Connection already established */

    @volatile
    private var terminated = false

    override def doTerminateImpl(isBottom: Boolean) {
      Debug.info(this + ": doTerminateImpl(" + isBottom + ")")
      terminated = true
      dataout.flush()
      datain.close()
      dataout.close()
      so.close()
    }

    override def newAlreadyTerminatedException() = new ConnectionAlreadyClosedException

    override def send(seq: ByteSequence, ftch: Option[RFuture]) {
      terminateLock.synchronized {
        ensureAlive()
        try {
          if (seq.isDiscardable && seq.offset >= 4) {
            // optimize this case: place 4 bytes of the integer length before
            // the sequence, and just write the bytes in one call
            val b = seq.bytes
            val o = seq.offset
            val l = seq.length

            b(o - 4) = ((l >>> 24) & 0xff).toByte
            b(o - 3) = ((l >>> 16) & 0xff).toByte
            b(o - 2) = ((l >>> 8) & 0xff).toByte
            b(o - 1) = ((l & 0xff)).toByte

            //Debug.error("Writing %d bytes to socket".format(l))

            dataout.write(b, o - 4, l + 4)
          } else {
            // TODO: should we do an array copy here to avoid an extra socket
            // write, or should we just leave this case
            dataout.writeInt(seq.length)
            dataout.write(seq.bytes, seq.offset, seq.length)
          }
          dataout.flush() // TODO: do we need to flush every message?
          ftch.foreach(_.finishSuccessfully())
        } catch {
          case e: IOException => 
            ftch.foreach(_.finishWithError(e))
            Debug.error(this + ": caught " + e.getMessage)
            Debug.doError { e.printStackTrace }
            terminateBottom()
        }
      }
    }

    private final val EmptyArray = new Array[Byte](0)

    override def run() {
      try {
        while (!terminated) {
          val length = datain.readInt()
          if (length == 0) {
            receiveBytes(new ByteArrayInputStream(EmptyArray))
          } else if (length < 0) {
            throw new IllegalStateException("received negative length message size header")
          } else {
            val bytes = new Array[Byte](length)
            datain.readFully(bytes, 0, length)
            receiveBytes(new ByteArrayInputStream(bytes))
          }
        }
      } catch {
        case e: IOException if (terminated) =>
          Debug.info(this + ": listening thread is shutting down")
        case e: Exception =>
          Debug.error(this + ": caught " + e.getMessage)
          Debug.doError { e.printStackTrace }
          terminateBottom()
      }

    }

    override def toString = "<BlockingServiceWorker: " + so + ">"
  }

  private val DUMMY_VALUE = new Object

  class BlockingServiceListener(
  		serverPort: Int,
      override val connectionCallback: ConnectionCallback[ByteConnection],
      receiveCallback: BytesReceiveCallback)
    extends Runnable
    with    Listener {

    override def mode = ServiceMode.Blocking

    @volatile
    private var terminated = false

    private val serverSocket = new ServerSocket(serverPort)

  	override def port = serverSocket.getLocalPort

    private val childConnections = new ConcurrentHashMap[ByteConnection, Object]

    override def run() {
      try {
        while (!terminated) {
          val client = serverSocket.accept()
          client.setTcpNoDelay(true)
          val conn = new BlockingServiceWorker(client, true, receiveCallback)
          conn afterTerminate { isBottom =>
            childConnections.remove(conn)
          }
          childConnections.put(conn, DUMMY_VALUE)
          executor.execute(conn)
          receiveConnection(conn)
        }
      } catch {
        case e: SocketException if (terminated) =>
          Debug.error(this + ": Listening thread is shutting down")
        case e: Exception =>
          Debug.error(this + ": caught " + e.getMessage)
          Debug.doError { e.printStackTrace }
          terminateBottom()
      }
    }

    override def doTerminateImpl(isBottom: Boolean) {
      terminated = true
      import scala.collection.JavaConversions._
      childConnections.keys.foreach(_.doTerminate(isBottom))
      childConnections.clear()
      serverSocket.close()
    }

    override def toString = "<BlockingServiceListener: " + serverSocket + ">"

  }

  override def mode = ServiceMode.Blocking

  override def newAlreadyTerminatedException() = new ProviderAlreadyClosedException

  override def connect(node: Node, receiveCallback: BytesReceiveCallback) = 
    withoutTermination {
      ensureAlive()
      val socket = new Socket
      socket.setTcpNoDelay(true)
      socket.connect(new InetSocketAddress(node.address, node.port))
      val worker = new BlockingServiceWorker(socket, false, receiveCallback)
      executor.execute(worker)
      worker
    }

  override def listen(port: Int, 
                      connectionCallback: ConnectionCallback[ByteConnection], 
                      receiveCallback: BytesReceiveCallback) = 
    withoutTermination {
      ensureAlive()
      val listener = new BlockingServiceListener(port, connectionCallback, receiveCallback)
      executor.execute(listener)
      listener
    }

  override def doTerminateImpl(isBottom: Boolean) {
    executor.shutdownNow() 
  }

  override def toString = "<BlockingServiceProvider>"

}
