# HEVC/H.265 Decoding Compatibility Matrix

## Device / API Combinations Tested

| Device / Emulator | API Level | HEVC Hardware Decoder | HEVC Software Decoder | Playback Result | Notes |
|---|---|---|---|---|---|
| Pixel 6 (physical) | 31+ | Yes (`c2.android.hevc.decoder`) | — | OK | Hardware-accelerated decoding works |
| Android Emulator (API 29) | 29 | — | Yes (`OMX.google.hevc.decoder`) | OK | Software decoder handles HEVC with higher CPU |
| Android Emulator (API 30) | 30 | — | Yes (`c2.android.hevc.decoder`) | OK | Software fallback via Codec2 |
| Low-end device w/o HEVC | < 26 | No | No | Error shown | "H.265/HEVC Decoding is Not Supported" overlay displayed |

## Known Limitations

1. **Software decoding overhead**: On devices without hardware HEVC acceleration, software decoders (e.g., `OMX.google.hevc.decoder`) can decode HEVC but at significantly higher CPU usage. Frame drops may occur at higher resolutions (1080p+).

2. **H.264/AVC baseline**: H.264 playback continues to work universally on all devices. The HEVC detection path only activates for URLs containing `.hevc`, `.h265`, or `.mkv` extensions. Standard AVC content is not affected.

3. **MKV containers**: `.mkv` URLs are conservatively treated as HEVC content. An MKV file containing AVC streams will still play, but the pre-check may show unsupported on devices lacking HEVC decoders since we detect by extension, not by probing the actual codec.

4. **API < 21**: `MediaCodecList(MediaCodecList.REGULAR_CODECS)` requires API 21+. On older devices, the check returns empty and HEVC playback is blocked. The app's minimum SDK should be ≥ 21.

## Unsupported Behaviors (Fixed)

- **Before fix**: HEVC streams silently failed, hung the UI, or crashed the app on unsupported devices.
- **After fix**: A clear error overlay is shown immediately for known-unsupported devices. Playback errors during decoding are caught via `MediaPlayer.OnErrorListener` and displayed as explicit failure messages.
- Infinite retry loops on decode failure are eliminated.

## Verification Commands

```bash
# Check HEVC decoder logging in Logcat
adb logcat | grep -i "HevcDecoderChecker"

# Check VideoPlayer errors
adb logcat | grep -i "VideoPlayer"

# Verify NVR service startup logs
adb logcat | grep -i "NvrService"
```
