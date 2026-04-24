name := "stream-router"

version := "0.1.0"

scalaVersion := "3.4.2"

cancelable in Global := true

resolvers ++= Resolver.sonatypeOssRepos("snapshots")

// ── Version pins ─────────────────────────────────────────────────────────────
val pekkoVersion     = "1.1.2"
val pekkoHttpVersion = "1.1.0"

enablePlugins(PekkoGrpcPlugin)

// ── Dependencies ──────────────────────────────────────────────────────────────
libraryDependencies ++= Seq(
  // Core Pekko libraries (Apache 2.0 - open source fork)
  "org.apache.pekko" %% "pekko-actor-typed"               % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream"                    % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream-typed"              % pekkoVersion,
  "org.apache.pekko" %% "pekko-http"                      % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-core"                 % pekkoHttpVersion,
  
  // Cluster and discovery
  "org.apache.pekko" %% "pekko-discovery"                 % pekkoVersion,
  "org.apache.pekko" %% "pekko-remote"                    % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster"                   % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-typed"             % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-sharding-typed"    % pekkoVersion,
  
  // Serialization and logging
  "org.apache.pekko" %% "pekko-slf4j"                     % pekkoVersion,
  "org.apache.pekko" %% "pekko-serialization-jackson"     % pekkoVersion,
  "com.typesafe.scala-logging" %% "scala-logging"         % "3.9.5",
  "ch.qos.logback"              % "logback-classic"       % "1.5.6",
  
  // Kafka
  "org.apache.kafka"            % "kafka-clients"         % "3.7.0",
  
  // Utilities
  ("com.lihaoyi" %% "fansi" % "0.4.0")
    .cross(CrossVersion.for3Use2_13)
)
