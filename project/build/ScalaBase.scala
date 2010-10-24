import sbt._

class ScalaBaseProject(info: ProjectInfo) extends DefaultProject(info) with AutoCompilerPlugins {      
  val continuations = compilerPlugin("org.scala-lang.plugins" % "continuations" % "2.8.0")
  override def compileOptions = CompileOption("-P:continuations:enable") :: super.compileOptions.toList

  override def packageSrcJar= defaultJarPath("-sources.jar")
  val sourceArtifact = Artifact.sources(artifactID)
  override def packageToPublishActions = super.packageToPublishActions ++ Seq(packageSrc)

  val slf4j = "org.slf4j" % "slf4j-api" % "1.6.1"
  
  val scalatest = "org.scalatest" % "scalatest" % "1.2" % "test"
  val logbackcore = "ch.qos.logback" % "logback-core" % "0.9.24" % "test"
  val logbackclassic = "ch.qos.logback" % "logback-classic" % "0.9.24" % "test"

//  override def fork = forkRun("-agentpath:/Applications/JProfiler/bin/macos/libjprofilerti.jnilib=port=8849,nowait,id=109,config=/Users/ms/.jprofiler6/config.xml" :: Nil)
}
