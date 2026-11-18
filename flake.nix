{
  description = "Herdr GPUI: a native GPUI client for an existing local Herdr daemon";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # Build with exactly the toolchain rust-toolchain.toml pins, not whatever
    # rustc the nixpkgs revision happens to carry.
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      rust-overlay,
    }:
    let
      inherit (nixpkgs) lib;
      # GPUI's Linux backend is the only one this flake builds; macOS users
      # install the signed DMG or the Homebrew cask instead.
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems =
        f:
        lib.genAttrs systems (
          system:
          f (
            import nixpkgs {
              inherit system;
              overlays = [ rust-overlay.overlays.default ];
            }
          )
        );
      # The pinned toolchain (with clippy and rustfmt) and the native libraries
      # GPUI's Linux backend builds against, shared by the package and the CI shell.
      toolchainFor = pkgs: pkgs.rust-bin.fromRustupToolchainFile ./rust-toolchain.toml;
      rustPlatformFor =
        pkgs:
        let
          toolchain = toolchainFor pkgs;
        in
        pkgs.makeRustPlatform {
          cargo = toolchain;
          rustc = toolchain;
        };
      # Loaded with dlopen at runtime, so the linker never records them.
      runtimeLibrariesFor = pkgs: [
        pkgs.vulkan-loader
        pkgs.wayland
      ];
      buildLibrariesFor =
        pkgs:
        [
          pkgs.alsa-lib
          pkgs.fontconfig
          pkgs.freetype
          pkgs.libxkbcommon
          pkgs.libxcb
        ]
        ++ runtimeLibrariesFor pkgs;
      package =
        pkgs:
        let
          rustPlatform = rustPlatformFor pkgs;
          runtimeLibraries = runtimeLibrariesFor pkgs;
        in
        rustPlatform.buildRustPackage {
          pname = "herdr-gpui";
          version = "0-unstable-${lib.substring 0 8 (self.lastModifiedDate or "19700101")}";
          src = lib.fileset.toSource {
            root = ./.;
            fileset = lib.fileset.unions [
              ./Cargo.toml
              ./Cargo.lock
              ./rust-toolchain.toml
              ./crates
              ./assets
              # The agent skill is compiled into the executable.
              ./skills
              ./scripts/release/herdr-gpui.desktop
            ];
          };
          cargoLock.lockFile = ./Cargo.lock;
          cargoBuildFlags = [
            "-p"
            "herdr-gpui"
          ];
          # The optimized CLI suite the release workflow runs; the rest of the
          # workspace suite needs a desktop or a live daemon.
          cargoTestFlags = [
            "-p"
            "herdr-gpui"
            "--test"
            "cli"
          ];
          nativeBuildInputs = [
            pkgs.pkg-config
            rustPlatform.bindgenHook
          ];
          buildInputs = buildLibrariesFor pkgs;
          # No updater key is embedded, so this build never replaces itself;
          # Nix owns the store path and upgrades come from the flake.
          postInstall = ''
            install -Dm644 scripts/release/herdr-gpui.desktop \
              $out/share/applications/herdr-gpui.desktop
            install -Dm644 assets/icons/herdr-icon-square-clean.svg \
              $out/share/icons/hicolor/scalable/apps/herdr-gpui.svg
          '';
          postFixup = ''
            patchelf --add-rpath ${lib.makeLibraryPath runtimeLibraries} $out/bin/herdr-gpui
          '';
          meta = {
            description = "Native GPUI client for an existing local Herdr daemon";
            homepage = "https://github.com/penso/herdr-gpui";
            license = lib.licenses.asl20;
            mainProgram = "herdr-gpui";
            platforms = systems;
          };
        };
    in
    {
      packages = forAllSystems (pkgs: {
        default = package pkgs;
        herdr-gpui = package pkgs;
      });
      # Host-side Rust only, for jobs run off this machine (lease-run/jobrun use
      # `nix develop .#ci`): clippy and the tests of the android-gpui and
      # android-remote workspaces. No Android SDK, NDK or JDK, and no secrets.
      devShells = forAllSystems (pkgs: {
        ci = pkgs.mkShell {
          name = "herdr-gpui-android-ci";
          packages = [
            (toolchainFor pkgs)
            pkgs.pkg-config
          ];
          nativeBuildInputs = [ (rustPlatformFor pkgs).bindgenHook ];
          buildInputs = buildLibrariesFor pkgs;
        };
      });
      overlays.default = final: _prev: {
        herdr-gpui = self.packages.${final.stdenv.hostPlatform.system}.default;
      };
    };
}
