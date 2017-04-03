import sbt.ScriptedPlugin
import sbt.ScriptedPlugin._

import Dependencies._
organization in ThisBuild := "ch.epfl.scala"

lazy val crossVersions = Seq(scala211, scala212)

lazy val compilerOptions = Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-unchecked",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-language:existentials",
//  "-Ywarn-numeric-widen", // TODO(olafur) enable
  "-Xfuture",
  "-Xlint"
)

lazy val gitPushTag = taskKey[Unit]("Push to git tag")

// Custom scalafix release command. Tried sbt-release but didn't play well with sbt-doge.
commands += Command.command("release") { s =>
  "clean" ::
    "very publishSigned" ::
    "sonatypeRelease" ::
    "gitPushTag" ::
    s
}

commands += Command.command("ci-fast") { s =>
  "clean" ::
    s"plz $ciScalaVersion testQuick" ::
    s
}

commands += Command.command("ci-slow") { s =>
  "very publishLocal" ::
    s"wow ${ciScalaVersion.get} scalafix-tests/test" ::
    "very scalafix-sbt/scripted" ::
    s
}

lazy val publishSettings = Seq(
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  publishArtifact in Test := false,
  licenses := Seq(
    "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  homepage := Some(url("https://github.com/scalacenter/scalafix")),
  autoAPIMappings := true,
  apiURL := Some(url("https://scalacenter.github.io/scalafix/")),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/scalacenter/scalafix"),
      "scm:git:git@github.com:scalacenter/scalafix.git"
    )
  ),
  pomExtra :=
    <developers>
      <developer>
        <id>olafurpg</id>
        <name>Ólafur Páll Geirsson</name>
        <url>https://geirsson.com</url>
      </developer>
    </developers>
)

lazy val noPublish = Seq(
  publishArtifact := false,
  publish := {},
  publishLocal := {}
)

lazy val buildInfoSettings: Seq[Def.Setting[_]] = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    version,
    "stableVersion" -> "0.3.1",
    "scalameta" -> scalametaV,
    scalaVersion,
    "scala211" -> scala211,
    "scala212" -> scala212,
    sbtVersion
  ),
  buildInfoPackage := "scalafix",
  buildInfoObject := "Versions"
)

lazy val allSettings = List(
  resolvers += Resolver.bintrayIvyRepo("scalameta", "maven"),
  triggeredMessage in ThisBuild := Watched.clearWhenTriggered,
  scalacOptions := compilerOptions,
  scalacOptions in (Compile, console) := compilerOptions :+ "-Yrepl-class-based",
  test in assembly := {},
  libraryDependencies += scalatest % Test,
  testOptions in Test += Tests.Argument("-oD"),
  assemblyJarName in assembly := "scalafix.jar",
  scalaVersion := ciScalaVersion.getOrElse(scala211),
  crossScalaVersions := crossVersions,
  updateOptions := updateOptions.value.withCachedResolution(true)
) ++ publishSettings

allSettings

noPublish

gitPushTag := {
  val tag = s"v${version.value}"
  assert(!tag.endsWith("SNAPSHOT"))
  import sys.process._
  Seq("git", "tag", "-a", tag, "-m", tag).!!
  Seq("git", "push", "--tags").!!
}

// settings to projects using @metaconfig.ConfigReader annotation.
lazy val metaconfigSettings: Seq[Def.Setting[_]] = Seq(
  addCompilerPlugin(
    ("org.scalameta" % "paradise" % paradiseV).cross(CrossVersion.full)),
  scalacOptions += "-Xplugin-require:macroparadise",
  scalacOptions in (Compile, console) := Seq(), // macroparadise plugin doesn't work in repl yet.
  sources in (Compile, doc) := Nil // macroparadise doesn't work with scaladoc yet.
)

lazy val core = project
  .settings(
    allSettings,
    buildInfoSettings,
    metaconfigSettings,
    isFullCrossVersion,
    moduleName := "scalafix-core",
    dependencyOverrides += scalameta,
    libraryDependencies ++= Seq(
      "com.typesafe" % "config"      % "1.3.1",
      "com.lihaoyi"  %% "sourcecode" % "0.1.3",
      metaconfig,
      scalameta,
      scalahost,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value
    )
  )
  .dependsOn(`scalafix-testutils` % Test)
  .enablePlugins(BuildInfoPlugin)

lazy val `scalafix-nsc` = project
  .settings(
    allSettings,
    isFullCrossVersion,
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      scalahostNsc,
      ammonite % Test,
      // integration property tests
      "org.typelevel"      %% "catalysts-platform" % "0.0.5"    % Test,
      "com.typesafe.slick" %% "slick"              % "3.2.0-M2" % Test,
      "com.chuusai"        %% "shapeless"          % "2.3.2"    % Test,
      "org.scalacheck"     %% "scalacheck"         % "1.13.4"   % Test
    ),
    // sbt does not fetch transitive dependencies of compiler plugins.
    // to overcome this issue, all transitive dependencies are included
    // in the published compiler plugin.
    publishArtifact in Compile := true,
    assemblyMergeStrategy in assembly := {
      // conflicts with scalahost plugin
      case "scalac-plugin.xml" => MergeStrategy.first
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    },
    assemblyJarName in assembly :=
      name.value + "_" +
        scalaVersion.value + "-" +
        version.value + "-assembly.jar",
    assemblyOption in assembly ~= { _.copy(includeScala = false) },
    Keys.`package` in Compile := {
      val slimJar = (Keys.`package` in Compile).value
      val fatJar =
        new File(crossTarget.value + "/" + (assemblyJarName in assembly).value)
      val _ = assembly.value
      IO.copy(List(fatJar -> slimJar), overwrite = true)
      slimJar
    },
    packagedArtifact in Compile in packageBin := {
      val temp = (packagedArtifact in Compile in packageBin).value
      val (art, slimJar) = temp
      val fatJar =
        new File(crossTarget.value + "/" + (assemblyJarName in assembly).value)
      val _ = assembly.value
      IO.copy(List(fatJar -> slimJar), overwrite = true)
      (art, slimJar)
    },
    exposePaths("scalafixNsc", Test)
  )
  .dependsOn(core, `scalafix-testutils` % Test)

lazy val cli = project
  .settings(
    allSettings,
    isFullCrossVersion,
    moduleName := "scalafix-cli",
    fork.in(Test, test) := true,
    baseDirectory.in(test) := file("."),
    javaOptions.in(test) +=
      s"-Dscalafix.scalahost.pluginpath=${scalahostNscPluginPath.value}",
    mainClass in assembly := Some("scalafix.cli.Cli"),
    libraryDependencies ++= Seq(
      "com.github.scopt"           %% "scopt"         % "3.5.0",
      "com.github.alexarchambault" %% "case-app"      % "1.1.3",
      "com.martiansoftware"        % "nailgun-server" % "0.9.1"
    )
  )
  .dependsOn(core, `scalafix-testutils` % Test)
lazy val fatcli = project
  .settings(
    allSettings,
    isFullCrossVersion,
    moduleName := "scalafix-fatcli",
    libraryDependencies += scalahostNsc
  )
  .dependsOn(cli)

lazy val publishedArtifacts = Seq(
  publishLocal in `scalafix-nsc`,
  publishLocal in core
)

lazy val `scalafix-sbt` = project
  .settings(
    allSettings,
    buildInfoSettings,
    ScriptedPlugin.scriptedSettings,
    sbtPlugin := true,
    // Doesn't work because we need to publish 2.11 and 2.12.
//    scripted := scripted.dependsOn(publishedArtifacts: _*).evaluated,
    testQuick := {
      RunSbtCommand(
        s"; very publishLocal " +
          "; very scalafix-sbt/scripted sbt-scalafix/config"
      )(state.value)
    },
    test := {
      RunSbtCommand(
        "; very publishLocal " +
          "; very scalafix-sbt/scripted"
      )(state.value)
    },
    scalaVersion := scala210,
    crossScalaVersions := Seq(scala210),
    moduleName := "sbt-scalafix",
    scriptedLaunchOpts ++= Seq(
      "-Dplugin.version=" + version.value,
      // .jvmopts is ignored, simulate here
      "-XX:MaxPermSize=256m",
      "-Xmx2g",
      "-Xss2m"
    ),
    scriptedBufferLog := false
  )
  .enablePlugins(BuildInfoPlugin)

lazy val `scalafix-testutils` = project.settings(
  allSettings,
  noPublish,
  libraryDependencies ++= Seq(
    "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0",
    scalatest
  )
)

lazy val `scalafix-tests` = project
  .settings(
    allSettings,
    noPublish,
    testQuick := {}, // these tests are slow.
    parallelExecution in Test := true,
    libraryDependencies ++= Seq(
      ammonite
    )
  )
  .dependsOn(core)

lazy val readme = scalatex
  .ScalatexReadme(projectId = "readme",
                  wd = file(""),
                  url = "https://github.com/scalacenter/scalafix/tree/master",
                  source = "Readme")
  .settings(
    allSettings,
    noPublish,
    libraryDependencies ++= Seq(
      "com.twitter" %% "util-eval" % "6.42.0"
    )
  )
  .dependsOn(core, cli)

// Injects necessary paths into system properties to build a scalac global in tests.
def exposePaths(projectName: String,
                config: Configuration): Seq[Def.Setting[_]] = {
  def uncapitalize(s: String) =
    if (s.length == 0) ""
    else {
      val chars = s.toCharArray; chars(0) = chars(0).toLower; new String(chars)
    }
  val prefix = "sbt.paths." + projectName + "." + uncapitalize(config.name) + "."
  Seq(
    sourceDirectory in config := {
      val defaultValue = (sourceDirectory in config).value
      System.setProperty(prefix + "sources", defaultValue.getAbsolutePath)
      defaultValue
    },
    resourceDirectory in config := {
      val defaultValue = (resourceDirectory in config).value
      System.setProperty(prefix + "resources", defaultValue.getAbsolutePath)
      defaultValue
    },
    fullClasspath in config := {
      val defaultValue = (fullClasspath in config).value
      val classpath = defaultValue.files.map(_.getAbsolutePath)
      val scalaLibrary =
        classpath.map(_.toString).find(_.contains("scala-library")).get
      System.setProperty("sbt.paths.scalalibrary.classes", scalaLibrary)
      System.setProperty(prefix + "classes",
                         classpath.mkString(java.io.File.pathSeparator))
      defaultValue
    }
  )
}

lazy val isFullCrossVersion = Seq(
  crossVersion := CrossVersion.full
)

lazy val scalahostNscPluginPath = Def.task {
  val files = update
    .in(dummyScalahostProject)
    .value
    .allFiles
  val path = files.find { file =>
    val path = file.getAbsolutePath
    path.endsWith(s"scalahost-nsc_${scalaVersion.value}.jar")
  }.get
  path.getAbsolutePath
}

// sbt makes it hard to do simple stuff like get the jar of a dependency.
lazy val dummyScalahostProject = project
  .in(file("target/dummy"))
  .settings(
    allSettings,
    noPublish,
    description := "Just a project that has scalahost-nsc on the classpath.",
    libraryDependencies += scalahostNsc
  )

lazy val ciScalaVersion = sys.env.get("CI_SCALA_VERSION")
lazy val scala210 = "2.10.6"
lazy val scala211 = "2.11.8"
lazy val scala212 = "2.12.1"