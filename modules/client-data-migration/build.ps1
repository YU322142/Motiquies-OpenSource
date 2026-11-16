param([Parameter(Mandatory=$true)][string]$SdkRoot,[Parameter(Mandatory=$true)][string]$JavaHome,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
& python (Join-Path $PSScriptRoot 'build.py') --sdk-root $SdkRoot --java-home $JavaHome --output $Output
exit $LASTEXITCODE
