# Contributing to Technolinator

Yes, please.

## Requirements

* JDK 25+
* [cdxgen](https://github.com/CycloneDX/cdxgen) needs to be installed and available in $PATH
* [grype](https://github.com/anchore/grype) needs to be installed and available in $PATH
* [sbomqs](https://github.com/interlynk-io/sbomqs) needs to be installed and available in $PATH
* [depscan](https://github.com/owasp-dep-scan/dep-scan) needs to be installed and available in $PATH

### Installing the required tools

[install-requirements.sh](install-requirements.sh) installs the latest versions of cdxgen (incl. plugins), grype, sbomqs, depscan and [nydus](https://github.com/dragonflyoss/nydus) for Linux and macOS, mirroring the preparation steps of the GitHub Actions workflows.
It needs `curl`, `tar`, `node`/`npm` and `python3` and places the binaries (or links to them) into `~/.local/bin` by default; npm packages and the depscan virtualenv are kept in `~/.local/share/technolinator`.
Afterwards, make the tools available in your current shell by sourcing [update-path.sh](update-path.sh), which adds the install directory to your `$PATH` (use the same `BIN_DIR` as for the installation, if set):

```shell
./docs/install-requirements.sh
source docs/update-path.sh
```

To make this permanent, source it from your shell profile (e.g. `~/.bashrc`) or add the install directory to `$PATH` yourself.

Other options:

```shell
# pin versions, choose another target directory or skip tools if needed
BIN_DIR=~/bin GRYPE_VERSION=v0.121.3 SKIP="nydus" ./docs/install-requirements.sh
BIN_DIR=~/bin source docs/update-path.sh
```

See the header of the script for all available options. The versions used by the CI are defined in [.github/workflows/ci.yml](../.github/workflows/ci.yml).
