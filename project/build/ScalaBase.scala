import sbt._

class ScalaBaseProject(info: ProjectInfo) extends DefaultProject(info) with AutoCompilerPlugins {      
  val continuations = compilerPlugin("org.scala-lang.plugins" % "continuations" % "2.8.1")
  override def compileOptions = CompileOption("-P:continuations:enable") :: super.compileOptions.toList

  override def packageSrcJar= defaultJarPath("-sources.jar")
  val sourceArtifact = Artifact.sources(artifactID)
  override def packageToPublishActions = super.packageToPublishActions ++ Seq(packageSrc)

  val inventsoftReleases = "Inventsoft Release Repository" at "http://mavenrepo.inventsoft.ch/repo"
  val inventsoftSnapshots = "Inventsoft Snapshot Repository" at "http://mavenrepo.inventsoft.ch/snapshot-repo"
  val jbossRepo = "JBoss public repository" at "http://repository.jboss.org/nexus/content/groups/public-jboss/"
  override def managedStyle = ManagedStyle.Maven
  val publishTo = Resolver.sftp("Inventsoft Publish", "foxtrot.inventsoft.ch", "/inventsoft/dev/mavenrepo/snapshot-repo")
  Credentials(Path.userHome / ".ivy2" / ".credentials", log)



  val slf4j = "org.slf4j" % "slf4j-api" % "1.6.1"
  val jsr166y = "jsr166" % "jsr166y" % "20101024"
  val jsr310 = "javax.time" % "jsr-310-ri" % "20100212"
  
  val scalatest = "org.scalatest" % "scalatest" % "1.2" % "test"
  val logbackcore = "ch.qos.logback" % "logback-core" % "0.9.24" % "test"
  val logbackclassic = "ch.qos.logback" % "logback-classic" % "0.9.24" % "test"
}
