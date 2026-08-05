<#
.SYNOPSIS
  Convert office/pdf docs in a folder to Markdown with MarkItDown, so the existing
  /import-wiki (or /documents) endpoint can ingest them. Text-only knowledge base.

.DESCRIPTION
  Walks -SourceDir for .pdf/.docx/.pptx/.xlsx and writes a sibling .md next to each
  (e.g. Design.pdf -> Design.md). Skips images/binaries. Your Spring importer then
  ingests the .md files and ignores the originals.

  Prereq (once):  pip install "markitdown[pdf,docx,pptx,xlsx]"
  Scanned/image-only PDFs have no text layer -> empty .md; add OCR separately if needed.

.PARAMETER SourceDir
  Folder to scan (recursively). Required.

.PARAMETER Extensions
  Which extensions to convert. Default: pdf, docx, pptx. Add xlsx if you have
  knowledge spreadsheets.

.PARAMETER Force
  Re-convert even if the .md already exists and is newer than the source.

.EXAMPLE
  ./scripts/convert-to-md.ps1 -SourceDir "D:/project-fpt/your-wiki.wiki"
  # then: POST /projects/1/import-wiki { "path": "D:/project-fpt/your-wiki.wiki" }
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceDir,

    [string[]]$Extensions = @('pdf', 'docx', 'pptx'),

    [switch]$Force
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $SourceDir -PathType Container)) {
    throw "SourceDir not found or not a directory: $SourceDir"
}
if (-not (Get-Command markitdown -ErrorAction SilentlyContinue)) {
    throw "markitdown not on PATH. Install: pip install `"markitdown[pdf,docx,pptx,xlsx]`""
}

# NOTE: -Include is silently ignored when combined with -LiteralPath, so filter on the
# real .Extension instead. This also keeps extensionless files (.order) and images out.
$wantExt = $Extensions | ForEach-Object { ".$($_.ToLower())" }
$files = Get-ChildItem -LiteralPath $SourceDir -Recurse -File |
    Where-Object { $wantExt -contains $_.Extension.ToLower() } |
    Where-Object {
        $_.FullName -notmatch '\.attachments' -and
        $_.FullName -notmatch '[\\/]\.git[\\/]' -and
        $_.FullName -notmatch '[\\/]\.images[\\/]'
    }

$total = @($files).Count
Write-Host "Found $total file(s) to convert under $SourceDir"

$done = 0; $converted = 0; $skipped = 0; $failed = 0
foreach ($f in $files) {
    $done++
    $out = [System.IO.Path]::ChangeExtension($f.FullName, '.md')

    if ((-not $Force) -and (Test-Path -LiteralPath $out) -and
        ((Get-Item -LiteralPath $out).LastWriteTimeUtc -ge $f.LastWriteTimeUtc)) {
        $skipped++
        Write-Host ("[{0}/{1}] skip (up to date): {2}" -f $done, $total, $f.Name)
        continue
    }

    Write-Host ("[{0}/{1}] convert: {2}" -f $done, $total, $f.Name)
    try {
        # -o writes the markdown output file directly.
        & markitdown $f.FullName -o $out
        if ($LASTEXITCODE -ne 0) { throw "markitdown exit $LASTEXITCODE" }
        # Drop empty/whitespace-only output (e.g. scanned PDF with no text layer) so the
        # importer never sees a blank page. Matches WikiImporter's blank-page skip.
        $text = (Get-Content -LiteralPath $out -Raw -ErrorAction SilentlyContinue)
        if ([string]::IsNullOrWhiteSpace($text)) {
            Remove-Item -LiteralPath $out -Force
            Write-Warning ("  empty output (no text layer?), removed: {0}" -f $f.Name)
            $skipped++
        }
        else {
            $converted++
        }
    }
    catch {
        $failed++
        Write-Warning ("  FAILED {0}: {1}" -f $f.Name, $_.Exception.Message)
    }
}

Write-Host ""
Write-Host ("Done. converted=$converted skipped=$skipped failed=$failed total=$total")
Write-Host "Next: POST /projects/{id}/import-wiki with { `"path`": `"$SourceDir`" }"
