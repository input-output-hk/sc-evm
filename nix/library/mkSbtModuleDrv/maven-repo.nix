{
  lockPath,
  lib,
  runCommand,
  ...
}: let
  lock = lib.importJSON lockPath;
in
  runCommand "maven-repo" {} ''
    pairs=(${
      builtins.concatStringsSep "\n"
      (map (dep: "${dep.path}:${builtins.fetchurl dep.fetch}") lock)
    })
    for pair in ''${pairs[@]}; do
      IFS=':' read path store <<< "$pair"
      path="''${path#*/*/}"

      mkdir -p "$out/''${path%/*}"
      ln -s "$store" "$out/$path"
    done
  ''
