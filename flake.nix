{
  description = "clanker — Kotlin Multiplatform + Android (Jetpack Compose) agentic AI harness";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        buildToolsVersion = "36.0.0";

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          cmdLineToolsVersion = "20.0"; # highest available in this nixpkgs pin
          platformToolsVersion = "36.0.2";
          buildToolsVersions = [ buildToolsVersion ];
          platformVersions = [ "36" ]; # compileSdk 36
          includeEmulator = false;
          includeSystemImages = false;
          includeSources = false;
          includeNDK = false;
        };

        androidSdk = androidComposition.androidsdk;
        sdkRoot = "${androidSdk}/libexec/android-sdk";
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = [
            androidSdk
            pkgs.jdk21 # AGP 9.0 min JDK 17; 21 preferred
            pkgs.gradle # bootstrap only; ./gradlew wrapper takes over
          ];

          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;

          # aapt2 under Nix: the prebuilt aapt2 Gradle caches won't run on NixOS.
          # Point Gradle at the Nix-provided aapt2 via a project property. The attribute
          # name is QUOTED so Nix treats the dot as part of the env-var name rather than
          # as attribute nesting. (The wiki's GRADLE_OPTS form is broken — nixpkgs #402297.)
          "ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride" =
            "${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";

          shellHook = ''
            export GRADLE_USER_HOME="$PWD/.gradle"
            echo "clanker dev shell"
            echo "  Android SDK: $ANDROID_HOME"
            echo "  JDK: $(java -version 2>&1 | head -1)"
          '';
        };
      });
}
