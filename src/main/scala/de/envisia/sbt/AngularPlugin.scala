package de.envisia.sbt

import de.envisia.sbt.angular.{ Angular2, Angular2Exception }
import play.sbt.PlayImport.PlayKeys
import play.sbt.{ PlayInternalKeys, PlayService }
import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbt._

object AngularPlugin extends AutoPlugin {
  override def requires: Plugins = JvmPlugin && PlayService

  object autoImport {
    val ngNodeMemory: SettingKey[Int]           = settingKey[Int]("ng node memory")
    val ngProcessPrefix: SettingKey[String]     = settingKey[String]("ng process prefix (useful for windows)")
    val ngCommand: SettingKey[String]           = settingKey[String]("ng command")
    val ngDirectory: SettingKey[File]           = settingKey[File]("ng directory")
    val ngTarget: SettingKey[File]              = settingKey[File]("ng target")
    val ngBaseDirectory: SettingKey[File]       = settingKey[File]("ngBaseDirectory")
    val yarnInstall: TaskKey[Unit]              = taskKey[Unit]("yarnInstall")
    val ngBuild: TaskKey[Seq[(File, String)]]   = taskKey[Seq[(File, String)]]("ngBuild")
    val ngOutputDirectory: SettingKey[File]     = settingKey[File]("build output directory of angular")
    val ngDevOutputDirectory: SettingKey[File]  = settingKey[File]("dev build output directory of angular")
    val ngLint: TaskKey[Unit]                   = taskKey[Unit]("ng lint")
    val ngPackage: TaskKey[Seq[(File, String)]] = taskKey[Seq[(File, String)]]("ng package")
  }

  import autoImport._
  import scala.sys.process._
  import com.typesafe.sbt.packager.MappingsHelper._

  class AngularLogger(logger: sbt.Logger) extends ProcessLogger {
    override def out(s: => String): Unit = logger.info(s)
    override def err(s: => String): Unit = logger.error(s)
    override def buffer[T](f: => T): T   = f
  }

  private def runProcessSync(log: Logger, command: String, base: File): Unit = {
    log.info(s"Running $command...")
    val rc = Process(command, base).!(new ProcessLogger {
      override def err(s: => String): Unit = log.err(s"> $s")
      override def out(s: => String): Unit = log.info(s"> $s")
      override def buffer[T](f: => T): T   = f
    })
    if (rc != 0) {
      throw new Angular2Exception(s"$command failed with $rc")
    }
  }

  private def ngBuildTask = Def.task {
    val ng     = ngCommand.value
    val dir    = ngDirectory.value
    val log    = streams.value.log
    val output = ngOutputDirectory.value
    runProcessSync(
      log,
      s"$ng build --prod=true --progress=false --aot=true --build-optimizer --output-path=${output.toString}",
      dir
    )
    contentOf(output)
  }

  private def ngBuildAndGzip: Def.Initialize[Task[Seq[(File, String)]]] = Def.taskDyn {
    val targetDir = ngTarget.value / "web" / "gzip"
    val mappings  = ngBuild.value
    val include   = "*.html" || "*.css" || "*.js"
    val exclude   = HiddenFileFilter
    Def.task {
      val gzipMappings = for {
        (file, path) <- mappings if !file.isDirectory && include.accept(file) && !exclude.accept(file)
      } yield {
        val gzipPath = path + ".gz"
        val gzipFile = targetDir / gzipPath
        IO.gzip(file, gzipFile)
        (gzipFile, gzipPath)
      }
      (mappings ++ gzipMappings).map { case (file, path) => (file, "public/" + path) }
    }
  }

  private def ngLintTask: Def.Initialize[Task[Unit]] = Def.task {
    val log = new AngularLogger(streams.value.log)
    val cmd = ngCommand.value

    val retCode1 = Process(s"$cmd lint").!(log)
    if (retCode1 != 0) {
      throw new RuntimeException("ng lint failed")
    }
    val retCode2 = Process(s"$cmd build --prod").!(log)
    if (retCode2 != 0) {
      throw new RuntimeException("ng build --prod failed")
    }
  }

  override def projectSettings = Seq(
    ngNodeMemory := 1024,
    ngDirectory := file("ui"),
    ngProcessPrefix := {
      sys.props("os.name").toLowerCase match {
        case os if os.contains("win") => "cmd /c "
        case _                        => ""
      }
    },
    ngCommand := s"${ngProcessPrefix.value}node --max_old_space_size=${ngNodeMemory.value} node_modules/@angular/cli/bin/ng",
    ngLint := ngLintTask.dependsOn(yarnInstall).value,
    ngTarget := target.value / "web",
    cleanFiles += ngDirectory.value / "dist",
    ngBaseDirectory := ngDirectory.value,
    ngOutputDirectory := target.value / "dist",
    ngDevOutputDirectory := ngTarget.value / "public" / "main",
    ngPackage := ngBuildAndGzip.value,
    yarnInstall := {
      val log = streams.value.log
      // resolve dependencies before installing
      runProcessSync(log, s"${ngProcessPrefix.value}yarn install", ngDirectory.value)
    },
    (run in Compile) := (run in Compile).dependsOn(yarnInstall).evaluated,
    // includes the angular application
    ngBuild := ngBuildTask.dependsOn(yarnInstall).value,
    mappings in (Compile, packageBin) ++= ngPackage.value,
    PlayKeys.playRunHooks += Angular2(
      ngCommand.value,
      streams.value.log,
      ngBaseDirectory.value,
      target.value,
      ngDevOutputDirectory.value
    ),
    // Sets the Angular output directory as Play's public directory. This completely replaces the
    // public directory, if you want to use this in addition to the assets in the public directory,
    // then use this instead:
    PlayInternalKeys.playAllAssets := Seq("public/" -> ngDevOutputDirectory.value)
  )

}
