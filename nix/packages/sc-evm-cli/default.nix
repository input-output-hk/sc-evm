{
  inputs,
  library,
  lockPath,
  extraPassthru ? {},
  rev ? "dirty",
}:
library.mkSbtModuleDrv {
  name = "sc-evm-cli";
  buildCommand = "scEvmCli/stage";
  buildOutput = "tooling/scEvmCli/target/universal/stage/*";
  src = inputs.self;
  inherit extraPassthru rev lockPath;
}
