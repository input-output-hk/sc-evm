{
  inputs,
  pkgs,
  ...
}: let
  cardano-config = pkgs.writeTextFile {
    name = "config-preprod.json";
    text = builtins.toJSON inputs.cardano-node.environments.${pkgs.system}.preprod.networkConfig;
  };
  ogmios = inputs.ctl-infra.inputs.kupo-nixos.outputs.packages.${pkgs.system}.kupo;
in
  pkgs.writeScriptBin "kupo-preprod" ''
    mkdir $PRJ_DATA_DIR/kupo
    exec ${ogmios}/bin/kupo \
      --node-socket $CARDANO_NODE_SOCKET_PATH \
      --node-config ${cardano-config} \
      --workdir "$PRJ_DATA_DIR/kupo" \
      --match '*' \
      --since origin \
      --host 127.0.0.1 \
      --port 9999
  ''
