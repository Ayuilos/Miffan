//go:build !windows

package main

import "os"

// restrictToCurrentUser makes path readable by the current user only (files 0600, directories 0700).
func restrictToCurrentUser(path string, dir bool) error {
	mode := os.FileMode(0o600)
	if dir {
		mode = 0o700
	}
	return os.Chmod(path, mode)
}
