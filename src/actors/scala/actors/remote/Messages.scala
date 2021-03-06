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
 * Base trait for all messages that the <code>NetKernel</code> understands.
 * These are the envelope messages used to wrap sent messages with enough
 * metadata to locate the correct actor on the remote end.
 *
 * Most users of remote actors do not have to worry about these control
 * messages; only implementors of new serializers need to concern themselves
 * with these messages. These messages are exposed for performance reasons;
 * writers of serializers can probably serialize these messages much more
 * efficiently than the Java serializer can, so it makes sense to expose these
 * types.
 *
 * These types are subject to change much more than the rest of the public API
 * is, for performance and functionality reasons.
 * 
 * @see MessageCreator
 */
sealed trait NetKernelMessage

/**
 * Provides factory methods for default (case class) implementations of the
 * <code>LocateRequest</code> interface.
 *
 * @see DefaultLocateRequestImpl
 */
object LocateRequest {
  def apply(sessionId: Long, receiverName: String): LocateRequest =
    DefaultLocateRequestImpl(sessionId, receiverName)
  def unapply(r: LocateRequest): Option[(Long, String)] =
    Some(r.sessionId, r.receiverName)
}

/**
 * <code>LocateRequest</code> is issued by the network layer to locate a named
 * actor at the remote end
 */
trait LocateRequest extends NetKernelMessage {
  /**
   * The session id associated with the request. Only needs to be unique per
   * machine
   */
  def sessionId: Long

  /**
   * The name of the actor in to be located
   */
  def receiverName: String
}

/**
 * A simple implementation of the <code>LocateRequest</code> interface
 */
case class DefaultLocateRequestImpl(override val sessionId: Long,
  																	override val receiverName: String)
  extends LocateRequest
  
/**
 * Provides factory methods for default (case class) implementations of the
 * <code>LocateResponse</code> interface.
 *
 * @see DefaultLocateResponseImpl
 */
object LocateResponse {
  def apply(sessionId: Long, receiverName: String, found: Boolean): LocateResponse =
    DefaultLocateResponseImpl(sessionId, receiverName, found)
  def unapply(r: LocateResponse): Option[(Long, String, Boolean)] =
    Some(r.sessionId, r.receiverName, r.found)
}

/**
 * <code>LocateResponse</code> is the response returned for a
 * <code>LocateRequest</code>
 */
trait LocateResponse extends NetKernelMessage {
  def sessionId: Long
  def receiverName: String
  def found: Boolean
}

/**
 * A simple implementation of the <code>LocateResponse</code> interface
 */
case class DefaultLocateResponseImpl(override val sessionId: Long,
  																	 override val receiverName: String,
                                     override val found: Boolean)
  extends LocateResponse

/**
 * Provides factory methods for default (case class) implementations of the
 * <code>AsyncSend</code> interface.
 *
 * @see DefaultAsyncSendImpl
 */
object AsyncSend {
  def apply(senderName: String, receiverName: String, message: AnyRef): AsyncSend =
    DefaultAsyncSendImpl(senderName, receiverName, message)
  def unapply(n: AsyncSend): Option[(String, String, AnyRef)] =
    Some((n.senderName, n.receiverName, n.message))
}

/**
 * <code>AsyncSend</code> maps directly to a fire and forget message.
 */
trait AsyncSend extends NetKernelMessage {

  /**
   * The sender of the async message. Can be null. Is not exposed as an
   * <code>Option</code> for more flexibility with non Scala serialization
   * frameworks.
   */
  def senderName: String

  /**
   * The receiver (on the remote end) of the message. Can NOT be null.
   */
  def receiverName: String

  /**
   * The message being sent. Is typed <code>AnyRef</code> for flexibility.
   */
  def message: AnyRef
}

/**
 * A simple implementation of the <code>AsyncSend</code> interface
 */
case class DefaultAsyncSendImpl(override val senderName: String,
                                override val receiverName: String, 
                                override val message: AnyRef) extends AsyncSend

/**
 * Provides factory methods for the default (case class) implementations of
 * the <code>SyncSend</code> interface.
 *
 * @see DefaultSyncSendImpl
 */
object SyncSend {
  def apply(senderName: String, receiverName: String, message: AnyRef, session: String): SyncSend =
    DefaultSyncSendImpl(senderName, receiverName, message, session)
  def unapply(n: SyncSend): Option[(String, String, AnyRef, String)] =
    Some((n.senderName, n.receiverName, n.message, n.session))
}

/**
 * <code>SyncSend</code> maps directly to either a <code>!?</code> or a
 * <code>!!</code> message.
 */
trait SyncSend extends NetKernelMessage {

  /**
   * The sender of the message. Can NOT be null.
   */
  def senderName: String
  
  /**
   * The receiver (on the remote end) of the message. Can NOT be null.
   */
  def receiverName: String

  /**
   * The message being sent. Is typed <code>AnyRef</code> for flexibility.
   */
  def message: AnyRef

  /**
   * An opaque session ID used to describe this session. This session ID is
   * unique for both ends of the session. Is currently implemented as a
   * concatenation of the origin's hostname followed by a random 64-bit
   * integer generated by the origin. 
   */
  def session: String
}

case class DefaultSyncSendImpl(override val senderName: String, 
                               override val receiverName: String, 
                               override val message: AnyRef,
                               override val session: String) extends SyncSend

/**
 * Provides factory methods for the default (case class) implementations of
 * the <code>SyncReply</code> interface.
 *
 * @see DefaultSyncReplyImpl
 */
object SyncReply {
  def apply(receiverName: String, message: AnyRef, session: String): SyncReply =
    DefaultSyncReplyImpl(receiverName, message, session)
  def unapply(n: SyncReply): Option[(String, AnyRef, String)] =
    Some((n.receiverName, n.message, n.session))
}

/**
 * <code>SyncReply</code> maps directly to a response from a <code>!?</code>
 * or a <code>!!</code> message. It does NOT contain a
 * <code>senderName</code>, since you cannot reply to a reply (in the current
 * actors framework).
 */
trait SyncReply extends NetKernelMessage {

  /**
   * The receiver (and also the original sender) of the reply
   */
  def receiverName: String

  /**
   * The message being sent. Is typed <code>AnyRef</code> for flexibility.
   */
  def message: AnyRef

  /**
   * The session ID. 
   * 
   * @see SyncSend#session
   */
  def session: String
}

/**
 * A simple implementation of the <code>SyncReply</code> interface
 */
case class DefaultSyncReplyImpl(override val receiverName: String, 
                                override val message: AnyRef,
                                override val session: String) extends SyncReply
/**
 * Provides factory methods for the default (case class) implementations of
 * the <code>RemoteApply</code> interface.
 *
 * @see DefaultRemoteApplyImpl
 */
object RemoteApply {
  def apply(senderName: String, receiverName: String, rfun: RemoteFunction): RemoteApply = 
    DefaultRemoteApplyImpl(senderName, receiverName, rfun)
  def unapply(r: RemoteApply): Option[(String, String, RemoteFunction)] = 
    Some((r.senderName, r.receiverName, r.function))
}

/**
 * <code>RemoteApply</code> maps to a <code>link</code>, <code>unlink</code>,
 * or <code>exit</code> called on a remote actor proxy.
 */
trait RemoteApply extends NetKernelMessage {

  /**
   * The sender of the message. Can NOT be null.
   */
  def senderName: String

  /**
   * The receiver (on the remote end) of the message. Can NOT be null.
   */
  def receiverName: String

  /**
   * The remote function to apply on the other hand.
   */
  def function: RemoteFunction
}

/**
 * A simple implementation of the <code>RemoteApply</code> interface
 */
case class DefaultRemoteApplyImpl(override val senderName: String,
                                  override val receiverName: String,
                                  override val function: RemoteFunction) extends RemoteApply


object RemoteStartInvoke {
  def apply(actorClass: String): RemoteStartInvoke = 
    DefaultRemoteStartInvokeImpl(actorClass)
  def unapply(r: RemoteStartInvoke): Option[(String)] = Some((r.actorClass))
}

trait RemoteStartInvoke {
  def actorClass: String
}

case class DefaultRemoteStartInvokeImpl(override val actorClass: String) extends RemoteStartInvoke

object RemoteStartInvokeAndListen {
  def apply(actorClass: String, port: Int, name: String): RemoteStartInvokeAndListen =
    DefaultRemoteStartInvokeAndListenImpl(actorClass, port, name)
  def unapply(r: RemoteStartInvokeAndListen): Option[(String, Int, String)] = 
    Some((r.actorClass, r.port, r.name))
}

trait RemoteStartInvokeAndListen {
  def actorClass: String
  def port: Int
  def name: String
}

case class DefaultRemoteStartInvokeAndListenImpl(override val actorClass: String,
                                                 override val port: Int,
                                                 override val name: String)
  extends RemoteStartInvokeAndListen

object RemoteStartResult {
  def apply(errorMessage: Option[String]): RemoteStartResult = 
    DefaultRemoteStartResultImpl(errorMessage)
  def unapply(r: RemoteStartResult): Option[(Option[String])] =
    Some((r.errorMessage))
}

trait RemoteStartResult {
  def success: Boolean = errorMessage.isEmpty
  def errorMessage: Option[String]
}

case class DefaultRemoteStartResultImpl(override val errorMessage: Option[String]) extends RemoteStartResult
