param([Parameter(Mandatory=$true)][string]$SnapshotDirectory)
$ErrorActionPreference = 'Stop'
$token = $env:LOCAL_LEADERBOARD_TOKEN
if ([string]::IsNullOrEmpty($token) -or $token.Length -lt 32) { throw 'Set LOCAL_LEADERBOARD_TOKEN to the same 32+ character token as the rehearsal application.' }
$base = 'http://127.0.0.1:18000'
$headers = @{ Authorization = 'Bearer ' + $token }
$utf8 = New-Object System.Text.UTF8Encoding($false, $true)
$directory = (Resolve-Path -LiteralPath $SnapshotDirectory).Path
$summary = [System.IO.File]::ReadAllText((Join-Path $directory 'summary.json'), $utf8) | ConvertFrom-Json
# Windows PowerShell 5.1 can decode JSON without a charset as Latin-1.
$response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri ($base + '/internal/leaderboard/rehearsal')
try { $metadata = $utf8.GetString($response.RawContentStream.ToArray()) | ConvertFrom-Json }
finally { $response.RawContentStream.Dispose() }
if ($metadata.mode -cne 'LOCAL_REHEARSAL' -or $metadata.environment -cne 'local-rehearsal') { throw 'Target is not the isolated rehearsal application.' }
if ($summary.sourceSha256 -cnotmatch '^[a-f0-9]{64}$' -or $summary.expectedDigest -cnotmatch '^[a-f0-9]{64}$' -or $summary.expectedRows -lt 1 -or $summary.pages -lt 1) { throw 'Invalid conversion summary.' }
if (@(Compare-Object @($summary.games) @($metadata.games) -CaseSensitive).Count -ne 0) { throw 'Game lists differ.' }
$batch = 'local-' + $summary.sourceSha256.Substring(0, 32)
$plan = [ordered]@{
    batchId = $batch; sourceId = $summary.sourceSha256; mode = 'SCOPED'
    games = $summary.games; includesAllGames = $true; legacyFrozen = $true
    expectedRows = $summary.expectedRows; expectedDigest = $summary.expectedDigest
    cutoverAt = $metadata.cutoverAt; dayStart = $metadata.dayStart; cycleStart = $metadata.cycleStart
    evidence = 'LOCAL REHEARSAL ONLY: immutable exported copy, simulated period; not a production freeze or cutover.'
}
function Post-Json([string]$Path, [string]$Json) {
    Invoke-RestMethod -Method Post -Uri ($base + $Path) -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes($Json)
}
$state = Post-Json '/internal/leaderboard/migrations' ($plan | ConvertTo-Json -Depth 5 -Compress)
$nextPage = [int]$state.next_page
$pages = 0
$rows = 0L
$reader = New-Object System.IO.StreamReader((Join-Path $directory 'pages.jsonl'), $utf8)
try { while ($null -ne ($line = $reader.ReadLine())) {
    $page = $line | ConvertFrom-Json
    $pages++
    if ([int]$page.page -ne $pages -or @($page.rows).Count -lt 1 -or @($page.rows).Count -gt 200) { throw 'Invalid page sequence or size; staging batch was not activated.' }
    $rows += @($page.rows).Count
    if ($pages -ge $nextPage) {
        $state = Post-Json ('/internal/leaderboard/migrations/' + $batch + '/pages') $line
        if ([int]$state.next_page -ne $pages + 1) { throw 'Unexpected import progress.' }
        if ($pages % 100 -eq 0) { Write-Host ('Imported pages: ' + $pages) }
    }
} } finally { $reader.Dispose() }
if ($pages -ne $summary.pages -or $rows -ne $summary.expectedRows) { throw 'Converted file counts differ; staging batch was not activated.' }
$state = Post-Json ('/internal/leaderboard/migrations/' + $batch + '/finish') '{}'
Write-Host ('Rehearsal batch: ' + $state.batch_id + '; status: ' + $state.status + '; rows: ' + $state.imported_rows)
