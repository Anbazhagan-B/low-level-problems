#!/usr/bin/env bash
# Compile and run the concert ticket booking demo (no build tool required; needs JDK 11+).
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -d out $(find src -name '*.java')
java -cp out concertbooking.demo.Demo
