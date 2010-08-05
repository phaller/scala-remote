/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */



package scala.actors
package remote

import java.io.ByteArrayOutputStream

/**
 * This class gives us access to the underlying buffer of
 * ByteArrayOutputStream, so that we can avoid a copy.
 *
 * TODO: reimplement ByteArrayOutputStream methods to NOT be thread-safe,
 * since we don't use ExposingByteArrayOutputStream concurrently.
 */
class ExposingByteArrayOutputStream(i: Int) extends ByteArrayOutputStream(i) {
	def this() = this(32)
	def getUnderlyingByteArray = this.buf
}
