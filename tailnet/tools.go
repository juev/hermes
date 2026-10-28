//go:build tools

// Pins the gomobile bind runtime that scripts/build-tailnet.sh needs in the module.
package tailnet

import _ "golang.org/x/mobile/bind"
