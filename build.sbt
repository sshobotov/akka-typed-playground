name := "Test assignment"

scalaVersion := "2.12.12"

Compile / run / mainClass := Some("xite.assignment.Application")

scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation", "-Ywarn-value-discard")

val akkaVersion = "2.5.13"
val akkaHttpVersion = "10.1.3"

resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed"   % akkaVersion,
  "com.typesafe.akka" %% "akka-stream"        % akkaVersion,
  "com.typesafe.akka" %% "akka-http"          % akkaHttpVersion,
  "de.heikoseeberger" %% "akka-http-circe"    % "1.21.0",
  "io.circe"          %% "circe-generic"      % "0.9.3",
  "org.typelevel"     %% "cats-core"          % "1.1.0",

  "org.scalatest"     %% "scalatest"          % "3.0.5" % Test,
  "com.typesafe.akka" %% "akka-http-testkit"  % akkaHttpVersion % Test
)
