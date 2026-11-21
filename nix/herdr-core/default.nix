# herdr-core: the runtime that keeps terminals and agents running for the
# Herdr app, and its `hc` CLI. A core installed by Nix is never updated by the
# app or `hc update` ("update it in your Nix configuration").
#
# x86_64-linux (laptop, server) builds our fork (flake input herdr-core-src,
# github:404Wolf/herdr-core) from source, as the release does: a static musl
# binary, so `hc machine add` can still copy this very hc to a machine without
# Nix. Elsewhere the official release: the Mac runs the core from its signed
# bundle (local network and microphone permissions follow its identity), and
# no machine of ours is aarch64-linux.
{
  lib,
  stdenv,
  stdenvNoCC,
  fetchurl,
  makeRustPlatform,
  rust-bin,
  zig_0_16,
  pkgsStatic,
  src,
}:
let
  version = "0.1.18";
  releases = {
    aarch64-linux = {
      target = "linux-aarch64";
      sha256 = "cf33393cf950bb763734dc3e16610ab9daf8492391a4ef0de56026cd7f752853";
    };
    aarch64-darwin = {
      target = "macos-aarch64";
      sha256 = "77d8af092e25ae650f4424d6dad27514f6916a11d807fb0752858cd27c21e4fc";
    };
  };
  system = stdenv.hostPlatform.system;
  meta = {
    description = "Terminals for coding agents: the runtime behind the Herdr app";
    homepage = "https://github.com/404Wolf/herdr-core";
    license = lib.licenses.asl20;
    mainProgram = "hc";
    platforms = [ "x86_64-linux" ] ++ lib.attrNames releases;
  };

  target = "x86_64-unknown-linux-musl";
  toolchain = rust-bin.stable.latest.minimal.override { targets = [ target ]; };
  rustPlatform = makeRustPlatform {
    cargo = toolchain;
    rustc = toolchain;
  };
  # build.rs builds libghostty-vt from the vendored Ghostty with Zig, which
  # would fetch Ghostty's Zig packages; fetched here instead.
  ghosttyDeps = zig_0_16.fetchDeps {
    pname = "herdr-core-ghostty";
    inherit version;
    src = "${src}/vendor/ghostty";
    fetchAll = true;
    hash = "sha256-G9xbZ/pyQTDIfzG0h++i70a45zpwyYpZMBZfAnoOQw0=";
  };
  linker = "${pkgsStatic.stdenv.cc}/bin/${pkgsStatic.stdenv.cc.targetPrefix}cc";

  fromSource = stdenv.mkDerivation {
    pname = "herdr-core";
    version = "${version}-unstable-${src.shortRev or "dirty"}-grid";
    inherit src meta;
    # Same pinned source and wire generation: interactive app viewers render
    # the ordered PTY grid while reporting their available pane size separately.
    patches = [
      ./patches/interactive-grid.patch
      ./patches/viewer-visibility.patch
      ./patches/visibility-fallback.patch
      ./patches/visibility-reoffer.patch
      ./patches/desktop-priority.patch
      ./patches/viewer-diagnostics.patch
      ./patches/build-skew.patch
      ./patches/focused-window.patch
      ./patches/hidden-reporter-fallback.patch
      ./patches/phone-leads-hidden-desktop.patch
      ./patches/resize-history.patch
      ./patches/relay-reattach-credit.patch
      ./patches/latest-input-leads.patch
      ./patches/registry-writer.patch
      ./patches/send-caller-gone.patch
      ./patches/draft-flag-expiry.patch
      ./patches/repaint-seam.patch
      ./patches/paint-hyperlinks.patch
      # Stage 3 core features (incarnation, guarded send, bounded read), as home-manager-conf
      # 8be530a (#151) adds them: the offline-s2 emulator test reads transcripts with them.
      ./patches/guarded-send.patch
    ];
    cargoDeps = rustPlatform.importCargoLock { lockFile = "${src}/Cargo.lock"; };
    nativeBuildInputs = [
      toolchain
      zig_0_16
      rustPlatform.cargoSetupHook
    ];
    env = {
      "CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER" = linker;
    };
    buildPhase = ''
      runHook preBuild
      export HERDR_CORE_BUILD="${version}-${src.shortRev or "dirty"}-grid/$(basename "$out" | cut -c1-12)"
      export ZIG_GLOBAL_CACHE_DIR=$TMPDIR/zig-global
      mkdir -p $ZIG_GLOBAL_CACHE_DIR/tmp
      cp -rLT ${ghosttyDeps} $ZIG_GLOBAL_CACHE_DIR/p
      chmod -R u+w $ZIG_GLOBAL_CACHE_DIR/p
      # Nixpkgs' musl gcc brings the C runtime; from Rust's own self-contained
      # copy only libunwind, which the musl target links and gcc lacks.
      mkdir -p $TMPDIR/unwind
      ln -s ${toolchain}/lib/rustlib/${target}/lib/self-contained/libunwind.a $TMPDIR/unwind/
      # The core names its build by the linker's build id (client.rs).
      export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS="-C link-self-contained=no -L native=$TMPDIR/unwind -C link-arg=-Wl,--build-id"
      cargo build --release --locked --offline --target ${target} -p herdr-core -j $NIX_BUILD_CORES
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      install -Dm755 target/${target}/release/herdr-core $out/bin/herdr-core
      ln -s herdr-core $out/bin/hc
      runHook postInstall
    '';
    # Static: nothing to patch. Unstripped, like the release.
    dontPatchELF = true;
    dontStrip = true;
    passthru = { inherit ghosttyDeps; };
  };

  release =
    let
      source = releases.${system} or (throw "herdr-core: no build for ${system}");
    in
    stdenvNoCC.mkDerivation {
      pname = "herdr-core";
      inherit version meta;
      src = fetchurl {
        url = "https://releases.herdr.dev/core/${version}/herdr-core-${source.target}.tar.gz";
        inherit (source) sha256;
      };
      sourceRoot = ".";
      dontPatchELF = true;
      dontStrip = true;
      installPhase = ''
        runHook preInstall
      ''
      + (
        if stdenvNoCC.hostPlatform.isDarwin then
          ''
            mkdir -p $out/Applications $out/bin
            cp -R "Herdr Core.app" $out/Applications/
            ln -s "$out/Applications/Herdr Core.app/Contents/MacOS/herdr-core" $out/bin/herdr-core
          ''
        else
          ''
            install -Dm755 herdr-core $out/bin/herdr-core
          ''
      )
      + ''
        ln -s herdr-core $out/bin/hc
        runHook postInstall
      '';
    };
in
if system == "x86_64-linux" then fromSource else release
