# Drivers for outside engines

Ours, under this repository's BSD 3-Clause licence. Each file is *additive*: it
is copied into a fetched engine's source tree at build time and changes nothing
that was there.

    xskat/skatklar_driver.c     replaces XSkat's X11 layer and its auction
    go-skat/skatklar_driver.go  enabled by -skatklar, which init() sees first

Neither engine is vendored here; `tools/build-external-bots.sh` fetches nothing
either — it builds what is already under `third_party/`, and says what is
missing when there is nothing to build. The protocol both drivers speak, and the
honesty control both carry, are documented in
[`docs/external-bots.md`](../docs/external-bots.md).
