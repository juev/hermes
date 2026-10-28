// Package tailnet runs a userspace Tailscale node inside the Hermes app and serves a local SOCKS5
// proxy onto the tailnet. It needs no Android VpnService, so it works next to another VPN.
//
// The functions use only strings, ints and errors, the types gomobile can bind.
package tailnet

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var (
	mu      sync.Mutex
	srv     *tsnet.Server
	logs    = newRing(200)
	authURL string
)

func init() {
	// Android denies app processes the netlink socket that net.Interfaces needs; the kernel still
	// tells which source address it would use, which is enough for the node to find its endpoints.
	netmon.RegisterInterfaceGetter(syntheticInterfaces)
}

// Start brings the node up and returns "host:port\npassword" of its SOCKS5 proxy (user "tsnet").
// An empty authKey makes the node wait for an interactive login; LoginURL then returns the link.
func Start(dir, controlURL, authKey, hostname string) (string, error) {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		srv.Close()
		srv = nil
	}
	authURL = ""
	s := &tsnet.Server{
		Dir:        dir,
		Hostname:   hostname,
		ControlURL: controlURL,
		AuthKey:    authKey,
		Logf:       func(format string, args ...any) { logs.add(fmt.Sprintf(format, args...)) },
		UserLogf: func(format string, args ...any) {
			line := fmt.Sprintf(format, args...)
			logs.add(line)
			if i := strings.Index(line, "https://"); i >= 0 && strings.Contains(line, "authenticate") {
				authURL = strings.Fields(line[i:])[0]
			}
		},
	}
	addr, cred, _, err := s.Loopback()
	if err != nil {
		s.Close()
		return "", err
	}
	srv = s
	return addr + "\n" + cred, nil
}

// Stop shuts the node down; its state stays in the directory for the next Start.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		srv.Close()
		srv = nil
	}
}

// Status returns JSON: {"state", "authUrl", "ips", "name", "error"}.
func Status() string {
	mu.Lock()
	s := srv
	mu.Unlock()
	out := map[string]any{"state": "Stopped"}
	if s == nil {
		return encode(out)
	}
	lc, err := s.LocalClient()
	if err != nil {
		out["error"] = err.Error()
		return encode(out)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		out["error"] = err.Error()
		return encode(out)
	}
	out["state"] = st.BackendState
	url := st.AuthURL
	if url == "" {
		url = authURL
	}
	out["authUrl"] = url
	if st.Self != nil {
		ips := make([]string, 0, len(st.Self.TailscaleIPs))
		for _, ip := range st.Self.TailscaleIPs {
			ips = append(ips, ip.String())
		}
		out["ips"] = ips
		out["name"] = st.Self.DNSName
	}
	return encode(out)
}

// Logs returns the last lines the node logged, oldest first.
func Logs() string {
	return strings.Join(logs.lines(), "\n")
}

func encode(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"state":"Error"}`
	}
	return string(b)
}

// syntheticInterfaces follows tailscale.com/feature/androidbin: one made-up interface that
// carries the outbound IPv4 and IPv6 source addresses of the process.
func syntheticInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		return nil, errors.New("tailnet: no outbound routes found")
	}
	return []netmon.Interface{{
		Interface: &net.Interface{MTU: 1500, Name: "android", Flags: net.FlagUp | net.FlagRunning},
		AltAddrs:  addrs,
	}}, nil
}

// outboundIP reports the source address the kernel picks for a socket to addr; dialing UDP sends
// nothing.
func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}

type ring struct {
	mu   sync.Mutex
	buf  []string
	size int
}

func newRing(size int) *ring { return &ring{size: size} }

func (r *ring) add(line string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.buf = append(r.buf, line)
	if len(r.buf) > r.size {
		r.buf = r.buf[len(r.buf)-r.size:]
	}
	log.Print(line)
}

func (r *ring) lines() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.buf...)
}
