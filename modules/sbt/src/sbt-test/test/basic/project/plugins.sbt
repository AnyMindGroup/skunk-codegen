addSbtPlugin("com.anymindgroup" % "sbt-skunk-codegen" % System.getProperty("plugin.version"))

credentials += {
  for {
    username <- sys.env.get("ARTIFACT_REGISTRY_USERNAME")
    apiKey   <- sys.env.get("ARTIFACT_REGISTRY_PASSWORD")
  } yield Credentials("https://asia-maven.pkg.dev", "asia-maven.pkg.dev", username, apiKey)
}.getOrElse(Credentials(Path.userHome / ".ivy2" / ".credentials"))

resolvers ++= Seq(
  "AnyChat Registry Release" at "https://asia-maven.pkg.dev/anychat-staging/maven-release"
)
