package ch.inventsoft.scalabase.communicationport

import ch.inventsoft.scalabase.process._
import ch.inventsoft.scalabase.oip._
import ch.inventsoft.scalabase.log._
import java.io.{InputStream,OutputStream}
import ch.inventsoft.scalabase.time._

/**
 * Communication port based on an Input- and an OutputStream.
 */
trait IOStreamPort[Res] extends CommunicationPort with StateServer[IOStreamPortState[Res]] with Log {
  type State = IOStreamPortState[Res]
  
  /**
   * Time to wait for more data to become available on the input stream.
   * Smaller values increase responsivness, bigger values lower cpu usage. 
   */
  protected val readDelay: Duration = 10 ms
  /**
   * Max number of bytes read in a single packet.
   */
  protected val maxPacketSize: Int = 1024
  
  override protected[this] def initialState = {
    val (input, output, additional) = openStreams
    val reader = spawnChild(Required) {
      val read = createPortReader
      read(input)
    }
    val writer = createPortWriter(output)
    IOStreamPortState(reader, writer, None, Nil, additional)
  }
  protected[this] def createPortReader = new IOStreamPortReader(process, maxPacketSize, readDelay)
  protected[this] def createPortWriter(output: OutputStream) = IOStreamPortWriter(output)(SpawnAsRequiredChild)
  protected[this] def openStreams: (InputStream, OutputStream, Res)
  override protected[this] def messageHandler(state: State) = {
    case ReadPacket(state.reader, data) => state.redirectTo match {
      case Some(process) =>
        process ! DataReceived(this, data)
        Some(state)
      case None =>
        Some(state.addToBuffer(data))
  }}
  override def send(data: Iterator[Byte]) = cast { state =>
    state.writer.send(data)
    state
  }
  override def receive = call { state =>
    (state.buffer, state.emptyBuffer)
  }
  override def redirectIncomingTo(process: Option[Process]) = cast { state =>
    process.map{ p =>
      //Send any data currently in the buffer
      val buffer = state.buffer
      if (!buffer.isEmpty) p ! DataReceived(this, buffer)
      state.emptyBuffer
    }.getOrElse(state).redirect(process)
  }
  protected[this] val writerCloseTimeout = 5 s 
  override def close = call_ { state =>
    state.reader ! Terminate
    receiveWithin(writerCloseTimeout) { state.writer.close.option }
    closeAdditionalResource(state.additionalResource)
    ((), None)
  }
  protected[this] def closeAdditionalResource(resources: Res): Unit = {
  }
}
case class IOStreamPortState[Res](reader: Process, writer: IOStreamPortWriter, redirectTo: Option[Process], buffer: List[Byte], additionalResource: Res) {
  def addToBuffer(data: Seq[Byte]) = {
    val newBuffer = buffer ::: data.toList
    IOStreamPortState(reader, writer, redirectTo, newBuffer, additionalResource)
  }
  def emptyBuffer = IOStreamPortState(reader, writer, redirectTo, Nil, additionalResource)
  def redirect(to: Option[Process]) = {
    IOStreamPortState(reader, writer, to, buffer, additionalResource)
  }
}

/**
 * Reads packets from an InputStream and forwards it to a process (as ReadPacket's).
 * Terminate by sending it a Terminate-message.
 * 
 * Start like this: 
 *   val reader = new IOStreamPortReader(self)
 *   spawnChild(Required) {
 *     reader(inputStream) 
 *   }
 */
class IOStreamPortReader(forwardDataTo: Process, maxPacketSize: Int, readDelay: Duration) extends Function1[InputStream,Unit @processCps] with Log {
  override def apply(input: InputStream) = {
    log.trace("Create PortReader {}", self)
    readLoop(input)
  }
  private def readLoop(input: InputStream): Unit @processCps = {
    val continue = input.available match {
      case 0 =>
        //nothing available, wait a bit and try again
        receiveWithin(readDelay) {
          case Terminate => false
          case Timeout => true
        }
        
      case available =>
        //data is available, read it
        val buffer = new Array[Byte](maxPacketSize min available)
        input.read(buffer, 0, buffer.size) match {
          case -1 =>
            throw new EndOfStream()
          case 0 =>
            receiveWithin(readDelay) {
              case Terminate => false
              case Timeout => true
            }
          case readCount =>
            val packet = ReadPacket(self, buffer.take(readCount))
            forwardDataTo ! packet
            receiveNoWait {
              case Terminate => false
              case Timeout => true
            }
        }
    }
    if (continue) readLoop(input) else terminate(input)
  }
  protected[this] def terminate(input: InputStream) = {
    log.trace("Close PortReader {}", self)
    input.close
  }
}
case class ReadPacket(from: Process, data: Seq[Byte])
case class EndOfStream() extends Exception("unexpected end of stream")

/**
 * Writes packets to a output stream.
 * Use either #send or #sendAndWait.
 */
class IOStreamPortWriter protected(_output: OutputStream) extends StateServer[OutputStream] {
  override protected[this] def initialState = _output
  def send(data: Iterator[Byte]) = cast { output =>
    data.foreach(b => output.write(b))
    output.flush
    output
  }
  def sendAndWait(data: Iterator[Byte]) = call { output =>
    data.foreach(b => output.write(b))
    output.flush
    ((),output)
  }
  def close = call_ { output =>
    output.close
    ((), None)
  }
}
object IOStreamPortWriter {
  def apply(output: OutputStream)(as: SpawnStrategy) = {
    val o = new IOStreamPortWriter(output)
    o.start(as)
    o
  }
}
