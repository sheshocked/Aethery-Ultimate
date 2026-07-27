package dns

/*
#include <stdint.h>
#include <stdlib.h>
char* JniLookupOnUnderlayNetwork(const char* host, const char* networkFamily);
*/
import "C"

import (
	"context"
	"fmt"
	"net/netip"
	"strings"
	"sync/atomic"
	"unsafe"

	"github.com/wgtunnel/android/shared"
	"github.com/wgtunnel/core/engine"
	coredns "github.com/wgtunnel/core/engine/dns"
	"github.com/wgtunnel/core/engine/dns/transport/local"
	"github.com/wgtunnel/core/engine/platform/android"
)

var underlayNetworkHandle atomic.Int64

//export GoSetUnderlayNetworkHandle
func GoSetUnderlayNetworkHandle(handle int64) {
	underlayNetworkHandle.Store(handle)
	shared.LogDebug("DNS", "underlay network handle=%d", handle)
}

func CurrentUnderlayNetworkHandle() int64 {
	return underlayNetworkHandle.Load()
}

func jniLookupOnUnderlayNetwork(ctx context.Context, network, host string) ([]netip.Addr, error) {
	shared.LogDebug("DNS", "local fallback lookup host=%s family=%s", host, network)

	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	chost := C.CString(host)
	cfam := C.CString(network)
	defer C.free(unsafe.Pointer(chost))
	defer C.free(unsafe.Pointer(cfam))

	cres := C.JniLookupOnUnderlayNetwork(chost, cfam)
	if cres == nil {
		return nil, fmt.Errorf("lookup fallback: jni failed for %s", host)
	}
	defer C.free(unsafe.Pointer(cres))

	text := strings.TrimSpace(C.GoString(cres))
	if text == "" {
		return nil, fmt.Errorf("lookup fallback: no addresses for %s", host)
	}

	var out []netip.Addr
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		addr, err := netip.ParseAddr(line)
		if err != nil {
			continue
		}
		out = append(out, addr)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("lookup fallback: parse failed for %s", host)
	}
	return out, nil
}

func newAndroidLocalTransport() coredns.Transport {
	lookupFunc := func(ctx context.Context, _ int64, network, host string) ([]netip.Addr, error) {
		return jniLookupOnUnderlayNetwork(ctx, network, host)
	}
	resolver := android.NewResolver(lookupFunc)
	localT := local.New(resolver)
	localT.SetNetworkHandleFunc(CurrentUnderlayNetworkHandle)
	localT.SetLogger(shared.NewCoreLogger("Local DNS"))
	return localT
}

// SetupTunnelDNSEngine wires Android local transport, then calls core engine setup.
func SetupTunnelDNSEngine(cfg *coredns.TunnelDNSConfig) (*coredns.Engine, error) {
	if cfg == nil {
		return nil, nil
	}

	sc := engine.SetupConfig{Config: cfg}
	if cfg.DefaultTransport == "local" || len(cfg.LocalSuffixes) > 0 {
		sc.Local = newAndroidLocalTransport()
	}
	return engine.SetupTunnelDNSEngine(sc)
}
