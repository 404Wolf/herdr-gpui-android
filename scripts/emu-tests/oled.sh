#!/usr/bin/env bash
# The right panel and the rail under Wolf's OLED palette (provisioned, as the phone's config sets it):
# panel.sh's steps and shots with EMU_PALETTE set to the preview's html.phone tokens.
#   lease-run --kind android --outputs <dir> -- scripts/emu-test.sh scripts/emu-tests/oled.sh
export EMU_PALETTE='{"background":"000000","foreground":"e6e6e6","chrome":"000000","border":"222222","muted":"a3a3a3","accent":"7c9cff","roles":{"panel":"0b0b0b","raised":"161616","hover":"121212","selected":"17202e","danger":"ff5f5f","working":"f2c14e","success":"6fbf73","info":"7c9cff"}}'
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec bash "$here/panel.sh"
