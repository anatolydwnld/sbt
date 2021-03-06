/* sbt -- Simple Build Tool
 * Copyright 2011 Mark Harrah
 */
package sbt

import Keys._
import sbt.internal.util.complete.{ DefaultParsers, Parser }
import sbt.internal.util.AttributeKey
import DefaultParsers._
import Def.{ ScopedKey, Setting }
import sbt.internal.CommandStrings.{ CrossCommand, CrossRestoreSessionCommand, SwitchCommand, crossHelp, crossRestoreSessionHelp, switchHelp }
import java.io.File

import sbt.internal.inc.ScalaInstance
import sbt.io.IO
import sbt.librarymanagement.CrossVersion

object Cross {

  private def spacedFirst(name: String) = opOrIDSpaced(name) ~ any.+

  private case class Switch(version: ScalaVersion, verbose: Boolean, command: Option[String])
  private trait ScalaVersion {
    def force: Boolean
  }
  private case class NamedScalaVersion(name: String, force: Boolean) extends ScalaVersion
  private case class ScalaHomeVersion(home: File, resolveVersion: Option[String], force: Boolean) extends ScalaVersion

  private def switchParser(state: State): Parser[Switch] = {
    import DefaultParsers._
    def versionAndCommand(spacePresent: Boolean) = {
      val x = Project.extract(state)
      import x._
      val knownVersions = crossVersions(x, currentRef)
      val version = token(StringBasic.examples(knownVersions: _*)).map { arg =>
        val force = arg.endsWith("!")
        val versionArg = if (force) arg.dropRight(1) else arg
        versionArg.split("=", 2) match {
          case Array(home) if new File(home).exists() => ScalaHomeVersion(new File(home), None, force)
          case Array(v)                               => NamedScalaVersion(v, force)
          case Array(v, home)                         => ScalaHomeVersion(new File(home), Some(v).filterNot(_.isEmpty), force)
        }
      }
      val spacedVersion = if (spacePresent) version else version & spacedFirst(SwitchCommand)
      val verbose = Parser.opt(token(Space ~> "-v"))
      val optionalCommand = Parser.opt(token(Space ~> matched(state.combinedParser)))
      (spacedVersion ~ verbose ~ optionalCommand).map {
        case v ~ verbose ~ command =>
          Switch(v, verbose.isDefined, command)
      }
    }

    token(SwitchCommand ~> OptSpace) flatMap { sp => versionAndCommand(sp.nonEmpty) }
  }

  private case class CrossArgs(command: String, verbose: Boolean)

  private def crossParser(state: State): Parser[CrossArgs] =
    token(CrossCommand <~ OptSpace) flatMap { _ =>
      (token(Parser.opt("-v" <~ Space)) ~ token(matched(state.combinedParser))).map {
        case (verbose, command) => CrossArgs(command, verbose.isDefined)
      } & spacedFirst(CrossCommand)
    }

  private def crossRestoreSessionParser(state: State): Parser[String] = token(CrossRestoreSessionCommand)

  private def requireSession[T](p: State => Parser[T]): State => Parser[T] = s =>
    if (s get sessionSettings isEmpty) failure("No project loaded") else p(s)

  private def resolveAggregates(extracted: Extracted): Seq[ProjectRef] = {
    import extracted._

    def findAggregates(project: ProjectRef): List[ProjectRef] = {
      project :: (structure.allProjects(project.build).find(_.id == project.project) match {
        case Some(resolved) => resolved.aggregate.toList.flatMap(findAggregates)
        case None           => Nil
      })
    }

    (currentRef :: currentProject.aggregate.toList.flatMap(findAggregates)).distinct
  }

  private def crossVersions(extracted: Extracted, proj: ProjectRef): Seq[String] = {
    import extracted._
    (crossScalaVersions in proj get structure.data) getOrElse {
      // reading scalaVersion is a one-time deal
      (scalaVersion in proj get structure.data).toSeq
    }
  }

  /**
   * Parse the given command into either an aggregate command or a command for a project
   */
  private def parseCommand(command: String): Either[String, (String, String)] = {
    import DefaultParsers._
    val parser = (OpOrID <~ charClass(_ == '/', "/")) ~ any.* map {
      case project ~ cmd => (project, cmd.mkString)
    }
    Parser.parse(command, parser).left.map(_ => command)
  }

  def crossBuild: Command =
    Command.arb(requireSession(crossParser), crossHelp)(crossBuildCommandImpl)

  private def crossBuildCommandImpl(state: State, args: CrossArgs): State = {
    val x = Project.extract(state)
    import x._

    val (aggs, aggCommand) = parseCommand(args.command) match {
      case Right((project, cmd)) =>
        (structure.allProjectRefs.filter(_.project == project), cmd)
      case Left(cmd) => (resolveAggregates(x), cmd)
    }

    // if we support scalaVersion, projVersions should be cached somewhere since
    // running ++2.11.1 is at the root level is going to mess with the scalaVersion for the aggregated subproj
    val projVersions = (aggs flatMap { proj =>
      crossVersions(x, proj) map { (proj.project, _) }
    }).toList

    val verbose = if (args.verbose) "-v" else ""

    if (projVersions.isEmpty) {
      state
    } else {
      // Group all the projects by scala version
      val allCommands = projVersions.groupBy(_._2).mapValues(_.map(_._1)).toSeq.flatMap {
        case (version, Seq(project)) =>
          // If only one project for a version, issue it directly
          Seq(s"$SwitchCommand $verbose $version $project/$aggCommand")
        case (version, projects) if aggCommand.contains(" ") =>
          // If the command contains a space, then the all command won't work because it doesn't support issuing
          // commands with spaces, so revert to running the command on each project one at a time
          s"$SwitchCommand $verbose $version" :: projects.map(project => s"$project/$aggCommand")
        case (version, projects) =>
          // First switch scala version, then use the all command to run the command on each project concurrently
          Seq(s"$SwitchCommand $verbose $version", projects.map(_ + "/" + aggCommand).mkString("all ", " ", ""))
      }

      allCommands ::: CrossRestoreSessionCommand :: captureCurrentSession(state, x)
    }
  }

  def crossRestoreSession: Command =
    Command.arb(crossRestoreSessionParser, crossRestoreSessionHelp)(crossRestoreSessionImpl)

  private def crossRestoreSessionImpl(state: State, arg: String): State = {
    restoreCapturedSession(state, Project.extract(state))
  }

  private val CapturedSession = AttributeKey[Seq[Setting[_]]]("crossCapturedSession")

  private def captureCurrentSession(state: State, extracted: Extracted): State = {
    state.put(CapturedSession, extracted.session.rawAppend)
  }

  private def restoreCapturedSession(state: State, extracted: Extracted): State = {
    state.get(CapturedSession) match {
      case Some(rawAppend) =>
        val restoredSession = extracted.session.copy(rawAppend = rawAppend)
        BuiltinCommands.reapply(restoredSession, extracted.structure, state).remove(CapturedSession)
      case None => state
    }
  }

  def switchVersion: Command =
    Command.arb(requireSession(switchParser), switchHelp)(switchCommandImpl)

  private def switchCommandImpl(state: State, args: Switch): State = {
    val switchedState = switchScalaVersion(args, state)

    args.command.toSeq ::: switchedState
  }

  private def switchScalaVersion(switch: Switch, state: State): State = {
    val x = Project.extract(state)
    import x._

    val (version, instance) = switch.version match {
      case ScalaHomeVersion(homePath, resolveVersion, _) =>
        val home = IO.resolve(x.currentProject.base, homePath)
        if (home.exists()) {
          val instance = ScalaInstance(home)(state.classLoaderCache.apply _)
          val version = resolveVersion.getOrElse(instance.actualVersion)
          (version, Some((home, instance)))
        } else {
          sys.error(s"Scala home directory did not exist: $home")
        }
      case NamedScalaVersion(v, _) => (v, None)
    }

    val binaryVersion = CrossVersion.binaryScalaVersion(version)

    def logSwitchInfo(included: Seq[(ProjectRef, Seq[String])], excluded: Seq[(ProjectRef, Seq[String])]) = {

      instance.foreach {
        case (home, instance) =>
          state.log.info(s"Using Scala home $home with actual version ${instance.actualVersion}")
      }
      if (switch.version.force) {
        state.log.info(s"Forcing Scala version to $version on all projects.")
      } else {
        state.log.info(s"Setting Scala version to $version on ${included.size} projects.")
      }
      if (excluded.nonEmpty && !switch.verbose) {
        state.log.info(s"Excluded ${excluded.size} projects, run ++ $version -v for more details.")
      }

      def detailedLog(msg: => String) = if (switch.verbose) state.log.info(msg) else state.log.debug(msg)

      def logProject: (ProjectRef, Seq[String]) => Unit = (proj, scalaVersions) => {
        val current = if (proj == currentRef) "*" else " "
        detailedLog(s"  $current ${proj.project} ${scalaVersions.mkString("(", ", ", ")")}")
      }
      detailedLog("Switching Scala version on:")
      included.foreach(logProject.tupled)
      detailedLog("Excluding projects:")
      excluded.foreach(logProject.tupled)
    }

    val projects: Seq[Reference] = {
      val projectScalaVersions = structure.allProjectRefs.map(proj => proj -> crossVersions(x, proj))
      if (switch.version.force) {
        logSwitchInfo(projectScalaVersions, Nil)
        structure.allProjectRefs ++ structure.units.keys.map(BuildRef.apply)
      } else {

        val (included, excluded) = projectScalaVersions.partition {
          case (proj, scalaVersions) => scalaVersions.exists(v => CrossVersion.binaryScalaVersion(v) == binaryVersion)
        }
        logSwitchInfo(included, excluded)
        included.map(_._1)
      }
    }

    setScalaVersionForProjects(version, instance, projects, state, x)
  }

  private def setScalaVersionForProjects(version: String, instance: Option[(File, ScalaInstance)],
    projects: Seq[Reference], state: State, extracted: Extracted): State = {
    import extracted._

    val newSettings = projects.flatMap { project =>
      val scope = Scope(Select(project), Global, Global, Global)

      instance match {
        case Some((home, inst)) => Seq(
          scalaVersion in scope := version,
          scalaHome in scope := Some(home),
          scalaInstance in scope := inst
        )
        case None => Seq(
          scalaVersion in scope := version,
          scalaHome in scope := None
        )
      }
    }

    val filterKeys: Set[AttributeKey[_]] = Set(scalaVersion, scalaHome, scalaInstance).map(_.key)

    // Filter out any old scala version settings that were added, this is just for hygiene.
    val filteredRawAppend = session.rawAppend.filter(_.key match {
      case ScopedKey(Scope(Select(ref), Global, Global, Global), key) if filterKeys.contains(key) && projects.contains(ref) => false
      case _ => true
    })

    val newSession = session.copy(rawAppend = filteredRawAppend ++ newSettings)

    BuiltinCommands.reapply(newSession, structure, state)
  }

}
