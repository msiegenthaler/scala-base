package ch.inventsoft.scalabase

package object oip {
  object Terminate
  
  import oip._
  
  def transient = ProcessSpecification.transient
  def permanent = ProcessSpecification.permanent
  def temporary = ProcessSpecification.temporary
}