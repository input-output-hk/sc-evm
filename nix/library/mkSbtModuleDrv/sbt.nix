{
  sbt,
  fetchFromGitHub,
  callPackage,
  makeWrapper,
  PATH,
}: let
  sbt-derivation = builtins.fetchTree {
    type = "github";
    owner = "zaninime";
    repo = "sbt-derivation";
    rev = "920b6f187937493371e2b1687261017e6e014cf1";
    narHash = "sha256-Z7eWMLreLtiSiJ3nWDWBy1w9WNEFexkYCgT/dWZF7yo=";
  };
in
  (callPackage "${sbt-derivation}/pkgs/custom-sbt" {
    sbt = sbt.overrideAttrs (prev: {
      nativeBuildInputs = (prev.nativeBuildInputs or []) ++ [makeWrapper];
      postInstall = ''
        rm $out/bin/sbt
        makeWrapper $out/share/sbt/bin/sbt $out/bin/sbt --prefix PATH : ${PATH}
      '';
    });
  })
  .overrideAttrs (prev: {
    passthru =
      (prev.passthru or {})
      // {
        mkDerivation = callPackage "${sbt-derivation}/pkgs/sbt-derivation" {};
      };
  })
