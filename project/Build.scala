import sbt._
import Keys._

object BuildSettings {
    import Dependencies._
    import Resolvers._

    val buildOrganization = "com.typesafe"
    val buildVersion = "1.0"
    val buildScalaVersion = "2.9.0-1"

    val globalSettings = Seq(
        organization := buildOrganization,
        version := buildVersion,
        scalaVersion := buildScalaVersion,
        scalacOptions += "-deprecation",
        fork in test := true,
        libraryDependencies ++= Seq(slf4jSimpleTest, scalatest),
        resolvers := Seq(scalaToolsRepo, jbossRepo,
                         akkaRepo, sonatypeRepo))

    val projectSettings = Defaults.defaultSettings ++ globalSettings
}

object Resolvers {
    val sonatypeRepo = "Sonatype Release" at "http://oss.sonatype.org/content/repositories/releases"
    val scalaToolsRepo = "Scala Tools" at "http://scala-tools.org/repo-snapshots/"
    val jbossRepo = "JBoss" at "http://repository.jboss.org/nexus/content/groups/public/"
    val akkaRepo = "Akka" at "http://akka.io/repository/"
}

object Dependencies {
    val scalatest = "org.scalatest" %% "scalatest" % "1.6.1" % "test"
    val slf4jSimpleTest = "org.slf4j" % "slf4j-simple" % "1.6.2" % "test"

    val jettyVersion = "7.3.1.v20110307"
    val jettyServer = "org.eclipse.jetty" % "jetty-server" % jettyVersion
    val jettyServerTest = jettyServer % "test"

    val akka = "se.scalablesolutions.akka" % "akka-actor" % "1.2-RC6"

    val asyncHttp = "com.ning" % "async-http-client" % "1.6.5"

    val jsoup = "org.jsoup" % "jsoup" % "1.6.1"
}

object WebWordsBuild extends Build {
    import BuildSettings._
    import Dependencies._
    import Resolvers._

    override lazy val settings = super.settings ++ globalSettings

    lazy val root = Project("webwords",
                            file("."),
                            settings = projectSettings ++
                            Seq()) aggregate(common, web, indexer)

    lazy val web = Project("webwords-web",
                           file("web"),
                           settings = projectSettings ++
                           Seq(libraryDependencies ++= Seq(jettyServer))) dependsOn(common)

    lazy val indexer = Project("webwords-indexer",
                              file("indexer"),
                              settings = projectSettings ++
                              Seq(libraryDependencies ++= Seq(jsoup))) dependsOn(common)

    lazy val common = Project("webwords-common",
                           file("common"),
                           settings = projectSettings ++
                           Seq(libraryDependencies ++= Seq(akka, asyncHttp, jettyServerTest)))
}

