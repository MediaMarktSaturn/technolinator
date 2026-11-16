#!/usr/bin/env bash
#
# Installs the tools Technolinator requires for local development and testing:
# cdxgen (+ plugins), grype, sbomqs, depscan and nydus.
#
# Mirrors the steps of .github/actions/install-requirements/action.yaml, but installs the latest
# versions by default. Pin a specific version by setting the matching environment variable, e.g.
#
#   GRYPE_VERSION=v0.121.3 ./install-requirements.sh
#
# Environment variables:
#   BIN_DIR                  target directory for (links to) the binaries (default: $HOME/.local/bin)
#   DATA_DIR                 directory for npm packages and the depscan virtualenv
#                            (default: ${XDG_DATA_HOME:-$HOME/.local/share}/technolinator)
#   CDXGEN_VERSION           @cyclonedx/cdxgen npm version (default: latest)
#   CDXGEN_PLUGINS_VERSION   @cdxgen/cdxgen-plugins-bin npm version (default: latest)
#   GRYPE_VERSION            anchore/grype release, with or without v-prefix (default: latest)
#   SBOMQS_VERSION           interlynk-io/sbomqs release, with or without v-prefix (default: latest)
#   DEPSCAN_VERSION          owasp-depscan version, with or without v-prefix (default: latest)
#   NYDUS_VERSION            dragonflyoss/nydus release, with or without v-prefix (default: latest)
#   SKIP                     space separated list of tools to skip: cdxgen grype sbomqs depscan nydus
#
# Requirements: bash, curl, tar, sha256sum or shasum, node + npm (cdxgen), python3 (depscan)

set -euo pipefail

BIN_DIR="${BIN_DIR:-$HOME/.local/bin}"
SKIP="${SKIP:-}"
DATA_DIR="${DATA_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/technolinator}"
NPM_PREFIX="$DATA_DIR/npm"
DEPSCAN_VENV="$DATA_DIR/depscan-venv"

info() { echo "INFO: $*"; }
warn() { echo "WARN: $*" >&2; }
fail() { echo "ERROR: $*" >&2; exit 1; }

skipped() { [[ " $SKIP " == *" $1 "* ]]; }
need() { command -v "$1" > /dev/null 2>&1 || fail "'$1' is required but not available in PATH"; }

case "$(uname -s)" in
    Linux) OS=linux; OS_CAP=Linux ;;
    Darwin) OS=darwin; OS_CAP=Darwin ;;
    *) fail "Unsupported operating system: $(uname -s)" ;;
esac

case "$(uname -m)" in
    x86_64 | amd64) ARCH=amd64; ARCH_ALT=x86_64 ;;
    aarch64 | arm64) ARCH=arm64; ARCH_ALT=arm64 ;;
    *) fail "Unsupported architecture: $(uname -m)" ;;
esac

need curl
need tar

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
mkdir -p "$BIN_DIR"

sha256() {
    if command -v sha256sum > /dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# Resolves the latest release tag of a GitHub repository (e.g. 'v1.2.3') by following the
# 'releases/latest' redirect, which doesn't count against the GitHub API rate limit.
latest_release() {
    local url
    url="$(curl -fsSL -o /dev/null -w '%{url_effective}' "https://github.com/$1/releases/latest")" \
        || fail "Could not determine latest release of $1"
    if [[ "$url" != *"/releases/tag/"* ]]; then
        fail "Could not determine latest release of $1, got redirected to: $url"
    fi
    echo "${url##*/}"
}

# Downloads $url to $file and verifies its checksum against the given checksum file url;
# the checksum file has to contain lines starting with a files hash, optionally followed by its name.
download_verified() {
    local url="$1" file="$2" checksums_url="$3" hash
    curl -fsSL "$url" -o "$file" || fail "Download failed: $url"
    curl -fsSL "$checksums_url" -o "$file.checksums" || fail "Download failed: $checksums_url"
    hash="$(sha256 "$file")"
    if [[ ! "$hash" =~ ^[0-9a-f]{64}$ ]]; then
        fail "Could not compute checksum of $file"
    fi
    if ! grep -qiE "^${hash}([[:space:]]|$)" "$file.checksums"; then
        fail "Invalid checksum for $url"
    fi
}

install_cdxgen() {
    need npm
    local version="${CDXGEN_VERSION:-latest}" plugins_version="${CDXGEN_PLUGINS_VERSION:-latest}"
    info "Installing cdxgen $version and cdxgen-plugins-bin $plugins_version into $NPM_PREFIX"
    npm install -g --prefix "$NPM_PREFIX" "@cyclonedx/cdxgen@$version"
    npm install -g --prefix "$NPM_PREFIX" "@cdxgen/cdxgen-plugins-bin@$plugins_version"
    # npm places the executables of global packages into <prefix>/bin, link them into BIN_DIR
    local cmd
    for cmd in "$NPM_PREFIX"/bin/*; do
        [[ -e "$cmd" ]] && ln -sf "$cmd" "$BIN_DIR/$(basename "$cmd")"
    done
}

install_grype() {
    local version="${GRYPE_VERSION:-$(latest_release anchore/grype)}"
    version="${version#v}"
    info "Installing grype $version"
    local base="https://github.com/anchore/grype/releases/download/v${version}"
    download_verified "$base/grype_${version}_${OS}_${ARCH}.tar.gz" "$TMP_DIR/grype.tar.gz" "$base/grype_${version}_checksums.txt"
    tar xf "$TMP_DIR/grype.tar.gz" -C "$TMP_DIR" grype
    install -m 0755 "$TMP_DIR/grype" "$BIN_DIR/grype"
}

install_sbomqs() {
    local version="${SBOMQS_VERSION:-$(latest_release interlynk-io/sbomqs)}"
    version="${version#v}"
    info "Installing sbomqs $version"
    local base="https://github.com/interlynk-io/sbomqs/releases/download/v${version}"
    download_verified "$base/sbomqs_${version}_${OS_CAP}_${ARCH_ALT}.tar.gz" "$TMP_DIR/sbomqs.tgz" "$base/checksums.txt"
    tar xf "$TMP_DIR/sbomqs.tgz" -C "$TMP_DIR" sbomqs
    install -m 0755 "$TMP_DIR/sbomqs" "$BIN_DIR/sbomqs"
}

install_nydus() {
    if [[ "$OS" != "linux" ]]; then
        warn "nydus is only available for Linux, skipping"
        return
    fi
    local version="${NYDUS_VERSION:-$(latest_release dragonflyoss/nydus)}"
    version="${version#v}"
    info "Installing nydus $version"
    local base="https://github.com/dragonflyoss/nydus/releases/download/v${version}"
    local name="nydus-static-v${version}-linux-${ARCH}.tgz"
    download_verified "$base/$name" "$TMP_DIR/nydus.tgz" "$base/$name.sha256sum"
    tar xf "$TMP_DIR/nydus.tgz" -C "$TMP_DIR"
    find "$TMP_DIR/nydus-static" -maxdepth 1 -type f -exec install -m 0755 {} "$BIN_DIR/" \;
}

install_depscan() {
    need python3
    local version="${DEPSCAN_VERSION:-}"
    version="${version#v}"
    local spec="owasp-depscan"
    [[ -n "$version" ]] && spec="owasp-depscan==$version"
    info "Installing depscan (${version:-latest}) into virtualenv $DEPSCAN_VENV"
    python3 -m venv "$DEPSCAN_VENV"
    "$DEPSCAN_VENV/bin/pip" install -q --upgrade pip
    "$DEPSCAN_VENV/bin/pip" install -q --upgrade "$spec"
    ln -sf "$DEPSCAN_VENV/bin/depscan" "$BIN_DIR/depscan"
    # clean up the generic 'scan' link created by previous versions of this script
    if [[ -L "$BIN_DIR/scan" && "$(readlink "$BIN_DIR/scan")" == "$DEPSCAN_VENV/bin/scan" ]]; then
        rm -f "$BIN_DIR/scan"
    fi
}

for tool in cdxgen grype sbomqs depscan nydus; do
    if skipped "$tool"; then
        info "Skipping $tool"
    else
        "install_$tool"
    fi
done

echo
info "Installed tools into $BIN_DIR"
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) warn "$BIN_DIR is not in your PATH, add it via: source $(dirname "${BASH_SOURCE[0]}")/update-path.sh" ;;
esac

for cmd in cdxgen grype sbomqs depscan nydus; do
    if command -v "$cmd" > /dev/null 2>&1; then
        info "$cmd: $(command -v "$cmd")"
    else
        warn "$cmd not found in PATH"
    fi
done
