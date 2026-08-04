# Ensure USB SSH tunnels to the iOS test bench are up (idempotent).
# Uses pymobiledevice3 (pure-Python usbmux) — no native iproxy needed.
# Run: pwsh F:\claude\specter\ios\dev-scripts\ios-tunnel.ps1
# After it prints "up", connect by name: ssh iphone8 | ssh se2 | ssh se3
#
# Device map (RootHide Dopamine, rootless /var/jb, iOS 16.3.1 unless noted):
#   iphone8  iPhone10,2  local :2224 -> dev 22 (OpenSSH)
#   se2      iPhone12,8  local :2222 -> dev 22 (OpenSSH)
#   se3      iPhone14,6  local :2223 -> dev 44 (Dropbear), iOS 16.2

$devices = @(
  @{ name = 'iphone8'; udid = '308e6361884208deb815e12efc230a028ddc4b1a'; lport = 2224; dport = 22 },
  @{ name = 'se2';     udid = '00008030-001229C01146402E';                 lport = 2222; dport = 22 },
  @{ name = 'se3';     udid = '00008110-000655D91E28401E';                 lport = 2223; dport = 44 }
)

foreach ($d in $devices) {
  $listening = Get-NetTCPConnection -State Listen -LocalPort $d.lport -ErrorAction SilentlyContinue
  if ($listening) {
    Write-Host "[$($d.name)] already up on 127.0.0.1:$($d.lport)"
    continue
  }
  # Start-Process detaches so the forwarder survives this script exiting.
  Start-Process -WindowStyle Hidden -FilePath "pymobiledevice3" `
    -ArgumentList @("usbmux", "forward", "$($d.lport)", "$($d.dport)", "--serial", $d.udid)
  Write-Host "[$($d.name)] starting tunnel 127.0.0.1:$($d.lport) -> device:$($d.dport)"
}
Write-Host ""
Write-Host "Done. Connect: ssh iphone8   (or se2 / se3)"
