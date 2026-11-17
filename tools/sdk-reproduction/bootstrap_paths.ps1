# Own path-only function. Loading this file starts no tool or external process.
function Assert-PlainFile([string]$value) {
  $item=Get-Item -LiteralPath $value -Force
  while ($null -ne $item) {
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'tool ancestor is a reparse point' }
    # .NET treats this as a literal pathname; no PowerShell wildcard parameter set.
    $parentPath=[IO.Path]::GetDirectoryName($item.FullName)
    if ([string]::IsNullOrEmpty($parentPath) -or $parentPath -eq $item.FullName) { break }
    $item=Get-Item -LiteralPath $parentPath -Force
  }
}
