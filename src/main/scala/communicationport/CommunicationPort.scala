package ch.inventsoft.scalabase.communicationport

import ch.inventsoft.scalabase.process._
import Messages._


/**
 * A port to communicate binary data over. For example a serial port.
 */
trait CommunicationPort {
  /**
   * Sends data to the port.
   */
  def send(data: Iterator[Byte]): Unit
  
  /**
   * Receives all available data from the port.
   * The port does not wait for new data to arrive. 
   * @see #redirectIncomingTo
   */
  def receive: MessageSelector[Seq[Byte]]
  
  /**
   * Redirects all incoming data to the process. The receive method will not work
   * (will return no data) when redirection is active.
   * The registered process will receive DataReceived messages.
   */
  def redirectIncomingTo(process: Option[Process]): Unit
  
  /**
   * Closes the port. The object is unusable afterwards.
   */
  def close: MessageSelector[Unit]

}

/**
 * Message that signals the recival of data by a port.
 * @see CommunicationPort#redirectIncomingTo
 */
case class DataReceived(on: CommunicationPort, data: Seq[Byte])
