package io.iohk.scevm

import io.iohk.scevm.utils.Logger

object App extends Logger {

  def main(args: Array[String]): Unit = {

    lazy val launchCommand = "sc-evm-launch"

    args.headOption match {
      case None                  => ScEvm.main(args)
      case Some(`launchCommand`) => ScEvm.main(args.tail)
      case Some(unknown) =>
        log.error(
          s"Unrecognised launcher option $unknown, " +
            s"the application must be started without parameters or with the first parameter equal to $launchCommand"
        )
    }

  }
}
