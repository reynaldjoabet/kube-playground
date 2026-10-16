import sbt.*

object Dependencies {

  private object Versions {

    // --- ZIO ecosystem ---
    val zio        = "2.1.26"
    val zioJson    = "0.9.2"
    val zioHttp    = "3.11.6"
    val zioLogging = "2.5.3"
    val zioConfig  = "4.0.7"
    val zioSchema  = "1.8.5"
    val zioKafka   = "3.8.0"

    // --- HTTP ---
    val http4s  = "0.23.37"
    val sttp4   = "4.0.26"
    val tapir   = "1.13.18"
    val jsonRpc = "0.2.0"

    // --- JSON ---
    val jsoniter = "2.40.1"
    val circe    = "0.14.15"

    // --- FP ---
    val catsEffect      = "3.7.1"
    val fs2             = "3.14.0"
    val fs2Kafka        = "4.1.3"
    val chimney         = "2.1.0"
    val iron            = "3.3.1"
    val uuidGenerator   = "5.2.0"
    val hedgehog        = "0.13.0"
    val scalacheck      = "1.20.0"
    val munit           = "1.3.6"
    val munitCatsEffect = "2.2.1"

    // --- DB ---
    val quill    = "4.8.6"
    val magnum   = "2.0.0-M3"
    val skunk    = "2.0.0-RC3"
    val hikaricp = "7.1.0"
    val flyway   = "13.10.0"
    val postgres = "42.7.14"

    // --- Security ---
    val jwtScala         = "11.0.4"
    val bouncycastle     = "1.84"
    val password4j       = "1.8.4"
    val auth0            = "4.6.2"
    val nimbusJoseJwt    = "10.10"
    val nimbusOauth2Oidc = "11.38.2"
    val vault            = "5.1.0"

    // --- Logging ---
    val scribe  = "3.19.0"
    val slf4j   = "2.0.20"
    val logback = "1.6.5"

    // --- Cache ---
    val caffeine = "3.3.1"

    // --- Observability ---
    val datadog = "2.62.0"
    val kamon   = "2.7.7"
    val otel4s  = "1.0.0"

    // --- Config ---
    val pureconfig = "0.17.10"

    // --- Cloud ---
    val awsV2         = "2.26.15"
    val azureIdentity = "1.18.7"
    val azureKv       = "4.9.4"

  }

  // Helpers
  private def http4sDep(a: String) =
    "org.http4s" %% s"http4s-$a" % Versions.http4s

  private def tapirDep(a: String) =
    "com.softwaremill.sttp.tapir" %% s"tapir-$a" % Versions.tapir

  private def sttpDep(a: String) =
    "com.softwaremill.sttp.client4" %% a % Versions.sttp4

  private def circeDep(a: String) =
    "io.circe" %% s"circe-$a" % Versions.circe

  private def awsDep(a: String) =
    "software.amazon.awssdk" % a % Versions.awsV2

  object core {

    val zio        = "dev.zio"       %% "zio"         % Versions.zio
    val catsEffect = "org.typelevel" %% "cats-effect" % Versions.catsEffect
    val fs2        = "co.fs2"        %% "fs2-core"    % Versions.fs2

  }

  object http {

    object http4s {

      val core  = Seq(http4sDep("dsl"), http4sDep("ember-server"), http4sDep("ember-client"))
      val circe = http4sDep("circe")

    }

    object sttp {

      val core     = sttpDep("core")
      val jsoniter = sttpDep("jsoniter")
      val circe    = sttpDep("circe")
      val cats     = sttpDep("cats")
      val fs2      = sttpDep("fs2")
      val zio      = sttpDep("zio")

      object backends {

        val http4s             = sttpDep("http4s-backend")
        val okhttp             = sttpDep("okhttp-backend")
        val asyncHttpClientFs2 = sttpDep("async-http-client-backend-fs2")
        val slf4j              = sttpDep("slf4j-backend")
        val prometheus         = sttpDep("prometheus-backend")
        val scribe             = sttpDep("scribe-backend")

      }

    }

    object tapir {

      val core         = tapirDep("core")
      val http4sServer = tapirDep("http4s-server")
      val jsoniter     = tapirDep("jsoniter-scala")
      val openapiDocs  = tapirDep("openapi-docs")
      val swaggerUi    = tapirDep("swagger-ui-bundle")
      val iron         = tapirDep("iron")

    }

    val zio     = "dev.zio"      %% "zio-http"       % Versions.zioHttp
    val jsonRpc = "tech.neander" %% "jsonrpclib-fs2" % Versions.jsonRpc

  }

  object json {

    object jsoniter {

      // The macros are compile-time only and always needed alongside core.
      val core = Seq(
        "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"   % Versions.jsoniter,
        "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % Versions.jsoniter % Provided
      )

      val circe =
        "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-circe" % Versions.jsoniter

    }

    object circe {

      val core    = circeDep("core")
      val generic = circeDep("generic")
      val parser  = circeDep("parser")

    }

    val zio = "dev.zio" %% "zio-json" % Versions.zioJson

  }

  object domain {

    val chimney       = "io.scalaland"      %% "chimney"             % Versions.chimney
    val uuidGenerator = "com.fasterxml.uuid" % "java-uuid-generator" % Versions.uuidGenerator

    object iron {

      val core       = "io.github.iltotore" %% "iron"            % Versions.iron
      val zioJson    = "io.github.iltotore" %% "iron-zio-json"   % Versions.iron
      val jsoniter   = "io.github.iltotore" %% "iron-jsoniter"   % Versions.iron
      val chimney    = "io.github.iltotore" %% "iron-chimney"    % Versions.iron
      val doobie     = "io.github.iltotore" %% "iron-doobie"     % Versions.iron
      val skunk      = "io.github.iltotore" %% "iron-skunk"      % Versions.iron
      val pureconfig = "io.github.iltotore" %% "iron-pureconfig" % Versions.iron

    }

    val zioSchema = Seq(
      "dev.zio" %% "zio-schema"            % Versions.zioSchema,
      "dev.zio" %% "zio-schema-json"       % Versions.zioSchema,
      "dev.zio" %% "zio-schema-derivation" % Versions.zioSchema,
      "dev.zio" %% "zio-schema-protobuf"   % Versions.zioSchema
    )

  }

  object logging {

    val slf4j   = "org.slf4j"      % "slf4j-api"       % Versions.slf4j
    val logback = "ch.qos.logback" % "logback-classic" % Versions.logback

    object scribe {

      val core  = "com.outr" %% "scribe"       % Versions.scribe
      val slf4j = "com.outr" %% "scribe-slf4j" % Versions.scribe
      val cats  = "com.outr" %% "scribe-cats"  % Versions.scribe

    }

    val zio = Seq(
      "dev.zio" %% "zio-logging"       % Versions.zioLogging,
      "dev.zio" %% "zio-logging-slf4j" % Versions.zioLogging
    )

  }

  object config {

    object pureconfig {

      val core = "com.github.pureconfig" %% "pureconfig-core" % Versions.pureconfig

      val generic = "com.github.pureconfig" %% "pureconfig-generic-scala3" % Versions.pureconfig

    }

    val zio = Seq(
      "dev.zio" %% "zio-config"          % Versions.zioConfig,
      "dev.zio" %% "zio-config-magnolia" % Versions.zioConfig,
      "dev.zio" %% "zio-config-typesafe" % Versions.zioConfig
    )

  }

  object database {

    val postgres = "org.postgresql"   % "postgresql"     % Versions.postgres
    val flyway   = "org.flywaydb"     % "flyway-core"    % Versions.flyway
    val hikaricp = "com.zaxxer"       % "HikariCP"       % Versions.hikaricp
    val skunk    = "org.tpolecat"    %% "skunk-core"     % Versions.skunk
    val quill    = "io.getquill"     %% "quill-jdbc-zio" % Versions.quill
    val magnum   = "com.augustnagro" %% "magnum"         % Versions.magnum

  }

  object kafka {

    val fs2 = "org.typelevel" %% "fs2-kafka" % Versions.fs2Kafka
    val zio = "dev.zio"       %% "zio-kafka" % Versions.zioKafka

  }

  object cache {

    val caffeine = "com.github.ben-manes.caffeine" % "caffeine" % Versions.caffeine

  }

  object security {

    object nimbus {

      val joseJwt    = "com.nimbusds" % "nimbus-jose-jwt" % Versions.nimbusJoseJwt
      val oauth2Oidc = "com.nimbusds" % "oauth2-oidc-sdk" % Versions.nimbusOauth2Oidc

    }

    object jwtScala {

      val zioJson = "com.github.jwt-scala" %% "jwt-zio-json" % Versions.jwtScala
      val circe   = "com.github.jwt-scala" %% "jwt-circe"    % Versions.jwtScala

    }

    object bouncycastle {

      val pkix     = "org.bouncycastle" % "bcpkix-jdk18on" % Versions.bouncycastle
      val provider = "org.bouncycastle" % "bcprov-jdk18on" % Versions.bouncycastle

    }

    val auth0       = "com.auth0"           % "java-jwt"          % Versions.auth0
    val password4j  = "com.password4j"      % "password4j"        % Versions.password4j
    val pemKeystore = "de.dentrassi.crypto" % "pem-keystore"      % "3.0.0"
    val vault       = "com.bettercloud"     % "vault-java-driver" % Versions.vault

  }

  object cloud {

    object aws {

      val ec2            = awsDep("ec2")
      val iam            = awsDep("iam")
      val rds            = awsDep("rds")
      val kms            = awsDep("kms")
      val secretsManager = awsDep("secretsmanager")
      val sts            = awsDep("sts")

    }

    object azure {

      val identity        = "com.azure" % "azure-identity"                  % Versions.azureIdentity
      val keyVaultKeys    = "com.azure" % "azure-security-keyvault-keys"    % Versions.azureKv
      val keyVaultSecrets = "com.azure" % "azure-security-keyvault-secrets" % Versions.azureKv

    }

  }

  object observability {

    object kamon {

      val bundle     = "io.kamon" %% "kamon-bundle"     % Versions.kamon
      val prometheus = "io.kamon" %% "kamon-prometheus" % Versions.kamon

    }

    val otel4s = Seq(
      "org.typelevel" %% "otel4s-core"     % Versions.otel4s,
      "org.typelevel" %% "otel4s-otlp"     % Versions.otel4s,
      "org.typelevel" %% "otel4s-otelJava" % Versions.otel4s
    )

    val datadog =
      ("com.datadoghq" % "datadog-api-client" % Versions.datadog).classifier("shaded-jar")

  }

  object testing {

    val munit = "org.scalameta" %% "munit" % Versions.munit % Test

    val munitCatsEffect = "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect % Test

    val scalacheck = "org.scalacheck" %% "scalacheck" % Versions.scalacheck % Test

    val hedgehog = Seq(
      "qa.hedgehog" %% "hedgehog-core" % Versions.hedgehog % Test,
      "qa.hedgehog" %% "hedgehog-sbt"  % Versions.hedgehog % Test
    )

    val zio = Seq(
      "dev.zio" %% "zio-test"     % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test
    )

    val zioJsonGolden = "dev.zio" %% "zio-json-golden" % Versions.zioJson % Test

  }

  // Tasks
  lazy val generate = taskKey[Seq[File]]("generate code from APIs")

}
