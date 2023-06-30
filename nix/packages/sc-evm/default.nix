{
  inputs,
  library,
  lockPath,
  extraPassthru ? {},
  rev ? "dirty",
}:
library.mkSbtModuleDrv {
  name = "sc-evm";
  buildCommand = "node/stage";
  buildOutput = "modules/node/target/universal/stage/*";
  src = inputs.self;
  inherit extraPassthru lockPath rev;
}
