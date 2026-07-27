package dns

/*
#cgo LDFLAGS: -landroid
#include "vpn_jni.h"
#include <stdint.h>
#include <stdlib.h>
extern void NotifyDnsResult(int64_t id, const char* result);
*/
import "C"
import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"github.com/miekg/dns"
	"github.com/wgtunnel/android/shared"
	coredns "github.com/wgtunnel/core/engine/dns"
	"github.com/wgtunnel/core/engine/dns/transport/doh"
	"github.com/wgtunnel/core/engine/dns/transport/dot"
	"github.com/wgtunnel/core/engine/dns/transport/plain"
	"golang.org/x/sys/unix"
)

// buildBootstrapEngine creates a short-lived engine for one bootstrap resolution
func buildBootstrapEngine(
	protocol, resolvedUpstream, originalUpstream string,
	bypass bool,
) (*coredns.Engine, error) {

	var t coredns.Transport
	var err error

	switch strings.ToLower(protocol) {
	case "doh":
		t, err = newDoHTransport(originalUpstream, resolvedUpstream, bypass)
	case "dot":
		t, err = newDoTTransport(originalUpstream, resolvedUpstream, bypass)
	default: // plain / udp / tcp
		t, err = newPlainTransport(resolvedUpstream, bypass)
	}
	if err != nil {
		return nil, err
	}

	engine := coredns.NewEngine()
	engine.RegisterTransport("bootstrap", t)

	router := coredns.NewSimpleRouter(engine, "bootstrap")
	engine.SetRouter(router)

	return engine, nil
}

func newPlainTransport(resolved string, bypass bool) (coredns.Transport, error) {
	servers := splitUpstreams(resolved)
	if len(servers) == 0 {
		return nil, fmt.Errorf("plain bootstrap: no servers")
	}

	for i, s := range servers {
		if _, _, err := net.SplitHostPort(s); err != nil {
			servers[i] = net.JoinHostPort(s, "53")
		}
	}
	tr := plain.New(servers, "udp")
	tr.Dialer = GetDialer(bypass)
	return tr, nil
}

func newDoTTransport(original, resolved string, bypass bool) (coredns.Transport, error) {
	servers := splitUpstreams(resolved)
	if len(servers) == 0 {
		return nil, fmt.Errorf("dot bootstrap: no servers")
	}
	sni, defPort, err := net.SplitHostPort(original)
	if err != nil {
		sni, defPort = original, "853"
	}
	for i, s := range servers {
		if _, _, err := net.SplitHostPort(s); err != nil {
			servers[i] = net.JoinHostPort(s, defPort)
		}
	}
	tr := dot.New(servers, sni)
	tr.Dialer = GetDialer(bypass)
	return tr, nil
}

func newDoHTransport(original, resolved string, bypass bool) (coredns.Transport, error) {
	urls := splitUpstreams(resolved)
	if len(urls) == 0 {
		return nil, fmt.Errorf("doh bootstrap: no urls")
	}
	orig := original
	if !strings.HasPrefix(orig, "https://") && !strings.HasPrefix(orig, "http://") {
		orig = "https://" + orig
	}
	u, err := url.Parse(orig)
	if err != nil {
		return nil, err
	}
	sni := u.Hostname()

	tr := doh.New(urls, sni)
	tr.Timeout = 5 * time.Second
	d := GetDialer(bypass)
	tr.DialContext = d.DialContext
	return tr, nil
}

//export StartResolveBootstrap
func StartResolveBootstrap(
	id C.int64_t,
	host *C.char,
	protocol *C.char,
	resolvedUpstream *C.char,
	originalUpstream *C.char,
	bypass C.int,
) {
	h := C.GoString(host)
	p := C.GoString(protocol)
	resolved := C.GoString(resolvedUpstream)
	original := C.GoString(originalUpstream)
	bp := bypass == 1

	shared.LogDebug("DNS", "StartResolveBootstrap id=%d host=%s protocol=%s", id, h, p)

	go func(reqID int64, h, p, resolved, original string, bypass bool) {
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		defer cancel()

		engine, err := buildBootstrapEngine(p, resolved, original, bypass)
		if err != nil {
			notifyError(reqID, err)
			return
		}
		defer engine.Close()

		// Resolve A + AAAA
		v4, v6, err := resolveWithEngine(ctx, engine, h)
		if err != nil {
			notifyError(reqID, err)
			return
		}

		resultStr := fmt.Sprintf("v4=%s;v6=%s",
			strings.Join(toStringSlice(v4), ","),
			strings.Join(toStringSlice(v6), ","),
		)
		shared.LogDebug("DNS", "ResolveBootstrap success id=%d → %s", reqID, resultStr)

		cResult := C.CString(resultStr)
		C.NotifyDnsResult(C.int64_t(reqID), cResult)
		C.free(unsafe.Pointer(cResult))
	}(int64(id), h, p, resolved, original, bp)
}

func resolveWithEngine(ctx context.Context, engine *coredns.Engine, host string) (v4, v6 []netip.Addr, err error) {
	// A
	msgA := new(dns.Msg)
	msgA.SetQuestion(dns.Fqdn(host), dns.TypeA)
	msgA.SetEdns0(4096, true)

	respA, errA := engine.Exchange(ctx, msgA)
	if errA == nil {
		v4 = parseAnswers(respA.Msg, dns.TypeA)
	}

	// AAAA
	msgAAAA := new(dns.Msg)
	msgAAAA.SetQuestion(dns.Fqdn(host), dns.TypeAAAA)
	msgAAAA.SetEdns0(4096, true)

	respAAAA, errAAAA := engine.Exchange(ctx, msgAAAA)
	if errAAAA == nil {
		v6 = parseAnswers(respAAAA.Msg, dns.TypeAAAA)
	}

	if len(v4) == 0 && len(v6) == 0 {
		return nil, nil, fmt.Errorf("no addresses: A=%v AAAA=%v", errA, errAAAA)
	}
	return v4, v6, nil
}

func parseAnswers(msg *dns.Msg, qtype uint16) []netip.Addr {
	var out []netip.Addr
	for _, ans := range msg.Answer {
		switch qtype {
		case dns.TypeA:
			if a, ok := ans.(*dns.A); ok {
				if ip, err := netip.ParseAddr(a.A.String()); err == nil {
					out = append(out, ip)
				}
			}
		case dns.TypeAAAA:
			if aaaa, ok := ans.(*dns.AAAA); ok {
				if ip, err := netip.ParseAddr(aaaa.AAAA.String()); err == nil {
					out = append(out, ip)
				}
			}
		}
	}
	return out
}

func notifyError(id int64, err error) {
	shared.LogError("DNS", "ResolveBootstrap failed id=%d: %v", id, err)
	cResult := C.CString("ERR|" + err.Error())
	C.NotifyDnsResult(C.int64_t(id), cResult)
	C.free(unsafe.Pointer(cResult))
}

func splitUpstreams(s string) []string {
	var out []string
	for _, line := range strings.Split(s, ",") {
		line = strings.TrimSpace(line)
		if line != "" {
			out = append(out, line)
		}
	}
	return out
}

func toStringSlice(addrs []netip.Addr) []string {
	out := make([]string, len(addrs))
	for i, a := range addrs {
		out[i] = a.String()
	}
	return out
}

func GetDialer(bypass bool) *net.Dialer {
	if !bypass {
		return &net.Dialer{LocalAddr: nil}
	}
	shared.LogDebug("DNS", "Creating bypass dialer")
	return &net.Dialer{
		Control: func(network, address string, c syscall.RawConn) error {
			var opErr error
			err := c.Control(func(fd uintptr) {
				if C.bypass_socket(C.int(fd)) == 0 {
					opErr = unix.EACCES
					shared.LogError("DNS", "Failed to bypass socket FD: %d", fd)
				} else {
					shared.LogDebug("DNS", "Bypassed DNS socket FD: %d", fd)
				}
			})
			if err != nil {
				return err
			}
			return opErr
		},
	}
}
