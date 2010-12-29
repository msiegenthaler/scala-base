package ch.inventsoft.scalabase
package io

import scala.xml._


object XmlUtility {
  /** Cleans up duplicates in the namespace */
  def cleanNamespaces(elem: Elem): Elem = {
    val nb = elem.scope
    val prefixes = if (isDefaultNamespaceDefined(nb)) collectPrefixes(nb) else collectPrefixes(nb).-(null)
    val nb2 = prefixes.foldLeft[NamespaceBinding](TopScope) { (soFar,prefix) =>
      NamespaceBinding(prefix, nb.getURI(prefix), soFar)
    }
    elem.copy(scope=nb2)
  }

  /** Merges two NamespaceBindings (eliminates duplicates). */
  def mergeNamespaces(child: NamespaceBinding, parent: NamespaceBinding) = {
    val onlyParent = collectPrefixes(parent) -- collectPrefixes(child)
    val nb = onlyParent.foldLeft(child) { (nb,prefix) =>
      val uri = parent.getURI(prefix)
      NamespaceBinding(prefix, uri, nb)
    }
    if (!isDefaultNamespaceDefined(child) && isDefaultNamespaceDefined(parent))
      NamespaceBinding(null, parent.getURI(null), nb)
    else nb
  }

  def isDefaultNamespaceDefined(nb: NamespaceBinding): Boolean = {
    val d = nb.getURI(null)
    d != null && d != ""
  }

  private def collectPrefixes(b: NamespaceBinding, soFar: Set[String] = Set()): Set[String] = {
    val s = soFar + b.prefix
    if (b.parent == null) s
    else collectPrefixes(b.parent, s)
  }

  /** Serializes the node to a string (works around namespace bug in scala 2.8.1) */
  def mkString(node: Node, minimizeTags: Boolean=true): String = {
    val n2 = node match {
      case e: Elem =>
        cleanNamespaces(e)
      case other => other
    }
    Utility.toXML(x=n2, minimizeTags=minimizeTags).toString
  }
}
