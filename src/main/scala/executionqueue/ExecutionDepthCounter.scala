package ch.inventsoft.scalabase
package executionqueue

/**
 * Counts the nesting depth of an execution.
 * Warning: breaks tail-recursion, only use for non-tail-recursive executions
 */
class ExecutionDepthCounter {
  private val depthTl = new ThreadLocal[Int] { override def initialValue = 0  }
  
  def depth = depthTl.get
  
  def apply[A](f: (Int) => A) = {
    val currentDepth = depthTl.get + 1
    try {
      depthTl.set(currentDepth)
      f(currentDepth)
    } finally {
      depthTl.set(currentDepth - 1)
    }
  }
}
