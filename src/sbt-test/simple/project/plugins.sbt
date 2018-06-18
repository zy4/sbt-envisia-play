val pluginVersion = Option(System.getProperty("plugin.version")).getOrElse("0.4.2-SNAPSHOT")
if(pluginVersion == null)
  throw new RuntimeException("""|The system property 'plugin.version' is not defined.
                                |Specify this property using the scriptedLaunchOpts -D.""".stripMargin)
else addSbtPlugin("de.envisia.sbt" % "sbt-envisia-play" % pluginVersion)