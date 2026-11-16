#!/usr/bin/env bash
#
# Sets up the current shell for working on Technolinator with the tools installed by
# install-requirements.sh. This script has to be *sourced*, running it has no effect on your shell:
#
#   source docs/update-path.sh
#
# What it does:
#   * prepends the tools' bin directory (BIN_DIR, default: $HOME/.local/bin) to PATH, if not yet contained
#   * disables update checks of grype and sbomqs, as done in the container image
#   * reports the resolved location of each required tool
#
# Environment variables:
#   BIN_DIR   directory the tools were installed to, same as for install-requirements.sh
#             (default: $HOME/.local/bin)
#
# To have it available in every shell, source it from your shell profile (~/.bashrc, ~/.zshrc, ...)
# using the absolute path of this file.

if ! (return 0 2> /dev/null); then
    echo "ERROR: This script needs to be sourced: source ${0}" >&2
    exit 1
fi

_technolinator_bin_dir="${BIN_DIR:-$HOME/.local/bin}"

if [ ! -d "$_technolinator_bin_dir" ]; then
    echo "WARN: $_technolinator_bin_dir does not exist, run install-requirements.sh first (or set BIN_DIR)" >&2
else
    case ":$PATH:" in
        *":$_technolinator_bin_dir:"*) ;;
        *) export PATH="$_technolinator_bin_dir:$PATH"; echo "INFO: Added $_technolinator_bin_dir to PATH" ;;
    esac
fi

export INTERLYNK_DISABLE_VERSION_CHECK="true"
export GRYPE_CHECK_FOR_APP_UPDATE="false"

for _technolinator_cmd in cdxgen grype sbomqs depscan; do
    if command -v "$_technolinator_cmd" > /dev/null 2>&1; then
        echo "INFO: $_technolinator_cmd: $(command -v "$_technolinator_cmd")"
    else
        echo "WARN: $_technolinator_cmd not found in PATH" >&2
    fi
done

unset _technolinator_bin_dir _technolinator_cmd
