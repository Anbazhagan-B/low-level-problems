#!/usr/bin/env bash
# Compile and run the vending machine demo (no build tool required; needs JDK 11+).
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -d out $(find src -name '*.java')
java -cp out vendingmachine.demo.Demo
