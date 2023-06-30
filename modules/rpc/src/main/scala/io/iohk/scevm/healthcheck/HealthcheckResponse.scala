package io.iohk.scevm.healthcheck

import io.iohk.scevm.utils.NodeState

final case class HealthcheckResponse(state: NodeState, checks: List[HealthcheckResult]) {
  lazy val isOK: Boolean = checks.forall(_.isOK)
}
