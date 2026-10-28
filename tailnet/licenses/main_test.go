package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLinkedModulesSkipsStandardAndMainAndTakesReplacements(t *testing.T) {
	stream := `
{"ImportPath": "fmt", "Standard": true}
{"ImportPath": "github.com/juev/hermes/tailnet", "Module": {"Path": "github.com/juev/hermes/tailnet", "Main": true}}
{"ImportPath": "golang.org/x/net/http2", "Module": {"Path": "golang.org/x/net", "Version": "v0.61.0", "Dir": "/m/net"}}
{"ImportPath": "golang.org/x/net/idna", "Module": {"Path": "golang.org/x/net", "Version": "v0.61.0", "Dir": "/m/net"}}
{"ImportPath": "example.com/a", "Module": {"Path": "example.com/a", "Version": "v1.0.0", "Dir": "/m/a", "Replace": {"Path": "example.com/fork", "Version": "v1.0.1", "Dir": "/m/fork"}}}
`
	modules, err := linkedModules(strings.NewReader(stream))
	if err != nil {
		t.Fatal(err)
	}
	want := []module{
		{Path: "example.com/a", Version: "v1.0.1", Dir: "/m/fork"},
		{Path: "golang.org/x/net", Version: "v0.61.0", Dir: "/m/net"},
	}
	if len(modules) != len(want) {
		t.Fatalf("got %d modules, want %d: %+v", len(modules), len(want), modules)
	}
	for i := range want {
		if modules[i].Path != want[i].Path || modules[i].Version != want[i].Version || modules[i].Dir != want[i].Dir {
			t.Errorf("module %d = %+v, want %+v", i, modules[i], want[i])
		}
	}
}

func TestLicenseOfPrefersPlainLicenseAndFailsWithoutOne(t *testing.T) {
	dir := t.TempDir()
	for name, text := range map[string]string{"LICENSE.md": "md", "LICENSE": "plain", "license_test.go": "code"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if text, err := licenseOf(dir); err != nil || text != "plain" {
		t.Errorf("licenseOf = %q, %v; want the LICENSE file", text, err)
	}
	if _, err := licenseOf(t.TempDir()); err == nil {
		t.Error("a module without a license file must be an error")
	}
}
