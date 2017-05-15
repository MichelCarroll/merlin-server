name := "merlin-ws-server"

version := "1.0"

scalaVersion := "2.11.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http" % "10.0.6",
  "com.lihaoyi" %% "upickle" % "0.4.3"
)