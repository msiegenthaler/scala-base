import sbt._

class ScalaBaseProject(info: ProjectInfo) extends DefaultProject(info) with AutoCompilerPlugins {      
  val continuations = compilerPlugin("org.scala-lang.plugins" % "continuations" % "2.8.0")
  override def compileOptions = CompileOption("-P:continuations:enable") :: super.compileOptions.toList

  val logbackcore = "ch.qos.logback" % "logback-core" % "0.9.24"
  val logbackclassic = "ch.qos.logback" % "logback-classic" % "0.9.24"
  
  val scalatest = "org.scalatest" % "scalatest" % "1.2-for-scala-2.8.0.final-SNAPSHOT" % "test"
  val toolsSnapshot = ScalaToolsSnapshots

//  override def fork = forkRun("-agentpath:/Applications/JProfiler/bin/macos/libjprofilerti.jnilib=port=8849,nowait,id=109,config=/Users/ms/.jprofiler6/config.xml" :: Nil)
}
