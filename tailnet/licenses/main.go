// Command licenses writes the license of every Go module linked into the tailnet node, the Go
// standard library first, as JSON for the Licenses screen of the app. It lists the packages the
// way gomobile builds them, so GOOS, GOARCH and CGO_ENABLED come from the caller.
//
// Usage: go run ./licenses <output.json> <package>...
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
)

// Component is one entry of the output: a module, its version and the text of its license.
type Component struct {
	Name    string `json:"name"`
	Version string `json:"version"`
	License string `json:"license"`
}

type module struct {
	Path    string
	Version string
	Dir     string
	Main    bool
	Replace *module
}

type pkg struct {
	Standard bool
	Module   *module
}

// licenseNames are the usual names of a license file, the most usual first.
var licenseNames = []string{"LICENSE", "LICENSE.md", "LICENSE.txt", "LICENCE", "COPYING", "COPYING.md", "COPYING.txt"}

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: licenses <output.json> <package>...")
		os.Exit(2)
	}
	if err := run(os.Args[1], os.Args[2:]); err != nil {
		fmt.Fprintln(os.Stderr, "licenses:", err)
		os.Exit(1)
	}
}

func run(output string, packages []string) error {
	listed, err := goCommand(append([]string{"list", "-deps", "-json"}, packages...)...)
	if err != nil {
		return err
	}
	modules, err := linkedModules(bytes.NewReader(listed))
	if err != nil {
		return err
	}
	goroot, err := goCommand("env", "GOROOT")
	if err != nil {
		return err
	}
	goVersion, err := goCommand("env", "GOVERSION")
	if err != nil {
		return err
	}
	std, err := os.ReadFile(filepath.Join(strings.TrimSpace(string(goroot)), "LICENSE"))
	if err != nil {
		return err
	}
	components := []Component{{"Go standard library", strings.TrimSpace(string(goVersion)), string(std)}}
	for _, m := range modules {
		text, err := licenseOf(m.Dir)
		if err != nil {
			return fmt.Errorf("%s@%s: %w", m.Path, m.Version, err)
		}
		components = append(components, Component{m.Path, m.Version, text})
	}
	out, err := json.MarshalIndent(components, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(output, append(out, '\n'), 0o644)
}

// linkedModules reads the stream of `go list -deps -json` and returns the modules other than the
// main one that its packages come from, a replacement in place of the module it replaces.
func linkedModules(r io.Reader) ([]module, error) {
	seen := map[string]module{}
	decoder := json.NewDecoder(r)
	for {
		var p pkg
		if err := decoder.Decode(&p); errors.Is(err, io.EOF) {
			break
		} else if err != nil {
			return nil, err
		}
		if p.Standard || p.Module == nil || p.Module.Main {
			continue
		}
		m := *p.Module
		if m.Replace != nil {
			m = module{Path: m.Path, Version: m.Replace.Version, Dir: m.Replace.Dir}
		}
		seen[m.Path] = m
	}
	modules := make([]module, 0, len(seen))
	for _, m := range seen {
		modules = append(modules, m)
	}
	sort.Slice(modules, func(i, j int) bool { return modules[i].Path < modules[j].Path })
	return modules, nil
}

// licenseOf returns the license text at the root of a module.
func licenseOf(dir string) (string, error) {
	for _, name := range licenseNames {
		if text, err := os.ReadFile(filepath.Join(dir, name)); err == nil {
			return string(text), nil
		}
	}
	return "", errors.New("no license file in " + dir)
}

func goCommand(args ...string) ([]byte, error) {
	cmd := exec.Command("go", args...)
	cmd.Stderr = os.Stderr
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("go %s: %w", strings.Join(args, " "), err)
	}
	return out, nil
}
