#!/usr/bin/env bash
# Compiles the converter and converts every repository under samples/ into AdHoc/.
set -eu
cd "$(dirname "$0")"
rm -rf out AdHoc
mkdir -p out AdHoc
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.DSDL2AdHoc samples AdHoc
