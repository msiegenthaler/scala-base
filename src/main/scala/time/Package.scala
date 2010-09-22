package ch.inventsoft.scalabase

package object time {
  implicit def durationBase(amount: Int) = new Duration.DurationMaker(amount)
  implicit def durationBase(amount: Long) = new Duration.DurationMaker(amount)
}

