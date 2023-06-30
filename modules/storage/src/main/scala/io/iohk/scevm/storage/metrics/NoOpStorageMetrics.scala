package io.iohk.scevm.storage.metrics

import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.metrics.instruments.Histogram
import io.prometheus.client.{Histogram => ClientHistogram}

final case class NoOpStorageMetrics[F[_]: Sync]() extends StorageMetrics[F] {
  override val readTimeForBlockHeaders: Histogram[F]         = Histogram.noop
  override val writeTimeForBlockHeaders: Histogram[F]        = Histogram.noop
  override val readTimeForBlockBodies: Histogram[F]          = Histogram.noop
  override val writeTimeForBlockBodies: Histogram[F]         = Histogram.noop
  override val readTimeForReverseStorage: Histogram[F]       = Histogram.noop
  override val readTimeForStableNumberMapping: Histogram[F]  = Histogram.noop
  override val writeTimeForStableNumberMapping: Histogram[F] = Histogram.noop
  override val readTimeForStableTransaction: Histogram[F]    = Histogram.noop
  override val writeTimeForStableIndexesUpdate: Histogram[F] = Histogram.noop
  override val readTimeForLatestStableNumber: Histogram[F]   = Histogram.noop
  override val writeTimeForLatestStableNumber: Histogram[F]  = Histogram.noop
  override val readTimeForReceipts: Histogram[F]             = Histogram.noop
  override val writeTimeForReceipts: Histogram[F]            = Histogram.noop
  override val readTimeForEvmCode: ClientHistogram           = Histogram.noOpClientHistogram()
  override val writeTimeForEvmCode: ClientHistogram          = Histogram.noOpClientHistogram()
  override val readTimeForNode: ClientHistogram              = Histogram.noOpClientHistogram()
  override val writeTimeForNode: ClientHistogram             = Histogram.noOpClientHistogram()

  override def unregister(): F[Unit] = ().pure
}
