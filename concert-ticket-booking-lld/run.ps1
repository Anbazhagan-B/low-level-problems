# Compile and run the concert ticket booking demo (needs JDK 11+).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force -Path out | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
javac -d out $sources
java -cp out concertbooking.demo.Demo
