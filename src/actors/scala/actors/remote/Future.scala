/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */



package scala.actors
package remote

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean

private[remote] trait Future { self =>
  def await(timeout: Long): Unit
  def awaitWithOption(timeout: Long): Option[Throwable]
  def finishSuccessfully(): Unit
  def finishWithError(t: Throwable): Unit 
  def chainWith(that: Future): Future = new Future {
    override def await(timeout: Long) {
      self.await(timeout)
      that.await(timeout)
    }
    override def awaitWithOption(timeout: Long) = {
      self.awaitWithOption(timeout) orElse that.awaitWithOption(timeout)
    }
    override def finishSuccessfully() {
      throw new RuntimeException("Cannot finish chain futures")
    }
    override def finishWithError(t: Throwable) {
      throw new RuntimeException("Cannot finish chain futures")
    }
    override def isFinished =
      self.isFinished && that.isFinished
  }
  def isFinished: Boolean
}

class FutureTimeoutException extends Exception("Future timed out")

/**
 * Simple lock-free future class based on CountDownLatch
 */
private[remote] class BlockingFuture extends Future {

  private val latch      = new CountDownLatch(1) 
  private val _isFinished = new AtomicBoolean(false)

  @volatile private var ex: Throwable = _

  override def await(timeout: Long) {
    if (!latch.await(timeout, TimeUnit.MILLISECONDS))
      throw new FutureTimeoutException
    if (ex ne null) throw ex
  }

  override def awaitWithOption(timeout: Long): Option[Throwable] = {
    if (!latch.await(timeout, TimeUnit.MILLISECONDS))
      throw new FutureTimeoutException
    Option(ex)
  }

  override def finishSuccessfully() {
    if (_isFinished.compareAndSet(false, true))
      latch.countDown()
    else 
      throw new IllegalStateException("Already set")
  }

  override def finishWithError(t: Throwable) {
    require(t ne null)
    if (_isFinished.compareAndSet(false, true)) {
      ex = t
      latch.countDown()
    } else
      throw new IllegalStateException("Already set")
  }

  override def isFinished = 
    latch.getCount == 0
}

/**
 * Future which assumes that the action will succeed, so awaits are no-ops,
 * and only if something does go wrong does callback get executed
 */
private[remote] class ErrorCallbackFuture(callback: Throwable => Unit) extends Future {
  override def await(timeout: Long) {}
  override def awaitWithOption(timeout: Long) = None
  override def finishSuccessfully() {}
  override def finishWithError(t: Throwable) { callback(t) }
  override def isFinished = true /** Constructed such that it is finished when it started */
}

/**
 * Future which represents an operation which has already completed
 */
private[remote] object NoOpFuture extends Future {
  override def await(timeout: Long) {}
  override def awaitWithOption(timeout: Long) = None
  override def finishSuccessfully() {
    throw new RuntimeException("finishSuccessfully should not be called") 
  }
  override def finishWithError(t: Throwable) {
    throw new RuntimeException("finishWithError should not be called") 
  }
  override def isFinished = true
}