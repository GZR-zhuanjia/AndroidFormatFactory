# Android audio dependencies

Run `./scripts/build-native.sh --abis arm64-v8a,x86_64 --jobs 2` from the repository.
Install NDK `28.2.13676358` and CMake `3.22.1` with Android's SDK manager first.
The script finds the SDK through `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or
`local.properties`; `ANDROID_NDK_HOME` can override the NDK path. The host needs
Linux x86-64, Bash, curl, SHA-256 utilities, tar, make, sed, and a C build toolchain.

The exact upstream source archives are downloaded and SHA-256 checked before
extraction. FFmpeg is pinned to official FFmpeg GitHub tag **n7.1.5**; LAME is
pinned to **3.100**. An existing archive with a mismatching checksum fails the
build. For a clean rebuild, remove `native-deps/build`, `native-deps/src`, and the
ABI install directories. Download archives can be retained after verification.

All output goes to ignored `native-deps/`: archives, source trees, build trees,
headers and shared libraries. The CMake project imports `avformat`, `avcodec`,
`avutil`, `swresample`, and `mp3lame` shared libraries directly from each ABI's
`lib` directory. Do not also copy them into `jniLibs`. Missing dependencies fail
CMake with the rebuild command. The output is linked with a 16 KiB maximum page
size and unversioned Android SONAMEs. LAME's generated libtool Makefile is adjusted
to omit Unix `.so.0` versions and decoder exports; upstream source is unchanged.

FFmpeg is configured with GPL, nonfree and version-3 licensing disabled, with
autodetection disabled. Only audio decoders and the selected local containers are
enabled. No video decoder/encoder, executable, device library or filter library is
built. The runtime has no FFmpeg network support, and only `file` and `pipe`
protocols are compiled; JNI accepts absolute local paths and applies an explicit
input protocol whitelist. FFmpeg remains LGPL 2.1-or-later and dynamically links
LAME (LGPL 2.0-or-later). Licenses plus effective FFmpeg configuration are copied
into each ABI's `licenses` directory for release compliance review. The exact
source archives and this build script must remain available to recipients when
distributing binaries, together with LGPL notices and relinking information.

JNI is synchronous on its caller's worker thread. Each operation uses a session
with an atomic interrupt flag. Cancellation interrupts demuxing and is checked
between decoding, resampling, encoding and muxing operations. Session release
removes the registry entry while active work safely retains its own reference.
Progress is in `[0, 1]`, or `-1` for unknown duration. Callback exceptions propagate
to Java only after native cleanup. Probe returns numeric/boolean JSON fields and
does not open a video decoder.

Output paths must not exist. Atomic exclusive creation prevents accidental
overwrite. Failure, cancellation, callback exceptions and storage errors remove
the incomplete output. Successful completion flushes, closes and finalizes the
container before reporting `1`. Every output has an 8 GiB limit; WAV uses a
smaller RIFF-safe limit below 4 GiB. Storage exhaustion is reported with `ENOSPC`;
oversized output uses `OUTPUT_TOO_LARGE` and cancellation uses `CANCELLED`.

MP3 uses LAME; M4A uses FFmpeg AAC; FLAC uses FFmpeg FLAC; WAV uses signed 16-bit
PCM. MP3 and M4A use at most two output channels. A zero sample rate/channels
request preserves the input configuration where the selected encoder permits it;
otherwise the nearest supported sample rate is chosen. FLAC uses signed 16-bit
for inputs of at most 16 bits and signed 24-bit for higher precision inputs.
Floating point and 32-bit input therefore lose precision when encoded to FLAC.
Resampling, channel conversion and WAV's 16-bit quantization also change samples.
