import org.scalajs.sbtplugin.cross.CrossProject

import xerial.sbt.Sonatype.sonatypeSettings
import sbtrelease.ReleaseStateTransformations._
import sys.process.Process

scalafmtOnCompile in ThisBuild := true

val ScalaVersion = "2.11.11"

lazy val root = Project(id = "scalacache", base = file("."))
  .enablePlugins(ReleasePlugin)
  .settings(
    commonSettings,
    sonatypeSettings,
    publishArtifact := false
  )
  .aggregate(coreJS, coreJVM, guava, memcached, ehcache, redis, caffeine)

lazy val core =
  CrossProject(id = "core", file("modules/core"), CrossType.Full)
    .settings(commonSettings)
    .settings(
      moduleName := "scalacache-core",
      libraryDependencies ++= Seq(
        "org.scala-lang" % "scala-reflect" % scalaVersion.value,
        "org.scalacheck" %% "scalacheck" % "1.13.4" % Test
      ),
      scala211OnlyDeps(
        "org.squeryl" %% "squeryl" % "0.9.9" % Test,
        "com.h2database" % "h2" % "1.4.196" % Test
      )
    )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js

def module(name: String) =
  Project(id = name, base = file(s"modules/$name"))
    .settings(commonSettings)
    .settings(moduleName := s"scalacache-$name")
    .dependsOn(coreJVM % "test->test;compile->compile")

lazy val guava = module("guava")
  .settings(
    libraryDependencies ++= Seq(
      "com.google.guava" % "guava" % "19.0",
      "com.google.code.findbugs" % "jsr305" % "1.3.9"
    )
  )

lazy val memcached = module("memcached")
  .settings(
    libraryDependencies ++= Seq(
      "net.spy" % "spymemcached" % "2.12.3"
    )
  )

lazy val ehcache = module("ehcache")
  .settings(
    libraryDependencies ++= Seq(
      "net.sf.ehcache" % "ehcache" % "2.10.4",
      "javax.transaction" % "jta" % "1.1"
    )
  )

lazy val redis = module("redis")
  .settings(
    libraryDependencies ++= Seq(
      "redis.clients" % "jedis" % "2.9.0"
    )
  )

lazy val caffeine = module("caffeine")
  .settings(
    libraryDependencies ++= Seq(
      "com.github.ben-manes.caffeine" % "caffeine" % "2.5.6",
      "com.google.code.findbugs" % "jsr305" % "3.0.0" % Provided
    )
  )

lazy val benchmarks = module("benchmarks")
  .enablePlugins(JmhPlugin)
  .settings(
    scalaVersion := ScalaVersion,
    publishArtifact := false,
    fork in (Compile, run) := true,
    javaOptions in Jmh ++= Seq("-server",
                               "-Xms2G",
                               "-Xmx2G",
                               "-XX:+UseG1GC",
                               "-XX:-UseBiasedLocking"),
    javaOptions in (Test, run) ++= Seq(
      "-XX:+UnlockCommercialFeatures",
      "-XX:+FlightRecorder",
      "-XX:StartFlightRecording=delay=20s,duration=60s,filename=memoize.jfr",
      "-server",
      "-Xms2G",
      "-Xmx2G",
      "-XX:+UseG1GC",
      "-XX:-UseBiasedLocking"
    )
  )
  .dependsOn(caffeine)

lazy val slf4j = Seq(
  "org.slf4j" % "slf4j-api" % "1.7.25"
)

lazy val scalaTest = Seq(
  "org.scalatest" %% "scalatest" % "3.0.4" % Test
)

// Dependencies common to all projects
lazy val commonDeps = slf4j ++ scalaTest

lazy val commonSettings =
  mavenSettings ++
    Seq(
      organization := "com.github.cb372",
      scalaVersion := ScalaVersion,
      crossScalaVersions := Seq(ScalaVersion, "2.12.4"),
      scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
      resolvers += Resolver.typesafeRepo("releases"),
      libraryDependencies ++= commonDeps,
      parallelExecution in Test := false,
      releasePublishArtifactsAction := PgpKeys.publishSigned.value,
      releaseCrossBuild := true,
      releaseProcess := Seq[ReleaseStep](
        checkSnapshotDependencies,
        inquireVersions,
        runClean,
        runTest,
        setReleaseVersion,
        commitReleaseVersion,
        updateVersionInReadme,
        tagRelease,
        publishArtifacts,
        setNextVersion,
        commitNextVersion,
        releaseStepCommand("sonatypeReleaseAll"),
        pushChanges
      ),
      commands += Command.command("update-version-in-readme")(
        updateVersionInReadme)
    )

lazy val mavenSettings = Seq(
  pomExtra :=
    <url>https://github.com/cb372/scalacache</url>
    <licenses>
      <license>
        <name>Apache License, Version 2.0</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.html</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:cb372/scalacache.git</url>
      <connection>scm:git:git@github.com:cb372/scalacache.git</connection>
    </scm>
    <developers>
      <developer>
        <id>cb372</id>
        <name>Chris Birchall</name>
        <url>https://github.com/cb372</url>
      </developer>
    </developers>,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ =>
    false
  }
)

lazy val updateVersionInReadme = ReleaseStep(action = st => {
  val extracted = Project.extract(st)
  val projectVersion = extracted.get(Keys.version)

  println(s"Updating project version to $projectVersion in the README")
  Process(
    Seq(
      "sed",
      "-i",
      "",
      "-E",
      "-e",
      s"""s/"scalacache-(.*)" % ".*"/"scalacache-\\1" % "$projectVersion"/g""",
      "README.md")).!
  println("Committing README.md")
  Process(
    Seq("git",
        "commit",
        "README.md",
        "-m",
        s"Update project version in README to $projectVersion")).!

  st
})

def scala211OnlyDeps(moduleIDs: ModuleID*) =
  libraryDependencies ++= (scalaBinaryVersion.value match {
    case "2.11" => moduleIDs
    case other => Nil
  })
