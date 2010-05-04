package ch.inventsoft.scalabase.process.cps

import scala.collection.generic.CanBuildFrom
import scala.collection.mutable.Builder
import scala.util.continuations._


/**
 * Utilities for cps code.
 */
object CpsUtils {
  
  implicit def cpsIterable[A](ita: Iterable[A]): CpsIterable[A] = {
    new CpsIterable(ita)
  }
  
  class CpsIterable[A](val self: Iterable[A]) extends scala.collection.IterableProxy[A] {
    def foreach_cps[U,X](f: A => U @cps[X]): Unit @cps[X] = {
      val it = iterator
      def exec: Unit @cps[X] = {
        if (it.hasNext) {
          f(it.next)
          exec
        }
      }
      exec
    }
    def map_cps[B,That,X](f: A => B @cps[X])(implicit bf: CanBuildFrom[CpsIterable[A],B,That]): That @cps[X] = {
      val it = iterator
      val b = bf(repr)
      def compose: Unit @cps[X] = {
        if (it.hasNext) {
          b += f(it.next)
          compose
        }
      }
      compose
      b.result
    }
    def flatMap_cps[B,That,X](f: A => Traversable[B] @cps[X])(implicit bf: CanBuildFrom[CpsIterable[A],B,That]): That @cps[X] = {
      val it = iterator
      val b = bf(repr)
      def compose: Unit @cps[X] = {
        if (it.hasNext) {
          b ++= f(it.next)
          compose
        }
      }
      compose
      b.result
    }

    def foldLeft_cps[B,X](z: B)(op: (B,A) => B @cps[X]): B @cps[X] = {
      val it = iterator
      def exec(soFar: B): B @cps[X] = {
        if (it.hasNext) {
          exec(op(soFar, it.next))
        } else soFar
      }
      exec(z)
    }
    def foldRight_cps[B,X](z: B)(op: (A,B) => B @cps[X]): B @cps[X] = {
      reversed.foldLeft_cps(z)((x,y) => op(y,x))
    }
  }

  
  implicit def cpsOption[A](option: Option[A]): CpsOption[A] = option match {
    case Some(value) => new CpsSome(value)
    case None => CpsNone
  }
  trait CpsOption[+A] {
    def flatMap_cps[B,X](fun: A => Option[B] @cps[X]): Option[B] @cps[X]
    def map_cps[B,X](fun: A => B @cps[X]): Option[B] @cps[X]
    def foreach_cps[U,X](fun: A => U @cps[X]): Unit @cps[X]
  }
  class CpsSome[+A](value: A) extends CpsOption[A] {
    override def flatMap_cps[B,X](fun: A => Option[B] @cps[X]) = fun(value)
    override def map_cps[B,X](fun: A => B @cps[X]) = Some(fun(value))
    override def foreach_cps[U,X](fun: A => U @cps[X]) = {
      fun(value)
      ()
    }
  }
  object CpsNone extends CpsOption[Nothing] {
    override def flatMap_cps[B,X](fun: Nothing => Option[B] @cps[X]) = None
    override def map_cps[B,X](fun: Nothing => B @cps[X]) = None
    override def foreach_cps[U,X](fun: Nothing => U @cps[X]) = ()
  }

  
  implicit def cpsPartialFunction[A,B,X](fun: PartialFunction[A,B @cps[X]]) = {
    new CpsPartialFunction(fun)
  }
  
  class CpsPartialFunction[-A,+B,X](val self: PartialFunction[A,B @cps[X]]) extends PartialFunction[A,B @cps[X]] with Proxy {
    override def apply(v: A) = self.apply(v)
    override def isDefinedAt(v: A) = self.isDefinedAt(v)
    def andThen_cps[C](k: B => C @cps[X]): PartialFunction[A,C @cps[X]] = {
      val base = this
      new PartialFunction[A,C @cps[X]] {
        override def apply(v: A) = {
          val b = base(v)
          k(b)
        }
        override def isDefinedAt(v: A) = base.isDefinedAt(v)
      }
    }
    def orElse_cps[A1<:A,B1>:B](that: PartialFunction[A1, B1 @cps[X]]) : PartialFunction[A1,B1 @cps[X]] = {
      val base = this
      new PartialFunction[A1,B1 @cps[X]] {
        override def apply(v: A1) = {
          if (base.isDefinedAt(v)) base(v)
          else that(v)
        }
        override def isDefinedAt(v: A1) = base.isDefinedAt(v) || that.isDefinedAt(v)
      }
    }
  }
}
