# Own recipe bootstrap. No environment dump, tool download, cache or secret input.
param([Parameter(Mandatory=$true)][ValidateSet('plan','run')][string]$Mode,
      [Parameter(Mandatory=$true)][string]$ApprovedCommit)
$ErrorActionPreference='Stop'
if ($env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:RUNNER_OS -ne 'Windows') { throw 'fresh hosted Windows required' }
if ($env:ImageOS -ne 'win22' -or $env:ImageVersion -ne '20261112.366.7') { throw 'reviewed VM image differs; collect new facts separately' }
if ($env:GITHUB_EVENT_NAME -ne 'workflow_dispatch' -or $env:GITHUB_SHA -cne $ApprovedCommit -or $ApprovedCommit -cnotmatch '^[0-9a-f]{40}$') { throw 'explicit approved commit differs' }
$sdkPython='C:/hostedtoolcache/windows/Python/3.13.17/x64/python.exe'
$sdkCurl='C:/Windows/System32/curl.exe'
. "$PSScriptRoot/bootstrap_paths.ps1"
Assert-PlainFile $sdkPython
Assert-PlainFile $sdkCurl
if ((Get-FileHash -LiteralPath $sdkPython -Algorithm SHA256).Hash.ToLowerInvariant() -cne '7c2fc2b303a4ee3cc14cc17b47bc6e8bca98c78792edd123b846a560da44b203') { throw 'Python fingerprint differs' }
if ((Get-FileHash -LiteralPath $sdkCurl -Algorithm SHA256).Hash.ToLowerInvariant() -cne '73d24149ff289afc49ec41f08918ef9faa727d39ad993e929757dc2ddafab805') { throw 'curl fingerprint differs' }
$env:SDK_PYTHON_VERSION='3.13.17'
# These two literals are sealed only after source preparation; never learned at runtime.
$sdkRecipeSha='bf8a8b9cd9089271d5cf9379c2fdcd05ad956a6c04613054faa45e02fdf88b6c'
$sdkPinsSha='0e1606b714321aaa65a7c665bb12787efa929619df612ae8939d8fd8fa52af5b'
& $sdkPython -B -X utf8 "$PSScriptRoot/hosted_worker.py" $Mode --recipe-sha $sdkRecipeSha --pins-sha $sdkPinsSha --approved-commit $ApprovedCommit
if ($LASTEXITCODE -ne 0) { throw 'SDK recipe failed; no retry' }
