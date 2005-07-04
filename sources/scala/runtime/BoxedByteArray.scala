/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */
package scala.runtime;

final class BoxedByteArray(val value: Array[Byte]) extends BoxedArray {

  def length: Int = value.length;

  def apply(index: Int): Object = BoxedByte.box(value(index));

  def update(index: Int, elem: Object): Unit = { 
    value(index) = elem.asInstanceOf[BoxedNumber].byteValue() 
  }

  def unbox(elemTag: String): Object = value;

  override def equals(other: Any) =
    value == other ||
    other.isInstanceOf[BoxedByteArray] && value == other.asInstanceOf[BoxedByteArray].value;

  override def hashCode(): Int = value.hashCode();
}


