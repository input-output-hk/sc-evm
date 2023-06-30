package io.iohk.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline._
import io.iohk.scevm.config.GenesisDataFileLoader
import io.iohk.scevm.db.storage.genesis.ObftGenesisLoader
import org.json4s.ParserUtil.ParseException

import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException

object GenesisHashCommand {

  final private case class Params(genesisHashPath: String, verbose: Boolean)

  private val verbose: Opts[Boolean] = Opts.flag("verbose", "Display additional information").orFalse

  private val genesisFilePath: Opts[String] = Opts.argument[String]("genesis-file-path")

  private val genesisHashOpts: Opts[Params] = Opts.subcommand("genesis-hash", "Compute genesis hash") {
    (genesisFilePath, verbose).mapN(Params)
  }

  def apply(): Opts[IO[ExitCode]] =
    genesisHashOpts.map { case Params(genesisHashPath, verbose) =>
      (for {
        obftGenesisData <- GenesisDataFileLoader.loadGenesisDataFile[IO](genesisHashPath)
        genesisHash     <- ObftGenesisLoader.getGenesisHash[IO](obftGenesisData)
        _               <- IO.println(genesisHash.toHex) // scalastyle:ignore
      } yield ExitCode.Success).recoverWith {
        case f: FileNotFoundException => handleError("Path not found", f, verbose)
        case a: AccessDeniedException => handleError("Access denied", a, verbose)
        case p: ParseException        => handleError("Fail to parse the given file", p, verbose)
        case t                        => handleError(s"An unexpected error happened (${t.getMessage})", t, verbose)
      }
    }
}
