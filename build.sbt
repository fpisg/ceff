name := "cats-effect-tutorial"

version := "1.0"

scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "1.3.0" withSources() withJavadoc(),
  "org.typelevel" %% "cats-core" % "2.0.0-M1" withSources() withJavadoc())

scalacOptions ++=
  Seq(
    "-feature",
    "-deprecation",
    "-unchecked",
    "-language:postfixOps",
    "-language:higherKinds",
    "-Ypartial-unification")


