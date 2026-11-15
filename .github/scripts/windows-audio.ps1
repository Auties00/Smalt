# Installs VB-Audio Virtual Cable (a render endpoint "CABLE Input" looped back to a capture endpoint "CABLE Output")
# on a Windows runner, which has no audio endpoint of its own, and starts the audio services. The driver pack and
# Microsoft's devcon tool are downloaded and pinned by SHA-256 (VB-CABLE is donationware: it is downloaded at CI
# time, never committed). The method is the one of LABSN/sound-ci-helpers and Mozilla cubeb's CI.
$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$driverUrl = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack43.zip"
$driverSha256 = "66fd0a4d9f4896ff41632b7e3d53892c085c4561f53e8ae8d0f0bc10eedd1cdd"
$devconUrl = "https://github.com/LABSN/sound-ci-helpers/raw/20a2b9bbd21fd005d8fe89933901506dba84ea4e/windows/devcon.exe"
$devconSha256 = "97cff42f8c0fe4fbdf991273159516bf78090625a933c3983ebd6f62284e329a"

$work = Join-Path $env:RUNNER_TEMP "vbcable"
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Get-Pinned([string] $Url, [string] $Sha256, [string] $Path) {
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            Invoke-WebRequest -Uri $Url -OutFile $Path -UseBasicParsing
            break
        } catch {
            if ($attempt -eq 4) { throw }
            Start-Sleep -Seconds (10 * $attempt)
        }
    }
    $actual = (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
    if ($actual -ne $Sha256) {
        throw "$Url has SHA-256 $actual, expected $Sha256"
    }
}

$zip = Join-Path $work "VBCABLE_Driver_Pack43.zip"
$devcon = Join-Path $work "devcon.exe"
Get-Pinned $driverUrl $driverSha256 $zip
Get-Pinned $devconUrl $devconSha256 $devcon

$driver = Join-Path $work "driver"
Expand-Archive -Force -LiteralPath $zip -DestinationPath $driver

# Trust the driver's signer so that devcon installs it without a prompt.
$catalog = Join-Path $driver "vbaudio_cable64_win7.cat"
$signature = Get-AuthenticodeSignature -FilePath $catalog
if ($null -eq $signature.SignerCertificate) {
    throw "no signer certificate on $catalog"
}
$certificate = Join-Path $work "vbcable.cer"
[System.IO.File]::WriteAllBytes($certificate,
    $signature.SignerCertificate.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
& certutil.exe -addstore -f "TrustedPublisher" $certificate
if ($LASTEXITCODE -ne 0) { throw "certutil failed with $LASTEXITCODE" }

& $devcon install (Join-Path $driver "vbMmeCable64_win7.inf") "VBAudioVACWDM"
if ($LASTEXITCODE -ne 0) { throw "devcon failed with $LASTEXITCODE" }

foreach ($service in "AudioEndpointBuilder", "Audiosrv") {
    Set-Service -Name $service -StartupType Automatic
    Start-Service -Name $service
}
Start-Sleep -Seconds 5
Get-CimInstance Win32_SoundDevice | Format-Table -AutoSize Name, Status
