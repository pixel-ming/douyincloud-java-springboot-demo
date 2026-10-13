$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$directory = Join-Path ([System.IO.Path]::GetTempPath()) ('leaderboard-import-selfcheck-' + [guid]::NewGuid().ToString('N'))
$utf8 = New-Object System.Text.UTF8Encoding($false)
$oldToken = $env:LOCAL_LEADERBOARD_TOKEN
$mockProgress = @{ next = 1; pages = 0; finished = 0; begun = 0 }
$games = @('BJGYB2', (-join @([char]0x6398,[char]0x5730,[char]0x6C42,[char]0x5347)))
function Invoke-WebRequest([switch]$UseBasicParsing, $Method, $Uri) {
    if ($Method -ne 'Get' -or $Uri -ne 'http://127.0.0.1:18000/internal/leaderboard/rehearsal') { throw 'Unexpected metadata request.' }
    $metadata = @{ mode = 'LOCAL_REHEARSAL'; environment = 'local-rehearsal'; games = $games; cutoverAt = 1792423200000L; dayStart = 1792416000000L; cycleStart = 1792423200000L }
    $bytes = $utf8.GetBytes(($metadata | ConvertTo-Json -Depth 3 -Compress))
    return @{ RawContentStream = [System.IO.MemoryStream]::new($bytes); Headers = @{ 'Content-Type' = 'application/json' } }
}
function Invoke-RestMethod($Method, $Uri, $Headers, $ContentType, $Body) {
    if (-not $Uri.StartsWith('http://127.0.0.1:18000/')) { throw 'Non-local request.' }
    if ($Method -ne 'Post') { throw 'Unexpected import method.' }
    if ($Uri.EndsWith('/migrations')) { $plan = $utf8.GetString($Body) | ConvertFrom-Json; if (@(Compare-Object $games @($plan.games) -CaseSensitive).Count -ne 0) { throw 'Chinese games changed in request.' }; $mockProgress.begun++ }
    if ($Uri.EndsWith('/pages')) { $page = $utf8.GetString($Body) | ConvertFrom-Json; if ($page.page -ne $mockProgress.next) { throw 'Wrong page.' }; $mockProgress.next++; $mockProgress.pages++ }
    if ($Uri.EndsWith('/finish')) { $mockProgress.finished++ }
    return @{ batch_id = 'mock'; status = 'IMPORTED'; next_page = $mockProgress.next; imported_rows = 2 }
}
try {
    [void][System.IO.Directory]::CreateDirectory($directory)
    $env:LOCAL_LEADERBOARD_TOKEN = 'only-local-rehearsal-token-32-characters'
    $summary = @{ sourceSha256 = ('a' * 64); expectedDigest = ('b' * 64); expectedRows = 2; pages = 2; games = $games }
    [System.IO.File]::WriteAllText((Join-Path $directory 'summary.json'), ($summary | ConvertTo-Json), $utf8)
    $pages = @(@{page=1;rows=@(@{UserId='mock1'})}, @{page=2;rows=@(@{UserId='mock2'})}) | ForEach-Object { $_ | ConvertTo-Json -Depth 4 -Compress }
    [System.IO.File]::WriteAllLines((Join-Path $directory 'pages.jsonl'), [string[]]$pages, $utf8)
    & (Join-Path $root 'tools/Import-LeaderboardRehearsal.ps1') -SnapshotDirectory $directory
    & (Join-Path $root 'tools/Import-LeaderboardRehearsal.ps1') -SnapshotDirectory $directory
    if ($mockProgress.pages -ne 2 -or $mockProgress.finished -ne 2) { throw 'Repeated invocation resent imported pages.' }
    [System.IO.File]::WriteAllLines((Join-Path $directory 'pages.jsonl'), [string[]]@($pages[1]), $utf8)
    $rejected = $false
    try { & (Join-Path $root 'tools/Import-LeaderboardRehearsal.ps1') -SnapshotDirectory $directory } catch { $rejected = $true }
    if (-not $rejected -or $mockProgress.finished -ne 2) { throw 'Incomplete input was activated.' }
    $before = $mockProgress.begun
    $summary.games = @('DIFFERENT_GAME')
    [System.IO.File]::WriteAllText((Join-Path $directory 'summary.json'), ($summary | ConvertTo-Json), $utf8)
    $rejected = $false
    try { & (Join-Path $root 'tools/Import-LeaderboardRehearsal.ps1') -SnapshotDirectory $directory } catch { $rejected = $_.Exception.Message -eq 'Game lists differ.' }
    if (-not $rejected -or $mockProgress.begun -ne $before) { throw 'Different games reached the import endpoint.' }
    Write-Host 'Import self-check passed: UTF-8 Chinese metadata, resume, incomplete pages and mismatched games; HTTP calls were mocked.'
} finally {
    $env:LOCAL_LEADERBOARD_TOKEN = $oldToken
    foreach ($name in @('summary.json','pages.jsonl')) { $file = Join-Path $directory $name; if ([System.IO.File]::Exists($file)) { [System.IO.File]::Delete($file) } }
    if ([System.IO.Directory]::Exists($directory)) { [System.IO.Directory]::Delete($directory) }
}
