package dns

import (
	"strings"

	"github.com/amnezia-vpn/amneziawg-go/tun"
	"github.com/wgtunnel/android/shared"
	coredns "github.com/wgtunnel/core/engine/dns"
	coretun "github.com/wgtunnel/core/engine/tun"
)

// MaybeWrapTUN returns WrapperTUN when valid dnsConfigJSON is present, otherwise, just returns base or closes base on error
func MaybeWrapTUN(base tun.Device, dnsConfigJSON string) (tun.Device, error) {
	if strings.TrimSpace(dnsConfigJSON) == "" {
		return base, nil
	}

	cfg, err := coredns.ParseTunnelDNSConfig(dnsConfigJSON)
	if err != nil {
		base.Close()
		return nil, err
	}
	if cfg == nil {
		return base, nil
	}

	engine, err := SetupTunnelDNSEngine(cfg)
	if err != nil {
		base.Close()
		return nil, err
	}
	if engine == nil {
		return base, nil
	}

	ft, err := coretun.NewWrapperTUN(
		base,
		engine,
		cfg.FakeDNS,
		shared.NewCoreLogger("WrapperTUN"),
	)
	if err != nil {
		_ = engine.Close()
		base.Close()
		return nil, err
	}
	return ft, nil
}
