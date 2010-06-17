/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */



package scala.actors
package remote


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

  private val kernels = new scala.collection.mutable.HashMap[Actor, NetKernel]

  /* If set to <code>null</code> (default), the default class loader
   * of <code>java.io.ObjectInputStream</code> is used for deserializing
   * objects sent as messages.
   */
  private var cl: ClassLoader = null

  def classLoader: ClassLoader = cl
  def classLoader_=(x: ClassLoader) { cl = x }

  /**
   * Default serializer instance, using Java serialization to implement
   * serialization of objects.
   */
  def defaultSerializer: Serializer = new JavaSerializer(cl)

  /**
   * Makes <code>self</code> remotely accessible on TCP port
   * <code>port</code>.
   */
  def alive(port: Int, serializer: Serializer = defaultSerializer): Unit = synchronized {
    createNetKernelOnPort(Actor.self, port, serializer)
  }

  private[remote] def createNetKernelOnPort(actor: Actor, port: Int, serializer: Serializer): NetKernel = {
    Debug.info("createNetKernelOnPort: creating net kernel for actor " + actor + " on port " + port)
    val serv = TcpService(port, cl, serializer)
    val kern = serv.kernel
    kernels += Pair(actor, kern)

    actor.onTerminate {
      Debug.info("alive actor "+actor+" terminated")
      // remove mapping for `actor`
      kernels -= actor
      // terminate `kern` when it does
      // not appear as value any more
      if (!kernels.valuesIterator.contains(kern)) {
        Debug.info("terminating "+kern)
        // terminate NetKernel
        kern.terminate()
      }
    }

    kern
  }

  @deprecated("this member is going to be removed in a future release")
  def createKernelOnPort(port: Int): NetKernel =
    createNetKernelOnPort(Actor.self, port, defaultSerializer)

  /**
   * Registers <code>a</code> under <code>name</code> on this
   * node.
   */
  def register(name: Symbol, a: Actor, serializer: Serializer = defaultSerializer): Unit = synchronized {
    val kernel = kernelFor(a, serializer)
    kernel.register(name, a)
  }

  private def selfKernel(serializer: Serializer) = kernelFor(Actor.self, serializer)

  private def kernelFor(a: Actor, serializer: Serializer) = kernels.get(a) match {
    case None =>
      // establish remotely accessible
      // return path (sender)
      createNetKernelOnPort(a, TcpService.generatePort, serializer)
    case Some(k) =>
      // serializer argument is ignored here in the case where we already have
      // a NetKernel instance
      k
  }

  def remoteStart[A <: Actor, S <: Serializer](node: Node, 
                                               serializer: Serializer,
                                               actorClass: Class[A], 
                                               port: Int, 
                                               name: Symbol,
                                               serializerClass: Option[Class[S]]) {
    remoteStart(node, serializer, actorClass.getName, port, name, serializerClass.map(_.getName))
  } 

  def remoteStart(node: Node,
                  serializer: Serializer,
                  actorClass: String, 
                  port: Int, 
                  name: Symbol,
                  serializerClass: Option[String]) {
    val remoteActor = select(node, 'remoteStartActor, serializer)
    remoteActor ! RemoteStart(actorClass, port, name, serializerClass)
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
      RemoteStartActor ! Terminate
      remoteListenerStarted = false
    }
  }

  /**
   * Returns (a proxy for) the actor registered under
   * <code>name</code> on <code>node</code>.
   */
  def select(node: Node, sym: Symbol, serializer: Serializer = defaultSerializer): AbstractActor = synchronized {
    selfKernel(serializer).getOrCreateProxy(node, sym)
  }

  private[remote] def someNetKernel: NetKernel =
    kernels.valuesIterator.next

  @deprecated("this member is going to be removed in a future release")
  def someKernel: NetKernel =
    someNetKernel
}


/**
 * This class represents a machine node on a TCP network.
 *
 * @param address the host name, or <code>null</code> for the loopback address.
 * @param port    the port number.
 *
 * @author Philipp Haller
 */
case class Node(address: String, port: Int)
