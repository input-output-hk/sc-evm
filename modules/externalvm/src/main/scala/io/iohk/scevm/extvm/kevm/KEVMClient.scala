package io.iohk.scevm.extvm.kevm

import cats.effect.std.Semaphore
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import io.iohk.extvm.kevm.{kevm_msg => msg}
import io.iohk.scevm.config._
import io.iohk.scevm.domain.Account
import io.iohk.scevm.exec.vm.{Storage, VM, WorldState}
import io.iohk.scevm.extvm.Codecs.{generatedMessageDecoder, generatedMessageEncoder}
import io.iohk.scevm.extvm.Implicits._
import io.iohk.scevm.extvm.MessageSocket
import io.iohk.scevm.extvm.MessageSocket.MessageSocketConfig
import io.iohk.scevm.extvm.VmConfig.KEVMConfig
import io.iohk.scevm.extvm.kevm.KEVMClient.VMConnection
import io.iohk.scevm.extvm.kevm.KEVMMessageConstructors._
import io.iohk.scevm.utils.ThrowableExtensions.ThrowableOps
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scalapb.GeneratedMessage

import java.io.IOException
import java.net.InetSocketAddress
import scala.concurrent.duration.DurationInt

final class KEVMClient[W <: WorldState[W, S], S <: Storage[S]](
    kevmConfig: KEVMConfig,
    blockchainConfig: BlockchainConfig,
    semaphore: Semaphore[IO],
    vmConnectionRef: Ref[IO, Option[VMConnection]]
) extends VM[IO, W, S] {

  import KEVMClient._

  /** If a socket error occurs during socket connection, the error is bubbled up.
    * If a socket error occurs during the execution of the program, the socket is discarded and we attempt to reconnect.
    * All other errors are bubbled up.
    */
  override def run(context: PC): IO[PR] =
    semaphore.permit
      .use { _ =>
        handle(context)
      }

  private def handle(context: PC): IO[PR] =
    vmConnectionRef.get >>= {
      case Some(vmConnection) => vmConnection.pure[IO]
      // If connect throws an exception the reference is never populated with a new one and PC is never evaluated.
      case None =>
        connect(kevmConfig, blockchainConfig)
          .flatTap(vmConnection => vmConnectionRef.set(vmConnection.some))
    } >>= { case (vmSocket, vmConnectionCloser) =>
      // If runPure throws an IOException we attempt to reconnect, otherwise we let the error bubble.
      runPure(vmSocket)(context).recoverWith { case ioException: IOException =>
        log.error("IO error while running transaction on KEVM external VM: " + ioException.stackTraceString) >>
          vmConnectionCloser >>
          vmConnectionRef.set(none) >>
          handle(context)
      }
    }

  private def runPure(vmSocket: VMSocket)(context: PC): IO[PR] = {

    def processQuery(query: msg.VMQuery.Query): IO[Either[Option[GeneratedMessage], msg.CallResult]] =
      query match {
        case msg.VMQuery.Query.CallResult(value) => value.asRight.pure[IO]
        case msg.VMQuery.Query.GetAccount(msg.GetAccount(address, _)) =>
          IO(
            context.world
              .getAccount(address)
              .fold(msg.Account(codeEmpty = true))(acc =>
                msg.Account(
                  nonce = acc.nonce.value,
                  balance = acc.balance,
                  codeEmpty = acc.codeHash == Account.EmptyCodeHash
                )
              )
              .some
              .asLeft
          )
        case msg.VMQuery.Query.GetStorageData(msg.GetStorageData(address, offset, _)) =>
          IO(msg.StorageData(data = context.world.getStorage(address).load(offset)).some.asLeft)
        case msg.VMQuery.Query.GetCode(msg.GetCode(address, _)) =>
          IO(msg.Code(context.world.getCode(address)).some.asLeft)
        case msg.VMQuery.Query.GetBlockhash(msg.GetBlockhash(offset, _)) =>
          IO(
            context.world
              .getBlockHash(offset)
              .fold(msg.Blockhash())(value => msg.Blockhash(hash = value.bytes))
              .some
              .asLeft
          )
        case msg.VMQuery.Query.Empty => none.asLeft.pure[IO]
      }

    def processQueries: IO[msg.CallResult] =
      vmSocket.receive >>= { vmQuery =>
        logRequest(vmQuery.query) >> processQuery(vmQuery.query) >>= {
          case Left(Some(response)) => logResponse(response) >> vmSocket.send(response) >> processQueries
          case Left(None)           => processQueries
          case Right(callResult)    => callResult.pure[IO]
        }
      }

    vmSocket.send(constructCallContext(context)) >>
      processQueries.map(constructProgramResult[W, S](context.world, _))
  }

  private def logRequest(query: msg.VMQuery.Query): IO[Unit] = log.debug(s"Client received message: ${query.getClass}")

  private def logResponse(msg: GeneratedMessage): IO[Unit] = log.debug(s"Client sent message: ${msg.getClass}")
}

object KEVMClient {

  type VMSocket = MessageSocket[IO, msg.VMQuery, GeneratedMessage]

  type VMSocketCloser = IO[Unit]

  type VMConnection = (VMSocket, VMSocketCloser)

  val log: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  def start[W <: WorldState[W, S], S <: Storage[S]](
      kevmConfig: KEVMConfig,
      blockchainConfig: BlockchainConfig
  ): Resource[IO, KEVMClient[W, S]] =
    Resource {
      for {
        vmConnection    <- connect(kevmConfig, blockchainConfig)
        _               <- log.info("Connected to KEVM server")
        semaphore       <- Semaphore[IO](1)
        vmConnectionRef <- Ref.of[IO, Option[VMConnection]](vmConnection.some)

        /** It might be possible for a 'run' call to be made after the vmConnectionCloser is executed and before the other
          * finalizers are finished executing, at which point a new message socket could be opened. We add a semaphore
          * around 'run' calls so that all calls executed after this finalizer is executed block. As a result, a given program
          * finishes executing before the VM is shutdown.
          */
        externalVMCloser = semaphore.acquire >> {
                             vmConnectionRef.get >>= {
                               case Some((_, vmConnectionCloser)) => vmConnectionCloser
                               case None                          => IO.unit
                             }
                           }
      } yield (
        new KEVMClient[W, S](kevmConfig, blockchainConfig, semaphore, vmConnectionRef),
        externalVMCloser
      )
    }

  private def connect(
      kevmConfig: KEVMConfig,
      blockchainConfig: BlockchainConfig
  ): IO[VMConnection] =
    MessageSocket
      .connect[IO, msg.VMQuery, GeneratedMessage](
        MessageSocketConfig(
          new InetSocketAddress(kevmConfig.host, kevmConfig.port),
          keepAlive = true,
          connectRetryCount = 3,
          connectRetryDelay = 3.seconds
        ),
        generatedMessageDecoder(msg.VMQuery),
        generatedMessageEncoder,
        log
      )
      .evalTap(_.send(constructHello(blockchainConfig, kevmConfig.protoApiVersion)))
      .allocated

}
