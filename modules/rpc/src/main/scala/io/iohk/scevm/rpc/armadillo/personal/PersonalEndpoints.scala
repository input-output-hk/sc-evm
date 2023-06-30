package io.iohk.scevm.rpc.armadillo.personal

import cats.effect.IO
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.controllers.PersonalController

object PersonalEndpoints extends Endpoints {

  def getPersonalEndpoints(personalController: PersonalController): List[JsonRpcServerEndpoint[IO]] = List(
    personal_newAccount(personalController),
    personal_listAccounts(personalController),
    personal_unlockAccount(personalController),
    personal_lockAccount(personalController),
    personal_sendTransaction(personalController),
    personal_sign(personalController),
    personal_importRawKey(personalController),
    personal_ecRecover(personalController)
  )

  def personal_newAccount(personalController: PersonalController): JsonRpcServerEndpoint[IO] =
    PersonalApi.personal_newAccount.serverLogic { req =>
      personalController.newAccount(req).noDataError.value
    }

  def personal_listAccounts(personalController: PersonalController): JsonRpcServerEndpoint[IO] =
    PersonalApi.personal_listAccounts.serverLogic { _ =>
      personalController.listAccounts().noDataError.value
    }

  def personal_unlockAccount(personalController: PersonalController): JsonRpcServerEndpoint[IO] =
    PersonalApi.personal_unlockAccount.serverLogic { case (address, passphrase, duration) =>
      personalController.unlockAccount(address, passphrase, duration).noDataError.value
    }

  def personal_lockAccount(personalController: PersonalController): JsonRpcServerEndpoint[IO] =
    PersonalApi.personal_lockAccount.serverLogic { req =>
      personalController.lockAccount(req).noDataError.value
    }

  def personal_sendTransaction(personalController: PersonalController): JsonRpcServerEndpoint[IO] =
    PersonalApi.personal_sendTransaction.serverLogic { case (tx, passphrase) =>
      personalController.sendTransaction(tx, passphrase).noDataError.value
    }

  def personal_sign(personalController: PersonalController): JsonRpcServerEndpoint[IO] =
    PersonalApi.personal_sign.serverLogic { case (msg, addr, passphr) =>
      personalController.sign(msg, addr, passphr).noDataError.value
    }

  def personal_importRawKey(personalController: PersonalController): JsonRpcServerEndpoint[IO] =
    PersonalApi.personal_importRawKey.serverLogic { case (key, passphrase) =>
      personalController.importRawKey(key, passphrase).noDataError.value
    }

  def personal_ecRecover(personalController: PersonalController): JsonRpcServerEndpoint[IO] =
    PersonalApi.personal_ecRecover.serverLogic { case (message, signature) =>
      personalController.ecRecover(message, signature).noDataError.value
    }

}
