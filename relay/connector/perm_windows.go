//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
)

// restrictToCurrentUser removes inherited permissions and grants full control to the current user only,
// by SID so account names and locales do not matter. SYSTEM is kept: it cannot be locked out and the
// file is no more exposed to it than to the user.
func restrictToCurrentUser(path string, dir bool) error {
	token, err := syscall.OpenCurrentProcessToken()
	if err != nil {
		return err
	}
	defer token.Close()
	user, err := token.GetTokenUser()
	if err != nil {
		return err
	}
	sid, err := user.User.Sid.String()
	if err != nil {
		return err
	}
	grant := "*" + sid + ":(F)"
	if dir {
		grant = "*" + sid + ":(OI)(CI)(F)"
	}
	icacls := filepath.Join(os.Getenv("SystemRoot"), "System32", "icacls.exe")
	cmd := exec.Command(icacls, path, "/inheritance:r", "/grant:r", grant, "/grant:r", "*S-1-5-18:(F)")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("icacls failed: %v: %.200s", err, out)
	}
	return nil
}
