{
  description = "SC_EVM";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    devshell.url = "github:numtide/devshell";
    cardano-node.url = "github:input-output-hk/cardano-node/1.35.4";
    cardano-db-sync.url = "github:input-output-hk/cardano-db-sync/13.0.4";

    trustless-ctl.url = "github:input-output-hk/sc-trustless-ctl/main";
    ctl-infra.follows = "trustless-ctl/cardano-transaction-lib";
    sbt-derivation.url = "github:zaninime/sbt-derivation";
  };

  outputs = inputs @ {
    self,
    nixpkgs,
    flake-utils,
    devshell,
    cardano-node,
    trustless-ctl,
    ctl-infra,
    sbt-derivation,
    ...
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [devshell.overlays.default];
        };
        inherit (pkgs.devshell) mkShell;
        jdk = pkgs.jdk17;
        baseCommands = with pkgs; [
          {
            name = "java";
            package = jdk;
          }
          {package = sbt.override {jre = jdk;};}
          {package = solc.override {z3Support = false;};}
          {package = netcat-gnu;}
          {package = nodejs-14_x;}
        ];
        library = import nix/library {inherit pkgs;};
      in {
        devShells.default = mkShell {
          commands = baseCommands;
        };
        devShells.sidechain = mkShell {
          commands = with pkgs;
            baseCommands
            ++ [
              {
                name = "cardano-node-preprod";
                help = "Cardano node configured for preprod";
                package = import ./nix/packages/cardano-node-preprod {inherit inputs pkgs;};
              }
              {
                name = "cardano-db-sync-preprod";
                help = "Cardano db-sync configured for preprod";
                package = import ./nix/packages/cardano-db-sync-preprod {inherit inputs pkgs;};
              }
              {
                package = cardano-node.packages.${system}.bech32;
                name = "bech32";
              }
              {
                name = "cardano-cli";
                help = "CLI for interacting with cardano-node.";
                package = cardano-node.packages.${system}.cardano-cli;
              }
              {package = postgresql;}
              {package = b2sum;}
              {
                package = xxd;
                name = "xxd";
                help = "Make a hexdump or do the reverse.";
              }
              {package = haskellPackages.cbor-tool;}
              {
                package = trustless-ctl.outputs.packages.${system}.sidechain-main-cli;
                name = "sidechain-main-cli";
                help = "CLI application to execute Trustless Sidechain Cardano endpoints";
              }
              {package = jq;}
              {package = yq;}
            ];
          env = [
            {
              name = "CARDANO_NODE_SOCKET_PATH";
              eval = "$PRJ_DATA_DIR/state-node-preprod/node.socket";
            }
          ];
        };
        devShells.ctl-infra = mkShell {
          commands = [
            {
              package = trustless-ctl.outputs.packages.${system}.sidechain-main-cli;
              name = "sidechain-main-cli";
              help = "CLI application to execute Trustless Sidechain Cardano endpoints";
            }
            {
              package = import ./nix/packages/ogmios-preprod {inherit inputs pkgs;};
              name = "ogmios-preprod";
              help = "Ogmios is a lightweight bridge interface for cardano-node. This one is configured to run on top of preprod";
            }
            {
              package = import ./nix/packages/kupo-preprod {inherit inputs pkgs;};
              name = "kupo-preprod";
              help = "Kupo indexer. Required by sidechain-main-cli. This one is configured to run on top of preprod";
            }
          ];
          env = [
            {
              name = "CARDANO_NODE_SOCKET_PATH";
              eval = "$PRJ_DATA_DIR/state-node-preprod/node.socket";
            }
          ];
        };
        devShells.retesteth = mkShell {
          commands = with pkgs;
          baseCommands ++ [
            {
              name = "retesteth";
              help = "Tool to run ETS tests";
              package = import ./nix/packages/retesteth {inherit pkgs;};
            }
          ];
        };

        packages = rec {
          sc-evm-cli = import ./nix/packages/sc-evm-cli {
            inherit inputs library;
            lockPath = ./nix/packages/deps.lock;
          };
          sc-evm = import ./nix/packages/sc-evm {
            inherit inputs library;
            lockPath = ./nix/packages/deps.lock;
          };
          default = sc-evm;
        };
      }
    );

  # --- Flake Local Nix Configuration ----------------------------
  nixConfig = {
    extra-substituters = [
      "https://nix-community.cachix.org"
      "https://cache.iog.io"
    ];
    extra-trusted-public-keys = [
      "hydra.iohk.io:f/Ea+s+dFdN+3Y/G+FDgSq+a5NEWhJGzdjvKNGv0/EQ="
      "nix-community.cachix.org-1:mB9FSh9qf2dCimDSUo8Zy7bkq5CX+/rkCWyvRCYg3Fs="
    ];
    allow-import-from-derivation = "true";
  };
  # --------------------------------------------------------------
}
