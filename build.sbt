ThisBuild / organization         := "com.fortemate"
ThisBuild / organizationName     := "Fortemate"
ThisBuild / organizationHomepage := Some(uri("https://fortemate.com"))
ThisBuild / homepage             := Some(uri("https://fortemate.com"))
ThisBuild / startYear            := Some(2026)
ThisBuild / version              := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion         := "3.8.4"

ThisBuild / description := "Authoritative real-time server for Dice Chess (human-vs-human + Bot API + Glicko-2 rating ladder)."
ThisBuild / licenses := List(License("AGPL-3.0", uri("https://www.gnu.org/licenses/agpl-3.0.txt")))

ThisBuild / scmInfo := Some(
  ScmInfo(
    uri("https://github.com/fortemate/dicechess-play-api"),
    "scm:git@github.com:fortemate/dicechess-play-api.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id = "rabestro",
    name = "Jegors Čemisovs",
    email = "jegors.cemisovs@gmail.com",
    url = uri("https://fortemate.com")
  )
)

// The engine artifact is published on Maven Central and GitHub Packages
ThisBuild / resolvers ++= Seq(
  Resolver.mavenCentral,
  "GitHub Packages (fortemate)" at "https://maven.pkg.github.com/fortemate/dicechess-engine"
)

def ghValue(envVar: String, ghArgs: String*): Option[String] =
  sys.env
    .get(envVar)
    .filter(_.nonEmpty)
    .orElse(scala.util.Try(scala.sys.process.Process("gh" +: ghArgs).!!.trim).toOption)
    .filter(_.nonEmpty)

ThisBuild / credentials ++= (for {
  token <- ghValue("GITHUB_TOKEN", "auth", "token")
  user = sys.env.get("GITHUB_ACTOR").filter(_.nonEmpty).getOrElse("git")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

val DiceChessEngineVersion    = "0.6.0"
val CatsEffectVersion         = "3.7.1"
val Fs2Version                = "3.13.0"
val Http4sVersion             = "0.23.30"
val CirceVersion              = "0.14.10"
val LogbackVersion            = "1.6.3"
val Http4sJdkClientVersion    = "0.10.0"
val MunitVersion              = "1.3.5"
val MunitCatsEffectVersion    = "2.2.0"
val DoobieVersion             = "1.0.0-RC9"
val FlywayVersion             = "13.4.0"
val JavaJwtVersion            = "4.6.0"
val JwksRsaVersion            = "0.24.1"
val PostgresDriverVersion     = "42.7.13"
val TestcontainersVersion     = "0.43.0"
val TestcontainersJavaVersion = "1.21.3"
val DockerJavaVersion         = "3.7.1"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name                := "dicechess-play-api",
    Compile / mainClass := Some("dicechess.play.Main"),
    libraryDependencies ++= Seq(
      // Game rules: official Fortemate Dice Chess engine
      "com.fortemate" %% "dicechess-engine" % DiceChessEngineVersion,
      // Effect system + streaming/concurrency primitives (Ref, Queue, Topic)
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "co.fs2"        %% "fs2-core"    % Fs2Version,
      // HTTP / WebSocket server + JSON
      "org.http4s" %% "http4s-ember-server" % Http4sVersion,
      "org.http4s" %% "http4s-ember-client" % Http4sVersion,
      "org.http4s" %% "http4s-dsl"          % Http4sVersion,
      "org.http4s" %% "http4s-circe"        % Http4sVersion,
      "io.circe"   %% "circe-core"          % CirceVersion,
      "io.circe"   %% "circe-generic"       % CirceVersion,
      "io.circe"   %% "circe-parser"        % CirceVersion,
      // Persistence: PostgreSQL + Doobie + Flyway
      "org.tpolecat"  %% "doobie-core"                % DoobieVersion,
      "org.tpolecat"  %% "doobie-hikari"              % DoobieVersion,
      "org.tpolecat"  %% "doobie-postgres"            % DoobieVersion,
      "org.tpolecat"  %% "doobie-postgres-circe"      % DoobieVersion,
      "org.flywaydb"   % "flyway-database-postgresql" % FlywayVersion,
      "org.postgresql" % "postgresql"                 % PostgresDriverVersion,
      // Google sign-in: HMAC session JWTs + RS256 id_token verification against Google's JWKS
      "com.auth0" % "java-jwt" % JavaJwtVersion,
      "com.auth0" % "jwks-rsa" % JwksRsaVersion,
      // Logging backend for Ember
      "ch.qos.logback" % "logback-classic" % LogbackVersion % Runtime,
      // Testing
      "org.scalameta" %% "munit"                  % MunitVersion           % Test,
      "org.typelevel" %% "munit-cats-effect"      % MunitCatsEffectVersion % Test,
      "org.http4s"    %% "http4s-jdk-http-client" % Http4sJdkClientVersion % Test,
      // PostgreSQL Testcontainers
      "com.dimafeng"          %% "testcontainers-scala-munit"      % TestcontainersVersion     % Test,
      "com.dimafeng"          %% "testcontainers-scala-postgresql" % TestcontainersVersion     % Test,
      "org.testcontainers"     % "testcontainers"                  % TestcontainersJavaVersion % Test,
      "org.testcontainers"     % "postgresql"                      % TestcontainersJavaVersion % Test,
      "com.github.docker-java" % "docker-java-api"                 % DockerJavaVersion         % Test,
      "com.github.docker-java" % "docker-java-transport-zerodep"   % DockerJavaVersion         % Test
    ),
    scalacOptions ++= Seq(
      "-Werror",
      "-Wunused:all",
      "-deprecation",
      "-feature",
      "-explain"
    ),
    coverageExcludedFiles    := ".*Main\\.scala",
    coverageFailOnMinimum    := false,
    Test / fork              := true,
    Test / parallelExecution := false,
    Test / javaOptions += "-Dapi.version=1.43"
  )
