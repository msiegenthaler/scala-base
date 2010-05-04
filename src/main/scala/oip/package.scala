package ch.inventsoft.scalabase

package object oip {
  object Terminate
  
  def transient = oip.ProcessSpecification.transient
  def permanent = oip.ProcessSpecification.permanent
  def temporary = oip.ProcessSpecification.temporary
}
