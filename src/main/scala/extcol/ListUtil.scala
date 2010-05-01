package ch.inventsoft.scalabase.extcol;


object ListUtil {
  /**
   * Removes the first matching element from the list and returns it along with the
   * rest of the list. Returns None if no element matches.
   */
  def removeFirst[A](list: List[A], filter: A => Boolean) = {
    def removeFirst_(head: List[A], tail: List[A]): Option[(A,List[A])] = tail match {
      case ele :: tail => 
        if (filter(ele)) Some(ele, head reverse_::: tail)
        else removeFirst_(ele :: head, tail)
      case Nil => None
    }
    removeFirst_(Nil, list)
  } 

  /**
   * Removes the last matching element from the list and returns it along with the
   * rest of the list. Returns None if no element matches.
   */
  def removeLast[A](list: List[A], filter: A => Boolean): Option[(A,List[A])] = {
    def removeLast_(head: List[A], tail: List[A], candidate: Option[(A,List[A],List[A])]): Option[(A,List[A],List[A])] = tail match {
      case ele :: tail => 
        val c = if (filter(ele)) Some(ele, head, tail) else candidate
        removeLast_(ele :: head, tail, c)
      case Nil => candidate
    }
    removeLast_(Nil, list, None) match {
      case Some((e, h, t)) => Some(e, h.reverse ::: t)
      case None => None
    }
  }
  
  /**
   * Seq of bytes to "12 FE AB 14 7E"
   */
  def byteListToHex(in: Seq[Byte]) = {
    in.map(_ & 0xFF).map(_.toHexString).map(_.toUpperCase).map(s => "0"*(2-s.length)+s).mkString(" ")
  }
}
