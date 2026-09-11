package client

import (
	"strconv"
	"strings"
	"time"
)

type HealthCheckConfig struct {
	healthCheckIntervalMs   int64
	healthCheckThresholdMs int64
	healthCheckTimeoutMs   int64
	redistributionEnabled bool
	idleRebalanceFraction float64
	maxClosePerRecovery  int
	loadAwareSelectionEnabled bool
}

const (
	DefaultHealthCheckIntervalMs   = 5000
	DefaultHealthCheckThresholdMs = 5000
	DefaultHealthCheckTimeoutMs  = 5000
	DefaultRedistributionEnabled = true
	DefaultIdleRebalanceFraction = 1.0
	DefaultMaxClosePerRecovery = 100
	DefaultLoadAwareSelectionEnabled = true
)

func LoadHealthCheckConfig(props map[string]string) *HealthCheckConfig {
	if props == nil {
		return DefaultHealthCheckConfig()
	}
	interval := getInt64(props, "ojp.health.check.interval", DefaultHealthCheckIntervalMs)
	threshold := getInt64(props, "ojp.health.check.threshold", DefaultHealthCheckThresholdMs)
	timeout := getInt64(props, "ojp.health.check.timeout", DefaultHealthCheckTimeoutMs)
	enabled := getBool(props, "ojp.redistribution.enabled", DefaultRedistributionEnabled)
	idleFraction := getFloat(props, "ojp.redistribution.idleRebalanceFraction", DefaultIdleRebalanceFraction)
	maxClose := getInt(props, "ojp.redistribution.maxClosePerRecovery", DefaultMaxClosePerRecovery)
	loadAware := getBool(props, "ojp.loadaware.selection.enabled", DefaultLoadAwareSelectionEnabled)

	return &HealthCheckConfig{
		healthCheckIntervalMs:   interval,
		healthCheckThresholdMs: threshold,
		healthCheckTimeoutMs:  timeout,
		redistributionEnabled: enabled,
		idleRebalanceFraction: idleFraction,
		maxClosePerRecovery:  maxClose,
		loadAwareSelectionEnabled: loadAware,
	}
}

func DefaultHealthCheckConfig() *HealthCheckConfig {
	return &HealthCheckConfig{
		healthCheckIntervalMs:   int64(DefaultHealthCheckIntervalMs),
		healthCheckThresholdMs: int64(DefaultHealthCheckThresholdMs),
		healthCheckTimeoutMs:  int64(DefaultHealthCheckTimeoutMs),
		redistributionEnabled: DefaultRedistributionEnabled,
		idleRebalanceFraction: DefaultIdleRebalanceFraction,
		maxClosePerRecovery:  DefaultMaxClosePerRecovery,
		loadAwareSelectionEnabled: DefaultLoadAwareSelectionEnabled,
	}
}

func getInt64(props map[string]string, key string, defaultValue int64) int64 {
	v, ok := props[key]
	if !ok || v == "" {
		return defaultValue
	}
	parsed, err := strconv.ParseInt(v, 10, 64)
	if err != nil || parsed < 0 {
		return defaultValue
	}
	return parsed
}

func getInt(props map[string]string, key string, defaultValue int) int {
	v, ok := props[key]
	if !ok || v == "" {
		return defaultValue
	}
	parsed, err := strconv.Atoi(v)
	if err != nil || parsed < 0 {
		return defaultValue
	}
	return parsed
}

func getBool(props map[string]string, key string, defaultValue bool) bool {
	v, ok := props[key]
	if !ok || v == "" {
		return defaultValue
	}
	return strings.EqualFold(v, "true")
}

func getFloat(props map[string]string, key string, defaultValue float64) float64 {
	v, ok := props[key]
	if !ok || v == "" {
		return defaultValue
	}
	parsed, err := strconv.ParseFloat(v, 64)
	if err != nil || parsed < 0 || parsed > 1 {
		return defaultValue
	}
	return parsed
}

func (c *HealthCheckConfig) HealthCheckIntervalMs() int64   { return c.healthCheckIntervalMs }
func (c *HealthCheckConfig) HealthCheckThresholdMs() int64 { return c.healthCheckThresholdMs }
func (c *HealthCheckConfig) HealthCheckTimeoutMs() int64  { return c.healthCheckTimeoutMs }
func (c *HealthCheckConfig) IsRedistributionEnabled() bool { return c.redistributionEnabled }
func (c *HealthCheckConfig) IdleRebalanceFraction() float64 { return c.idleRebalanceFraction }
func (c *HealthCheckConfig) MaxClosePerRecovery() int        { return c.maxClosePerRecovery }
func (c *HealthCheckConfig) IsLoadAwareSelectionEnabled() bool { return c.loadAwareSelectionEnabled }

func (c *HealthCheckConfig) HealthCheckInterval() time.Duration   { return time.Duration(c.healthCheckIntervalMs) * time.Millisecond }
func (c *HealthCheckConfig) HealthCheckThreshold() time.Duration { return time.Duration(c.healthCheckThresholdMs) * time.Millisecond }
func (c *HealthCheckConfig) HealthCheckTimeout() time.Duration  { return time.Duration(c.healthCheckTimeoutMs) * time.Millisecond }