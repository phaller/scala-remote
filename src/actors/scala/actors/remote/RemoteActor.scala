/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */



package scala.actors
package remote

import scala.collection.mutable.{ HashMap, HashSet }

/**
 *  This object provides methods for creating, registering, and
 *  selecting remotely accessible actors.
 *
 *  A remote actor is typically created like this:
 *  {{{
 *  actor {
 *    alive(9010)
 *    register('myName, self)
 *
 *    // behavior
 *  }
 *  }}}
 *  It can be accessed by an actor running on a (possibly)
 *  different node by selecting it in the following way:
 *  {{{
 *  actor {
 *    // ...
 *    val c = select(Node("127.0.0.1", 9010), 'myName)
 *    c ! msg
 *    // ...
 *  }
 *  }}}
 *
 * @author Philipp Haller
 */
object RemoteActor {

  /**
   * Maps actors to the port(s) it is listening on
   */
  private val actors = new HashMap[Actor, HashSet[Int]]

  /**
   * Maps listening port to the mode it is running in
   */
  private val portToMode = new HashMap[Int, ServiceMode]

  /**
   * Backing net kernel
   */
  private lazy val netKernel = new NetKernel(new StandardService)

  /* If set to <code>null</code> (default), the default class loader
   * of <code>java.io.ObjectInputStream</code> is used for deserializing
   * java objects sent as messages. Custom serializers are free to ignore this
   * field (especially since it probably doesn't apply).
   */
  private var cl: ClassLoader = null

  def classLoader: ClassLoader = cl
  def classLoader_=(x: ClassLoader) { cl = x }

  /**
   * Default serializer instance, using Java serialization to implement
   * serialization of objects. Not a val, so we can capture changes made
   * to the classloader instance
   */
  def defaultSerializer: Serializer = new JavaSerializer(cl)

  case class InconsistentSerializerException(expected: Serializer, actual: Serializer) 
    extends Exception("Inconsistent serializers: Expected " + expected + " but got " + actual)

  case class InconsistentServiceException(expected: ServiceMode, actual: ServiceMode) 
    extends Exception("Inconsistent service modes: Expected " + expected + " but got " + actual)

  /**
   * Makes <code>self</code> remotely accessible on TCP port
   * <code>port</code>.
   */
  @throws(classOf[InconsistentServiceException])
  def alive(port: Int, serviceMode: ServiceMode.Value = ServiceMode.Blocking): Unit = synchronized {

    // check to see if the port can support this service mode, or if we
    // need to start listening
    portToMode.get(port) match {
      case Some(mode0) =>
        // check to see if the mode is consistent on the port
        if (mode0 != serviceMode) 
          throw InconsistentServiceException(serviceMode, mode0)
      case None =>
        // need to listen
        listenOnPort(port, serviceMode)
    }

    // now register the actor w/ this name
    val thisActor = Actor.self
    val registrations = actors.get(thisActor) match {
      case Some(r) => r
      case None =>
        val r = new HashSet[Int]
        actors += thisActor -> r 
        r
    }

    registrations += port

    // TODO: onTerminate handler
  }

  def register(name: Symbol, actor: Actor) {
    netKernel.register(name, actor)
  }

  private def listenOnPort(port: Int, mode: ServiceMode.Value) { netKernel.listen(port, mode) }

  // does NOT invoke kernel.register()
  //private def addNetKernel(actor: Actor, kern: NetKernel) {
  //  kernels += Pair(actor, kern) // assumes no such mapping previously exists
  //  val kernLock = this
  //  actor.onTerminate {
  //    Debug.info("alive actor "+actor+" terminated")
  //    // Unregister actor from kernel
  //    kern.unregister(actor)
  //    val todo = kernLock.synchronized {
  //      // remove mapping for `actor`
  //      kernels -= actor
  //      // terminate `kern` when it does
  //      // not appear as value any more
  //      if (!kernels.valuesIterator.contains(kern)) {
  //        Debug.info("actor " + actor + " causing terminating "+kern)
  //        // terminate NetKernel
  //        () => kern.terminate()
  //      } else 
  //        () => ()
  //    }
  //    todo()
  //  }
  //}

  case class NameAlreadyRegisteredException(sym: Symbol, a: OutputChannel[Any])
    extends Exception("Name " + sym + " is already registered for channel " + a)

  def remoteStart[A <: Actor, S <: Serializer](node: Node, 
                                               serializer: Serializer,
                                               actorClass: Class[A], 
                                               port: Int, 
                                               name: Symbol,
                                               serviceFactory: Option[ServiceFactory],
                                               serializerClass: Option[Class[S]]) {
    remoteStart(node, serializer, actorClass.getName, port, name, serviceFactory, serializerClass.map(_.getName))
  } 

  def remoteStart(node: Node,
                  serializer: Serializer,
                  actorClass: String, 
                  port: Int, 
                  name: Symbol,
                  serviceFactory: Option[ServiceFactory],
                  serializerClass: Option[String]) {
    val remoteActor = select(node, 'remoteStartActor, serializer)
    remoteActor ! RemoteStart(actorClass, port, name, serviceFactory, serializerClass)
  }

  private var remoteListenerStarted = false

  def startListeners(): Unit = synchronized {
    if (!remoteListenerStarted) {
      RemoteStartActor.start
      remoteListenerStarted = true
    }
  }

  def stopListeners(): Unit = synchronized {
    if (remoteListenerStarted) {
      RemoteStartActor.send(Terminate, null)
      remoteListenerStarted = false
    }
  }

  /**
   * Returns (a proxy for) the actor registered under
   * <code>name</code> on <code>node</code>.
   */
  @throws(classOf[InconsistentSerializerException])
  @throws(classOf[InconsistentServiceException])
  def select(node: Node, sym: Symbol, 
             serializer: Serializer = defaultSerializer,
             serviceMode: ServiceMode.Value = Blocking): AbstractActor = synchronized {
    val connection = netKernel.connect(node, serializer, serviceMode)
    netKernel.getOrCreateProxy(connection, sym)
  }

}


/**
 * This class represents a machine node on a TCP network.
 *
 * @param address the host name, or <code>null</code> for the loopback address.
 * @param port    the port number.
 *
 * @author Philipp Haller
 */
case class Node(address: String, port: Int) {
  import java.net.{ InetAddress, InetSocketAddress }

  /**
   * Returns an InetSocketAddress representation of this Node
   */
  def toInetSocketAddress = new InetSocketAddress(address, port)

  /**
   * Returns the canonical representation of this form, resolving the
   * address into canonical form (as determined by the Java API)
   */
  def canonicalForm: Node = {
    val a = InetAddress.getByName(address)
    Node(a.getCanonicalHostName, port)
  }
  def isCanonical         = this == canonicalForm
}
