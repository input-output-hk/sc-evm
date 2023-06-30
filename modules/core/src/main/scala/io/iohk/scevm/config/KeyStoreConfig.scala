package io.iohk.scevm.config

import com.typesafe.config.Config

final case class KeyStoreConfig(
    keyStoreDir: String,
    minimalPassphraseLength: Int,
    allowNoPassphrase: Boolean
)

object KeyStoreConfig {
  def fromConfig(etcClientConfig: Config): KeyStoreConfig = {
    val keyStoreConfig = etcClientConfig.getConfig("keyStore")

    val keyStoreDir: String          = keyStoreConfig.getString("keystore-dir")
    val minimalPassphraseLength: Int = keyStoreConfig.getInt("minimal-passphrase-length")
    val allowNoPassphrase: Boolean   = keyStoreConfig.getBoolean("allow-no-passphrase")
    new KeyStoreConfig(keyStoreDir, minimalPassphraseLength, allowNoPassphrase)
  }

  def customKeyStoreConfig(path: String): KeyStoreConfig =
    new KeyStoreConfig(keyStoreDir = path, minimalPassphraseLength = 7, allowNoPassphrase = true)

}
