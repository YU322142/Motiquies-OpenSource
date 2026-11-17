# Own pure functional path test. Only extract/define the reviewed path function.
param([Parameter(Mandatory=$true)][string]$SourcePath,
      [Parameter(Mandatory=$true)][string]$FixtureRoot)
$ErrorActionPreference='Stop'
$tokens=$null
$errors=$null
$tree=[System.Management.Automation.Language.Parser]::ParseFile($SourcePath,[ref]$tokens,[ref]$errors)
if ($errors.Count -ne 0) { throw 'path-function source parse failure' }
$functions=@($tree.FindAll({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-PlainFile'},$true))
if ($functions.Count -ne 1) { throw 'exactly one own path function required' }
. ([ScriptBlock]::Create($functions[0].Extent.Text))
if (Test-Path -LiteralPath $FixtureRoot) { throw 'new own fixture directory required' }
[IO.Directory]::CreateDirectory($FixtureRoot) | Out-Null
$ownTarget=Join-Path $FixtureRoot 'target'
[IO.Directory]::CreateDirectory($ownTarget) | Out-Null
$ownFile=Join-Path $ownTarget 'own.txt'
[IO.File]::WriteAllText($ownFile,'own fixture')
Assert-PlainFile $ownFile
$ownJunction=Join-Path $FixtureRoot 'junction'
New-Item -ItemType Junction -Path $ownJunction -Value $ownTarget | Out-Null
$rejected=$false
try { Assert-PlainFile (Join-Path $ownJunction 'own.txt') } catch { $rejected=$true }
if (-not $rejected) { throw 'junction ancestor was accepted' }
$moved=Join-Path $FixtureRoot 'moved-target'
if (-not [IO.Path]::GetFullPath($ownTarget).StartsWith([IO.Path]::GetFullPath($FixtureRoot)+[IO.Path]::DirectorySeparatorChar) -or -not [IO.Path]::GetFullPath($moved).StartsWith([IO.Path]::GetFullPath($FixtureRoot)+[IO.Path]::DirectorySeparatorChar)) { throw 'fixture move outside own root' }
[IO.Directory]::Move($ownTarget,$moved)
$rejected=$false
try { Assert-PlainFile (Join-Path $ownJunction 'missing.txt') } catch { $rejected=$true }
if (-not $rejected) { throw 'dangling junction ancestor was accepted' }
'{"schema":1,"ordinaryPathAccepted":true,"junctionAncestorRejected":true,"danglingJunctionRejected":true,"pythonOrVendorExecuted":false}'
