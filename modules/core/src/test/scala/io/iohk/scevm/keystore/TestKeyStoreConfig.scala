package io.iohk.scevm.keystore

import io.iohk.scevm.config.KeyStoreConfig

import java.nio.file.Files

object TestKeyStoreConfig {
  def config(): KeyStoreConfig = {
    val tmpDir = Files.createTempDirectory("test-keystore")
    tmpDir.toFile.deleteOnExit()
    KeyStoreConfig(keyStoreDir = tmpDir.toFile.getAbsolutePath, minimalPassphraseLength = 8, allowNoPassphrase = true)
  }
}
