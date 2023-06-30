{
  inputs,
  pkgs,
  ...
}: let
  cardano-config = pkgs.writeTextFile {
    name = "config-preprod.json";
    text = builtins.toJSON inputs.cardano-node.environments.${pkgs.system}.preprod.networkConfig;
  };
  ogmios = inputs.ctl-infra.inputs.ogmios.outputs.packages.${pkgs.system}."ogmios:exe:ogmios";
in
  pkgs.writeScriptBin "ogmios-preprod" ''
    exec ${ogmios}/bin/ogmios --node-socket $CARDANO_NODE_SOCKET_PATH --node-config ${cardano-config}
  ''
