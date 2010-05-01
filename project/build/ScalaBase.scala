import sbt._

class ScalaBaseProject(info: ProjectInfo) extends DefaultProject(info) with AutoCompilerPlugins {      
  val continuations = compilerPlugin("org.scala-lang.plugins" % "continuations" % "2.8.0-SNAPSHOT")
  override def compileOptions = CompileOption("-P:continuations:enable") :: super.compileOptions.toList
  
      
  val slf4j = "org.slf4j" % "slf4j-api" % "1.5.11"
  val logbackcore = "ch.qos.logback" % "logback-core" % "0.9.20"
  val logbackclassic = "ch.qos.logback" % "logback-classic" % "0.9.20"
  
  val scalatest = "org.scalatest" % "scalatest" % "1.0.1-for-scala-2.8.0.RC1-SNAPSHOT" % "test"
  val toolsSnapshot = ScalaToolsSnapshots
}