package io.iohk.cli

import cats.data.EitherT
import cats.effect.IO
import cats.implicits.{catsSyntaxApply, showInterpolator}

import java.io.File
import scala.sys.process.Process

class CtlExecutable(executable: List[String]) {
  def call(args: String*): EitherT[IO, String, String] = call(args.toList)

  def call(args: List[String]): EitherT[IO, String, String] = {
    val command = executable ++ args
    EitherT(IO.blocking(Process(command).!!).attempt)
      .leftMap {
        case re: RuntimeException if re.getMessage.contains("Nonzero exit value") =>
          "CTL executable returned an error"
        case err =>
          show"Unexpected error when running CTL executable: ${err.getMessage}"
      }
  }
}

object CtlExecutable {
  def apply(executable: List[String]): EitherT[IO, String, CtlExecutable] =
    for {
      _ <- executable match {
             case "node" :: script :: _ => verifyNode() *> fileExists(script)
             case standalone :: _       => fileExists(standalone) *> fileIsExecutable(standalone)
             case _                     => EitherT.leftT[IO, Unit](show"Invalid executable: '$executable'")
           }
    } yield new CtlExecutable(executable)

  private def file(fileName: String) = new File(fileName)

  private def fileExists(fileName: String) =
    EitherT.cond[IO](file(fileName).isFile, (), show"File '$fileName' does not exist")

  private def fileIsExecutable(fileName: String) =
    EitherT.cond[IO](file(fileName).canExecute, (), show"File '$fileName' is not executable")

  private def verifyNode() =
    EitherT(IO.blocking(Process("node -v").!!).attempt)
      .leftMap(e => show"Failed to find NodeJS executable: ${e.getMessage}")

}
