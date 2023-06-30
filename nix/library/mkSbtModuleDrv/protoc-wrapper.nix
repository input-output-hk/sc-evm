{
  writeShellScriptBin,
  protobuf,
  runtimeShell,
}:
writeShellScriptBin "protoc" ''
  set -e

  for f in "$@"; do
    echo ''${f##*=}
  done | grep protocbridge | xargs sed -i "1s|.*|#!${runtimeShell}|"

  exec ${protobuf}/bin/protoc "$@"
''
