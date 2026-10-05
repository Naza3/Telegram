#!/usr/bin/env bash
set -euo pipefail

# Package the approved foreground artwork into Android density/mask variants.
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
master="$repo_dir/docs/branding/shiye/foreground-master.png"
res="$repo_dir/TMessagesProj/src/main/res"
standalone="$repo_dir/TMessagesProj_AppStandalone/src/main/res"
command -v magick > /dev/null
test -s "$master"

make_legacy() {
    local size="$1" shape="$2" output="$3"
    local edge=$((size - 1)) radius=$((size * 2 / 9)) artwork=$((size * 13 / 12))
    local mask="roundrectangle 0,0 $edge,$edge $radius,$radius"
    if [[ "$shape" == round ]]; then
        mask="circle $((size / 2)),$((size / 2)) $((size / 2)),0"
    fi
    magick -size "${size}x${size}" xc:none -fill '#405A47' -draw "$mask" \
        \( "$master" -resize "${artwork}x${artwork}" -gravity center -background none -extent "${size}x${size}" \) \
        -gravity center -compose over -composite -strip "PNG32:$output"
}

while read -r density icon foreground artwork; do
    dir="$res/mipmap-$density"
    make_legacy "$icon" rounded "$dir/ic_launcher.png"
    make_legacy "$icon" round "$dir/ic_launcher_round.png"
    # 78 dp artwork canvas within 108 dp adaptive canvas leaves safe mask padding.
    magick "$master" -resize "${artwork}x${artwork}" -gravity center \
        -background none -extent "${foreground}x${foreground}" -strip "PNG32:$dir/icon_foreground.png"
    cp "$dir/icon_foreground.png" "$dir/icon_foreground_round.png"
    cp "$dir/icon_foreground.png" "$dir/icon_foreground_sa.png"
    cp "$dir/ic_launcher.png" "$standalone/mipmap-$density/ic_launcher_sa.png"
done <<'DENSITIES'
mdpi 48 108 78
hdpi 72 162 117
xhdpi 96 216 156
xxhdpi 144 324 234
xxxhdpi 192 432 312
DENSITIES

make_legacy 512 rounded "$repo_dir/docs/branding/shiye/icon-preview.png"
printf 'Generated Shiye launcher icons for five Android densities.\n'
