package scala.scalanative.sbtplugin

import sbt._, Keys._, complete.DefaultParsers._
import scalanative.compiler.{Compiler => NativeCompiler, Opts => NativeOpts}
import ScalaNativePlugin.autoImport._

object ScalaNativePluginInternal {
  private def cpToStrings(cp: Seq[File]): Seq[String] =
    cp.map(_.getAbsolutePath)

  private def cpToString(cp: Seq[File]): String =
    cpToStrings(cp).mkString(java.io.File.pathSeparator)

  lazy val compileWithDottySettings =
    inConfig(Compile)(inTask(compile)(Defaults.runnerTask) ++ Seq(
      fork in compile := true,

      scalacOptions ++= Seq("-nir", "-language:Scala2"),

      compile := {
        val inputs = (compileInputs in compile).value
        import inputs.config._

        val s = streams.value
        val logger = s.log
        val cacheDir = s.cacheDirectory

        // Discover classpaths

        val compilerClasspath = classpath.filter { entry =>
          val path = entry.getAbsolutePath

          path.contains("scala-compiler") ||
          path.contains("org.scala-lang") ||
          path.contains("org.scala-native")
        }
        val applicationClasspath = classpath

        // List all my dependencies (recompile if any of these changes)

        val allMyDependencies = classpath filterNot (_ == classesDirectory) flatMap { cpFile =>
          if (cpFile.isDirectory) (cpFile ** "*.class").get
          else Seq(cpFile)
        }

        // Compile

        val cachedCompile = FileFunction.cached(cacheDir / "compile",
            FilesInfo.lastModified, FilesInfo.exists) { dependencies =>

          logger.info(
              "Compiling %d Scala sources to %s..." format (
              sources.size, classesDirectory))

          if (classesDirectory.exists)
            IO.delete(classesDirectory)
          IO.createDirectory(classesDirectory)

          val sourcesArgs = sources.map(_.getAbsolutePath()).toList

          /* run.run() below in doCompile() will emit a call to its
           * logger.info("Running dotty.tools.dotc.Main [...]")
           * which we do not want to see. We use this patched logger to
           * filter out that particular message.
           */
          val patchedLogger = new Logger {
            def log(level: Level.Value, message: => String) = {
              val msg = message
              if (level != Level.Info ||
                  !msg.startsWith("Running dotty.tools.dotc.Main"))
                logger.log(level, msg)
            }
            def success(message: => String) = logger.success(message)
            def trace(t: => Throwable) = logger.trace(t)
          }

          def doCompile(sourcesArgs: List[String]): Unit = {
            val run = (runner in compile).value
            val args =
                options ++:
                ("-classpath" :: cpToString(applicationClasspath) ::
                "-d" :: classesDirectory.getAbsolutePath() ::
                sourcesArgs)
            run.run("dotty.tools.dotc.Main", compilerClasspath,
                args, patchedLogger) foreach sys.error
          }

          // Work around the Windows limitation on command line length.
          val isWindows =
            System.getProperty("os.name").toLowerCase().indexOf("win") >= 0
          if ((fork in compile).value && isWindows &&
              (sourcesArgs.map(_.length).sum > 1536)) {
            IO.withTemporaryFile("sourcesargs", ".txt") { sourceListFile =>
              IO.writeLines(sourceListFile, sourcesArgs)
              doCompile(List("@"+sourceListFile.getAbsolutePath()))
            }
          } else {
            doCompile(sourcesArgs)
          }

          // Output is all files in classesDirectory
          (classesDirectory ** AllPassFilter).get.toSet
        }

        cachedCompile((sources ++ allMyDependencies).toSet)

        // We do not have dependency analysis when compiling externally
        sbt.inc.Analysis.Empty
      }
    ))

  /** Compiles application nir to llvm ir. */
  private def compileNir(opts: NativeOpts): Unit = {
    IO.createDirectory(file(opts.outpath).getParentFile)
    (new NativeCompiler(opts)).apply()
  }

  /** Compiles nrt to llvm ir using clang. */
  private def compileNrt(): Unit = {}

  /** Compiles runtime and application llvm ir files to assembly using llc. */
  private def compileLl(): Unit = {}

  /** Compiles assembly to object file using as. */
  private def compileAsm(): Unit = {}

  /** Links assembly-generated object files and generates a native binary using ld. */
  private def linkAsm(): Unit = {}

  lazy val commonProjectSettings = Seq(
    artifactPath :=
      (crossTarget in Compile).value / (moduleName.value + "-out.ll"),

    nativeVerbose := false,

    nativeCompile := {
      val entry     = (OptSpace ~> StringBasic).parsed
      val classpath = cpToStrings((fullClasspath in Compile).value.map(_.data))
      val outfile   = (artifactPath in Compile).value.getAbsolutePath
      val debug     = nativeVerbose.value
      val opts      = new NativeOpts(classpath, outfile, entry, debug)

      compileNir(opts)
      compileNrt()
      compileLl()
      compileAsm()
      linkAsm()
    },

    nativeRun := {
      ???
    }
  )

  lazy val projectSettings = {
    commonProjectSettings ++ Seq(
      addCompilerPlugin("org.scala-native" %% "nscplugin" % "0.1-SNAPSHOT")
    )
  }

  lazy val dottyProjectSettings =
    compileWithDottySettings ++ commonProjectSettings ++ Seq(
      scalaVersion := "2.11.5",
      libraryDependencies += "org.scala-lang" %% "dotty" % "0.1-SNAPSHOT" changing()
    )
}