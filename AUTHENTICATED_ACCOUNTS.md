# Authenticated Dev Setup

RuneLite's current Jagex Account development flow is:

1. Open `RuneLite (configure)`.
2. Add `--insecure-write-credentials` to `Client arguments`.
3. Save.
4. Launch RuneLite once through the Jagex Launcher.
5. RuneLite writes `%USERPROFILE%\.runelite\credentials.properties`.
6. Launch your local dev client and it will reuse those credentials.

This repo includes helper scripts for that workflow:

```powershell
.\scripts\Setup-AuthenticatedDev.ps1
.\scripts\Setup-AuthenticatedDev.ps1 -VerifyOnly
.\scripts\Run-AuthenticatedDevClient.ps1
```

## Notes

- Do not share `%USERPROFILE%\.runelite\credentials.properties`.
- Delete that file when you're done if you want RuneLite to return to normal launcher-only authentication.
- If you need to invalidate it, end your sessions from your Jagex account settings.
