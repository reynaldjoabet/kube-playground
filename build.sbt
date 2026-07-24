import Dependencies.*

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions ++= Seq(
  // "-no-indent",
  "-deprecation", // Warns about deprecated APIs
  "-feature", // Warns about advanced language features
  "-unchecked",
  // "-Wunused:imports",
  //   "-Wunused:privates",
  //   "-Wunused:locals",
  //   "-Wunused:explicits",
  //   "-Wunused:implicits",
  //   "-Wunused:params",
  //   "-Wvalue-discard",
  "-language:strictEquality",
  "-Xmax-inlines:100000"
)

lazy val root = (project in file("."))
  .settings(
    name := "kube-playground",
    libraryDependencies ++= Seq(
      sttpCore,
      sttpJsoniter,
      http4sBackend,
      http4sDsl,
      emberServer,
      fs2,
      chimney,
      emberClient,
      catsEffect,
      pureconfig,
      slf4j,
      logback,
      scribe,
      scribeSlf4j,
      scribeCats,
      jsoniter,
      jsoniterMacros,
      jsoniterCirce,
      munit
    )
  )
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion
    ),
    buildInfoPackage := "com.kubescala.generated",
    buildInfoObject := "KubeScalaBuildInfo",
    scalacOptions ++= Seq("-no-indent")
  )
  .dependsOn(
    admissionRegistration,
    admissionRegistrationV1,
    api,
    // apiV1,
    apiextensions,
    apiextensionsV1,
    apis,
    // apisV1,
    apps,
    // appsV1,
    authentication,
    authenticationV1,
    authorization,
    authorizationV1,
    autoscaling,
    autoscalingV1,
    autoscalingV2,
    batch,
    batchV1,
    certificates,
    certificatesV1,
    coordination,
    coordinationV1,
    discovery,
    discoveryV1,
    events,
    eventsV1,
    apiserver,
    apiserverV1,
    jwks,
    networking,
    networkingV1,
    node,
    nodeV1,
    policy,
    policyV1,
    rbac,
    rbacV1,
    scheduling,
    schedulingV1,
    storage,
    storageV1
  )

val commonSettings = Seq(
  scalacOptions := Seq.empty,
  openApiInputSpec := (baseDirectory.value / (name.value + ".json")).getPath,
  openApiModelNamePrefix := "",
  openApiModelNameSuffix := "",
  openApiApiPackage := s"kubescala.${name.value.replace("-", ".")}" + ".api",
  openApiModelPackage := s"kubescala.${name.value.replace("-", ".")}" + ".models",
  openApiInvokerPackage := s"kubescala.${name.value.replace("-", ".")}",
  // openApiRemoveOperationIdPrefix := Some(true),
  openApiGenerateMetadata := SettingDisabled,
  // Use the module-local config.json
  openApiConfigFile := (baseDirectory.value / "config.json").getPath,
  openApiIgnoreFileOverride := ((ThisBuild / baseDirectory).value / "modules" / ".openapi-generator-ignore").getPath,

  // Put generated sources where SBT expects managed sources
  openApiOutputDir := ((Compile / baseDirectory).value / "src/main/scala").getAbsolutePath,
  openApiGenerateModelTests := SettingDisabled,
  openApiGenerateApiTests := SettingDisabled,
  openApiValidateSpec := SettingDisabled,
  // Fail fast on bad specs (optional but recommended)
  // openApiValidateSpec := Some(true),

  // Define the simple generate command to generate full client codes.
  //
  // Must stay uncached. sbt 2 caches `:=` task results by default, but the
  // cache key is built from the task's `.value` inputs, and nothing here
  // hashes the OpenAPI spec's *contents* -- sbt's own file-input keys
  // (allInputFiles / changedInputFiles) are @transient, i.e. deliberately
  // excluded from cache input. A Def.cachedTask would therefore keep serving
  // a stale client whenever the spec changed, so we always regenerate.

  generate := Def.uncached {
    openApiGenerate.value
  },
  // Wired in as a sourceGenerator, NOT as `compile.dependsOn(generate)`.
  // sbt collects `sources` by globbing src/main/scala in a task separate from
  // `compile`, and dependsOn only sequences generate ahead of `compile` --
  // not ahead of that glob. So on a clean checkout the glob would run first,
  // find nothing, and the module would compile 0 sources, leaving its
  // api/models off the classpath and failing every downstream import that
  // depends on it -- and locally you'd never notice, since the previous run's
  // files are still on disk and the glob always finds those. A sourceGenerator
  // feeds `sources` directly, so sbt has to run it first.
  //
  // No separate glob needed: generate is typed Seq[File] (see
  // Dependencies.scala), so its own return value -- the exact file list
  // openApiGenerate just wrote -- IS what sourceGenerators needs.
  Compile / sourceGenerators += generate.taskValue,
  // openApiOutputDir *is* src/main/scala, so the generator above already
  // covers everything sbt would otherwise pick up as unmanaged sources.
  // Dropping the unmanaged dir makes the generator the single source of
  // truth instead of having sbt separately glob a directory that's empty on
  // a clean checkout.
  Compile / unmanagedSourceDirectories := Seq.empty,
  libraryDependencies ++= Seq(
    sttpJsoniter,
    jsoniter,
    jsoniterMacros,
    jsoniterCirce
  )
)

lazy val admissionRegistration =
  (project in file("modules/admission-registration"))
    .enablePlugins(OpenApiGeneratorPlugin)
    .settings(
      name := "admissionregistration"
    )
    .settings(commonSettings *)

lazy val admissionRegistrationV1 =
  (project in file("modules/admission-registration-v1"))
    .enablePlugins(OpenApiGeneratorPlugin)
    .settings(
      name := "admissionregistration-v1"
    )
    .settings(commonSettings *)

lazy val api = (project in file("modules/api"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "api"
  )
  .settings(commonSettings *)

val apiV1 = (project in file("modules/api-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "api-v1"
  )
  .settings(commonSettings *)

lazy val apiextensions = (project in file("modules/apiextensions"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "apiextensions"
  )

lazy val apiextensionsV1 = (project in file("modules/apiextensions-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "apiextensions-v1"
  )
  .settings(commonSettings *)

lazy val apis = (project in file("modules/apis"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "apis"
  )

// lazy val apisV1 = (project in file("modules/apis-v1"))
//   .enablePlugins(OpenApiGeneratorPlugin)
//   .settings(
//     name := "apis-v1"
//   )
//   .settings(commonSettings*)

lazy val apiserver =
  (project in file("modules/apiserver"))
    .enablePlugins(OpenApiGeneratorPlugin)
    .settings(
      name := "apiserver"
    )
    .settings(commonSettings *)

lazy val apiserverV1 =
  (project in file("modules/apiserver-v1"))
    .enablePlugins(OpenApiGeneratorPlugin)
    .settings(
      name := "apiserver-v1"
    )
    .settings(commonSettings *)

lazy val apps = (project in file("modules/apps"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "apps"
  )
  .settings(commonSettings *)

lazy val appsV1 = (project in file("modules/apps-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "apps-v1"
  )
  .settings(commonSettings *)

lazy val authentication = (project in file("modules/authentication"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "authentication"
  )
  .settings(commonSettings *)

lazy val authenticationV1 = (project in file("modules/authentication-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "authentication-v1"
  )
  .settings(commonSettings *)

lazy val authorization = (project in file("modules/authorization"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "authorization"
  )
  .settings(commonSettings *)

lazy val authorizationV1 = (project in file("modules/authorization-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "authorization-v1"
  )
  .settings(commonSettings *)

lazy val autoscaling = (project in file("modules/autoscaling"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "autoscaling"
  )
  .settings(commonSettings *)

lazy val autoscalingV1 = (project in file("modules/autoscaling-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "autoscaling-v1"
  )
  .settings(commonSettings *)

lazy val autoscalingV2 = (project in file("modules/autoscaling-v2"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "autoscaling-v2"
  )
  .settings(commonSettings *)

lazy val batch = (project in file("modules/batch"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "batch"
  )
  .settings(commonSettings *)

lazy val batchV1 = (project in file("modules/batch-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "batch-v1"
  )
  .settings(commonSettings *)

lazy val certificates = (project in file("modules/certificates"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "certificates"
  )
  .settings(commonSettings *)

lazy val certificatesV1 = (project in file("modules/certificates-v1"))
  .settings(
    name := "certificates-v1"
  )
  .settings(commonSettings *)

lazy val coordination = (project in file("modules/coordination"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "coordination"
  )
  .settings(commonSettings *)

lazy val coordinationV1 = (project in file("modules/coordination-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "coordination-v1"
  )
  .settings(commonSettings *)

lazy val discovery = (project in file("modules/discovery"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "discovery"
  )
  .settings(commonSettings *)

val discoveryV1 = (project in file("modules/discovery-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "discovery-v1"
  )
  .settings(commonSettings *)

val events = (project in file("modules/events"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "events"
  )
  .settings(commonSettings *)

lazy val eventsV1 = (project in file("modules/events-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "events-v1"
  )
  .settings(commonSettings *)

lazy val jwks = (project in file("modules/jwks"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "jwks"
  )
  .settings(commonSettings *)

val networking = (project in file("modules/networking"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "networking"
  )
  .settings(commonSettings *)

lazy val networkingV1 = (project in file("modules/networking-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "networking-v1"
  )
  .settings(commonSettings *)

val node = (project in file("modules/node"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "node"
  )
  .settings(commonSettings *)

lazy val nodeV1 = (project in file("modules/node-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "node-v1"
  )
  .settings(commonSettings *)

lazy val policy = (project in file("modules/policy"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "policy"
  )
  .settings(commonSettings *)

lazy val policyV1 = (project in file("modules/policy-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "policy-v1"
  )
  .settings(commonSettings *)

lazy val rbac = (project in file("modules/rbac"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "rbac"
  )
  .settings(commonSettings *)

lazy val rbacV1 =
  (project in file("modules/rbac-v1"))
    .enablePlugins(OpenApiGeneratorPlugin)
    .settings(
      name := "rbac-v1"
    )
    .settings(commonSettings *)
lazy val scheduling = (project in file("modules/scheduling"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "scheduling"
  )
  .settings(commonSettings *)
lazy val schedulingV1 = (project in file("modules/scheduling-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "scheduling-v1"
  )
  .settings(commonSettings *)
lazy val storage = (project in file("modules/storage"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "storage"
  )
  .settings(commonSettings *)

lazy val storageV1 = (project in file("modules/storage-v1"))
  .enablePlugins(OpenApiGeneratorPlugin)
  .enablePlugins(OpenApiGeneratorPlugin)
  .settings(
    name := "storage-v1"
  )
  .settings(commonSettings *)
