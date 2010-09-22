package ch.inventsoft.scalabase
package process

/**
 * Type of a child to spawn. 
 */
sealed trait ChildType

/**
 * Child will be monitored for termination (normal and abnormal). An abnormal
 * termination will not affect the parent process.
 */
object Monitored extends ChildType {
  override def toString = "Monitored"
}

/**
 * The child will crash the parent on abnormal termination. It will not send
 * a message in case of a normal termination. 
 */
object Required extends ChildType {
  override def toString = "Required"
}

/**
 * The child will not crash the parent on abnormal termination and will not send
 * any messages.
 */
object NotMonitored extends ChildType {
  override def toString = "NotMonitored"
}