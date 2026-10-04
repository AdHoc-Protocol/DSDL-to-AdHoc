#!/usr/bin/env bash
# Downloads the upstream DSDL definitions into samples/:
#   samples/opencyphal  <- https://github.com/OpenCyphal/public_regulated_data_types  (Cyphal v1: uavcan/, reg/)
#   samples/dronecan    <- https://github.com/dronecan/DSDL                          (DroneCAN: uavcan/, ardupilot/, ...)
set -eu
cd "$(dirname "$0")"
mkdir -p samples
fetch() { # name url
    if [ -d "samples/$1/.git" ]; then git -C "samples/$1" pull --ff-only -q; return; fi
    rm -rf "samples/$1"
    if command -v git >/dev/null 2>&1; then git clone --depth 1 -q "$2" "samples/$1"
    else
        curl -sSfL "$2/archive/refs/heads/master.tar.gz" -o "samples/$1.tgz" && mkdir -p "samples/$1" \
            && tar -xzf "samples/$1.tgz" -C "samples/$1" --strip-components=1 && rm "samples/$1.tgz"
    fi
}
# <folder in samples/> <repository>; both are taken at their default branch, master
REPOS="
opencyphal https://github.com/OpenCyphal/public_regulated_data_types
dronecan   https://github.com/dronecan/DSDL
"
{
    echo "# Where every sample comes from: <path in samples/> <page of the original>; a path ending with / covers every"
    echo "# file under it. Written by fetch-samples.sh; the converter links these pages in the headers of the descriptions."
    while read -r n url; do
        [ -z "$n" ] && continue
        printf '%-12s %s\n' "$n/" "$url/tree/master/"
    done <<< "$REPOS"
} > samples/sources.txt
while read -r n url; do
    [ -z "$n" ] && continue
    fetch "$n" "$url"
done <<< "$REPOS"
echo "opencyphal: $(find samples/opencyphal -name '*.dsdl' | wc -l) .dsdl files"
echo "dronecan:   $(find samples/dronecan -name '*.uavcan' | wc -l) .uavcan files"
