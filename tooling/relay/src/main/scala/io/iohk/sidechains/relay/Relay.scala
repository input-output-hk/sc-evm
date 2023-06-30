package io.iohk.sidechains.relay

import cats.data.Validated
import cats.effect.{ExitCode, IO, Resource}
import cats.syntax.all._
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sttp.model.Uri

/** This is not a long running process.
  * Executes 'init' if required.
  * Relays epochs that are pending upload to the main chain.
  * Quits.
  * A reason for it is that before starting, sidechain-main-cli config.json is set with URLs that are subject of change
  * in the Nomad environment.
  */
object Relay extends CommandIOApp(name = "relay", header = "Sidechains Relay") {

  implicit val log: Logger[IO] = Slf4jLogger.getLogger[IO]

  override def main: Opts[IO[ExitCode]] = FixHandover().orElse(FixMerkleRoot()).orElse(default)

  private lazy val default: Opts[IO[ExitCode]] = (nodeUrl, relaySigningKeyPath, initSigningKeyPath).mapN {
    case (nodeUrl, relaySigningKeyPath, initSigningKeyPath) =>
      val rpcClient = RpcClient(nodeUrl)
      (for {
        _             <- logConfigJson
        _             <- log.info(s"Sidechain node URL: $nodeUrl")
        initCommand   <- new InitCommands(rpcClient, initSigningKeyPath).getCtlCommand
        _             <- initCommand.toList.traverse(cmd => ProcessIO.execute(cmd))
        relayCommands <- new EpochCommands(rpcClient, relaySigningKeyPath).getCtlCommands
        _             <- relayCommands.traverse(cmd => ProcessIO.execute(cmd))
      } yield ()).attempt.flatMap {
        case Left(reason) => log.error(s"Relay failed: $reason").as(ExitCode(1))
        case _            => IO.pure(ExitCode(0))
      }
  }

  private val nodeUrl: Opts[Uri] = Opts
    .option[String]("node-url", "URL of sidechain node")
    .mapValidated(uri => Validated.fromEither(Uri.parse(uri)).toValidatedNel)

  private val initSigningKeyPath = Opts
    .option[String]("init-signing-key-path", "Path to signing key file used to invoke save-root and committee-hash")
    .orNone

  private val relaySigningKeyPath =
    Opts.option[String]("relay-signing-key-path", "Path to signing key file that owns genesis-committee-utxo")

  private val logConfigJson: IO[Unit] =
    Resource
      .make(IO(scala.io.Source.fromFile("config.json")))(r => IO(r.close()))
      .use(s => log.info(s"config.json:\n${s.mkString}"))
}
