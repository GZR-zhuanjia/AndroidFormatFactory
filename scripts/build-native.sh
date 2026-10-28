#!/usr/bin/env bash
# Rebuilds LGPL-only shared FFmpeg and LAME for Android. No system FFmpeg is used.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$SDK" && -f "$ROOT/local.properties" ]]; then
  SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
fi
NDK=${ANDROID_NDK_HOME:-"$SDK/ndk/28.2.13676358"}
ABIS=arm64-v8a,x86_64
JOBS=2
while (($#)); do
  case "$1" in
    --abis) ABIS=$2; shift 2 ;;
    --jobs) JOBS=$2; shift 2 ;;
    *) echo "Usage: $0 [--abis arm64-v8a,x86_64] [--jobs 2]" >&2; exit 2 ;;
  esac
done
[[ -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]] || { echo "Install Android NDK 28.2.13676358 with sdkmanager or set ANDROID_NDK_HOME" >&2; exit 1; }
BASE="$ROOT/native-deps"
mkdir -p "$BASE/downloads" "$BASE/src" "$BASE/build"
download() {
  local name=$1 url=$2 checksum=$3
  if [[ ! -f "$BASE/downloads/$name" ]]; then curl --fail --location --retry 3 "$url" -o "$BASE/downloads/$name.part"; mv "$BASE/downloads/$name.part" "$BASE/downloads/$name"; fi
  printf '%s  %s\n' "$checksum" "$BASE/downloads/$name" | sha256sum --check --status || { echo "SHA-256 mismatch: $name" >&2; exit 1; }
}
# Archive hashes are deliberately pinned; never execute an unchecked download.
download ffmpeg-n7.1.5.tar.gz https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n7.1.5.tar.gz e3963a50831c985933e1a625ed566ec4c7adb5c012c34fa9f84438e1d61bdacc
download lame-3.100.tar.gz https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
[[ -d "$BASE/src/FFmpeg-n7.1.5" ]] || tar -xzf "$BASE/downloads/ffmpeg-n7.1.5.tar.gz" -C "$BASE/src"
[[ -d "$BASE/src/lame-3.100" ]] || tar -xzf "$BASE/downloads/lame-3.100.tar.gz" -C "$BASE/src"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export PATH="$TOOLCHAIN:$PATH"
IFS=, read -ra TARGET_ABIS <<< "$ABIS"
for abi in "${TARGET_ABIS[@]}"; do
  case "$abi" in
    arm64-v8a) arch=aarch64; triple=aarch64-linux-android; host=aarch64-linux-android ;;
    x86_64) arch=x86_64; triple=x86_64-linux-android; host=x86_64-linux-android ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
  esac
  prefix="$BASE/$abi"
  cc="$TOOLCHAIN/${triple}26-clang"
  cxx="$TOOLCHAIN/${triple}26-clang++"
  mkdir -p "$BASE/build/lame-$abi" "$BASE/build/ffmpeg-$abi" "$prefix"
  (
    cd "$BASE/build/lame-$abi"
    CC="$cc" CXX="$cxx" AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm STRIP=llvm-strip \
      CFLAGS='-O2 -fPIC' LDFLAGS='-Wl,-z,max-page-size=16384' \
      "$BASE/src/lame-3.100/configure" --host="$host" --prefix="$prefix" \
      --enable-shared --disable-static --disable-frontend --disable-decoder --disable-analyzer-hooks --disable-gtktest
    # LAME's Unix libtool defaults add .so.0, which Android cannot package by SONAME.
    # Its export list also includes the intentionally disabled decoder; lld rejects
    # undefined exports, so use an encoder-only list, retaining symbol visibility.
    sed '/^hip_/d; /^lame_decode/d; /^lame_init_old$/d' "$BASE/src/lame-3.100/include/libmp3lame.sym" > libmp3lame.sym
    sed -i 's/-version-info 0:0/-avoid-version/; s|$(top_srcdir)/include/libmp3lame.sym|$(top_builddir)/libmp3lame.sym|' libmp3lame/Makefile
    make -j"$JOBS"
    make install
  )
  (
    cd "$BASE/build/ffmpeg-$abi"
    "$BASE/src/FFmpeg-n7.1.5/configure" \
      --prefix="$prefix" --target-os=android --arch="$arch" --enable-cross-compile \
      --cc="$cc" --cxx="$cxx" --ar=llvm-ar --ranlib=llvm-ranlib --nm=llvm-nm --strip=llvm-strip \
      --enable-shared --disable-static --enable-pic --disable-symver --disable-x86asm \
      --disable-gpl --disable-nonfree --disable-version3 --enable-libmp3lame \
      --disable-programs --disable-doc --disable-debug --disable-network --disable-autodetect \
      --disable-avdevice --disable-avfilter --disable-postproc --disable-swscale \
      --disable-everything --enable-avformat --enable-avcodec --enable-avutil --enable-swresample \
      --enable-protocol=file,pipe \
      --enable-demuxer=aac,ac3,aiff,amr,ape,asf,avi,caf,dts,eac3,flac,matroska,mov,mp3,mpeg,mpegts,ogg,wav,wv \
      --enable-decoder=aac,aac_fixed,ac3,ac3_fixed,alac,amrnb,amrwb,ape,atrac3,atrac3p,cook,dca,eac3,flac,mp1,mp1float,mp2,mp2float,mp3,mp3float,opus,pcm_alaw,pcm_f32be,pcm_f32le,pcm_f64be,pcm_f64le,pcm_mulaw,pcm_s16be,pcm_s16le,pcm_s24be,pcm_s24le,pcm_s32be,pcm_s32le,pcm_u8,vorbis,wavpack,wmav1,wmav2,wmapro,wmalossless \
      --enable-parser=aac,aac_latm,ac3,dca,flac,mpegaudio,opus,vorbis \
      --enable-encoder=libmp3lame,flac,aac,pcm_s16le --enable-muxer=mp3,flac,ipod,wav \
      --extra-cflags="-I$prefix/include -O2" --extra-ldflags="-L$prefix/lib -Wl,-z,max-page-size=16384" --extra-libs=-lm
    make -j"$JOBS"
    make install
  )
  mkdir -p "$prefix/licenses"
  cp "$BASE/src/FFmpeg-n7.1.5/COPYING.LGPLv2.1" "$prefix/licenses/FFmpeg-LGPL-2.1.txt"
  cp "$BASE/src/lame-3.100/COPYING" "$prefix/licenses/LAME-LGPL-2.0.txt"
  cp "$BASE/build/ffmpeg-$abi/config.h" "$BASE/build/ffmpeg-$abi/config_components.h" "$BASE/build/ffmpeg-$abi/ffbuild/config.mak" "$prefix/licenses/"
  for feature in GPL NONFREE VERSION3 NETWORK; do
    grep -qx "#define CONFIG_$feature 0" "$prefix/licenses/config.h" || { echo "Forbidden FFmpeg configuration: $feature" >&2; exit 1; }
  done
  protocols=$(awk '$1 == "#define" && $2 ~ /_PROTOCOL$/ && $3 == 1 { print $2 }' "$prefix/licenses/config_components.h" | sort)
  [[ "$protocols" == $'CONFIG_FILE_PROTOCOL\nCONFIG_PIPE_PROTOCOL' ]] || { echo "Unexpected FFmpeg protocols: $protocols" >&2; exit 1; }
  for lib in avformat avcodec avutil swresample mp3lame; do
    dynamic=$("$TOOLCHAIN/llvm-readelf" -d "$prefix/lib/lib$lib.so")
    grep -Fq "Library soname: [lib$lib.so]" <<< "$dynamic" || { echo "Unexpected SONAME for $lib" >&2; exit 1; }
    if grep -Eq 'Shared library: \[lib(avformat|avcodec|avutil|swresample|mp3lame)\.so\.' <<< "$dynamic"; then
      echo "Versioned Android dependency in $lib" >&2; exit 1
    fi
    "$TOOLCHAIN/llvm-readelf" -lW "$prefix/lib/lib$lib.so" | awk '$1 == "LOAD" && $NF != "0x4000" { exit 1 }' || { echo "Invalid 16 KiB page alignment in $lib" >&2; exit 1; }
    grep -E 'SONAME|NEEDED' <<< "$dynamic"
  done
done
echo "Native dependencies ready: $ABIS"
