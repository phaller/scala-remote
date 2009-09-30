/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2009, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id: Reactor.scala 18840 2009-09-30 15:42:18Z phaller $

package scala.actors

import scala.collection.mutable.Queue

/**
 * The Reactor trait provides very lightweight actors.
 *
 * @author Philipp Haller
 */
trait Reactor extends OutputChannel[Any] {

  /* The actor's mailbox. */
  private[actors] val mailbox = new MessageQueue("Reactor")

  private[actors] var sendBuffer = new Queue[(Any, OutputChannel[Any])]

  /* If the actor waits in a react, continuation holds the
   * message handler that react was called with.
   */
  private[actors] var continuation: PartialFunction[Any, Unit] = null

  /* Whenever this Actor executes on some thread, waitingFor is
   * guaranteed to be equal to waitingForNone.
   *
   * In other words, whenever waitingFor is not equal to
   * waitingForNone, this Actor is guaranteed not to execute on some
   * thread.
   */
  private[actors] val waitingForNone = (m: Any) => false
  private[actors] var waitingFor: Any => Boolean = waitingForNone

  /**
   * The behavior of a reactor is specified by implementing this
   * abstract method.
   */
  def act(): Unit

  protected[actors] def exceptionHandler: PartialFunction[Exception, Unit] =
    Map()

  protected[actors] def scheduler: IScheduler =
    Scheduler

  /**
   * Returns the number of messages in <code>self</code>'s mailbox
   *
   * @return the number of messages in <code>self</code>'s mailbox
   */
  def mailboxSize: Int =
    mailbox.size

  /**
   * Sends <code>msg</code> to this reactor (asynchronous) supplying
   * explicit reply destination.
   *
   * @param  msg      the message to send
   * @param  replyTo  the reply destination
   */
  def send(msg: Any, replyTo: OutputChannel[Any]) {
    val todo = synchronized {
      if (waitingFor ne waitingForNone) {
        val savedWaitingFor = waitingFor
        waitingFor = waitingForNone
        () => scheduler execute (makeReaction(() => {
          val startMbox = new MessageQueue("Start")
          synchronized { startMbox.append(msg, replyTo) }
          searchMailbox(startMbox, savedWaitingFor, true)
        }))
      } else {
        sendBuffer.enqueue((msg, replyTo))
        () => { /* do nothing */ }
      }
    }
    todo()
  }

  private[actors] def makeReaction(fun: () => Unit): Runnable =
    new ReactorTask(this, fun)

  private[actors] def resumeReceiver(item: (Any, OutputChannel[Any]), onSameThread: Boolean) {
    // assert continuation != null
    if (onSameThread)
      continuation(item._1)
    else
      scheduleActor(continuation, item._1)
  }

  /**
   * Sends <code>msg</code> to this reactor (asynchronous).
   */
  def !(msg: Any) {
    send(msg, null)
  }

  /**
   * Forwards <code>msg</code> to this reactor (asynchronous).
   */
  def forward(msg: Any) {
    send(msg, null)
  }

  def receiver: Actor = this.asInstanceOf[Actor]

  private[actors] def drainSendBuffer(mbox: MessageQueue) {
    while (!sendBuffer.isEmpty) {
      val item = sendBuffer.dequeue()
      mbox.append(item._1, item._2)
    }
  }

  // assume continuation != null
  private[actors] def searchMailbox(startMbox: MessageQueue,
                                    handlesMessage: Any => Boolean,
                                    resumeOnSameThread: Boolean) {
    var tmpMbox = startMbox
    var done = false
    while (!done) {
      val qel = tmpMbox.extractFirst(handlesMessage)
      if (tmpMbox ne mailbox)
        tmpMbox.foreach((m, s) => mailbox.append(m, s))
      if (null eq qel) {
        synchronized {
          // in mean time new stuff might have arrived
          if (!sendBuffer.isEmpty) {
            tmpMbox = new MessageQueue("Temp")
            drainSendBuffer(tmpMbox)
            // keep going
          } else {
            waitingFor = handlesMessage
            done = true
          }
        }
      } else {
        resumeReceiver((qel.msg, qel.session), resumeOnSameThread)
        done = true
      }
    }
  }

  /**
   * Receives a message from this reactor's mailbox.
   * <p>
   * This method never returns. Therefore, the rest of the computation
   * has to be contained in the actions of the partial function.
   *
   * @param  f    a partial function with message patterns and actions
   */
  protected[actors] def react(f: PartialFunction[Any, Unit]): Nothing = {
    assert(Actor.rawSelf(scheduler) == this, "react on channel belonging to other reactor")
    synchronized { drainSendBuffer(mailbox) }
    continuation = f
    searchMailbox(mailbox, f.isDefinedAt, false)
    throw Actor.suspendException
  }

  /* This method is guaranteed to be executed from inside
   * an actors act method.
   *
   * assume handler != null
   */
  private[actors] def scheduleActor(handler: PartialFunction[Any, Unit], msg: Any) = {
    val fun = () => handler(msg)
    val task = new ReactorTask(this, fun)
    scheduler execute task
  }

  /**
   * Starts this reactor.
   */
  def start(): Reactor = {
    ActorGC.newActor(this)
    val task = new ReactorTask(this, () => act())
    scheduler execute task
    this
  }

  /* This closure is used to implement control-flow operations
   * built on top of `seq`. Note that the only invocation of
   * `kill` is supposed to be inside `Reaction.run`.
   */
  private[actors] var kill: () => Unit =
    () => { exit() }

  private[actors] def seq[a, b](first: => a, next: => b): Unit = {
    val s = Actor.rawSelf(scheduler)
    val killNext = s.kill
    s.kill = () => {
      s.kill = killNext

      // to avoid stack overflow:
      // instead of directly executing `next`,
      // schedule as continuation
      scheduleActor({ case _ => next }, 1)
      throw Actor.suspendException
    }
    first
    throw new KillActorException
  }

  /**
   * Terminates the execution of this reactor.
   */
  def exit(): Nothing = {
    terminated()
    throw Actor.suspendException
  }

  private[actors] def terminated() {
    ActorGC.terminated(this)
  }

}
