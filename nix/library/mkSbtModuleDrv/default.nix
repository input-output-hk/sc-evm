{
  lib,
  coreutils,
  solc,
  makeWrapper,
  jre_headless,
  gawk,
  gnused,
  protobuf,
  runtimeShell,
  substituteAll,
  callPackage,
  writeText,
}: {
  name,
  buildCommand,
  buildOutput,
  src,
  lockPath,
  rev ? "dirty",
  extraPassthru ? {},
  version ? let
    versionFile = builtins.readFile "${src}/version.sbt";
    captures = builtins.match ''.* := "([^"]+)".*'' versionFile;
  in
    builtins.elemAt captures 0,
  protocPatch ? ./protoc.patch,
}: let
  PATH = lib.makeBinPath [
    jre_headless
    solc
    coreutils
    gawk
    gnused
  ];

  # filter out mentions of protobridge, which is unable to execute
  protoc-wrapper = callPackage ./protoc-wrapper.nix {inherit runtimeShell;};

  sbt = callPackage ./sbt.nix {inherit PATH;};

  # local maven repo for SBT to fetch dependencies from; built from ./deps.lock
  mavenRepo = callPackage ./maven-repo.nix {inherit lockPath;};

  repoConfig = writeText "repositories" ''
    [repositories]
      local
      nix-maven: file://${mavenRepo}
      iog-nexus: file://${mavenRepo}/maven-release
  '';
in
  (sbt.mkDerivation {
    pname = name;
    inherit src version;

    depsSha256 = null;

    nativeBuildInputs = [solc protobuf makeWrapper];

    PROTOCBRIDGE_SHELL = runtimeShell;

    patches = [
      (substituteAll {
        src = protocPatch;
        protobuf = protoc-wrapper;
      })
    ];

    # this is the command used to to create the ./deps.lock outside of Nix
    # It is never called during the build
    depsWarmupCommand = ''
      export PROTOC_CACHE=".nix/protoc-cache"
      sbt "dependencyList ; consoleQuick ; protocGenerate" <<< ":quit"
    '';

    NIX_GIT_REVISION = let
      maybeRev =
        if rev == "not-a-commit"
        then "dirty"
        else rev;
    in
      maybeRev;

    # force override SBT repositories to only our Nix built Maven repo
    buildPhase = ''
      runHook preBuild

      sbt ${buildCommand} -Dsbt.repository.config=${repoConfig} -Dsbt.override.build.repos=true

      runHook postBuild
    '';

    installPhase = ''
      mkdir -p $out/
      cp -r ${buildOutput} $out/

      # wrap executable so that java is available at runtime
      for p in $(find $out/bin/* -executable -type f); do
      wrapProgram "$p" \
      --prefix PATH : ${PATH}
      done
    '';

    meta.description = "A Proof-of-Stake sidechain based on Ouroboros-BFT.";
    passthru =
      extraPassthru
      // {
        inherit sbt mavenRepo;
      };
  })
  .overrideAttrs (prev: {
    # No longer needed for the build
    deps = null;
    # explicitly overwrite the `postConfigure` phase, otherwise it
    # references the now null `deps` derivation.
    postConfigure = ''
      mkdir -p .nix/ivy
      # SBT expects a "local" prefix to each organization for plugins
      for repo in ${mavenRepo}/sbt-plugin-releases/*; do
        ln -s $repo .nix/ivy/local''${repo##*/}
      done
    '';
    passthru =
      prev.passthru
      // {
        # used by `std //automation/jobs:lock-deps`
        depsDerivation = prev.deps.overrideAttrs (_: {
          outputHash = null;
        });
      };
  })
