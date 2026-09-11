package client

import (
	"fmt"
	"sync/atomic"
	"time"
)

// ServerEndpoint represents an OJP server endpoint with health status tracking.
type ServerEndpoint struct {
	host          string
	port          int
	dataSourceName string
	healthy      atomic.Bool
	lastFailureTime atomic.Int64
	connCount    atomic.Int64 // Number of active connections
	loadMetric  atomic.Int64 // Load metric for load-aware selection
}

// NewServerEndpoint creates a new server endpoint.
func NewServerEndpoint(host string, port int) *ServerEndpoint {
	return NewServerEndpointWithDS(host, port, "default")
}

// NewServerEndpointWithDS creates a new server endpoint with data source name.
func NewServerEndpointWithDS(host string, port int, dataSourceName string) *ServerEndpoint {
	if host == "" {
		panic("host cannot be empty")
	}
	if port <= 0 || port > 65535 {
		panic("port must be between 1 and 65535")
	}
	ds := dataSourceName
	if ds == "" {
		ds = "default"
	}
	return &ServerEndpoint{
		host:          host,
		port:          port,
		dataSourceName: ds,
	}
}

// Host returns the server host.
func (s *ServerEndpoint) Host() string {
	return s.host
}

// Port returns the server port.
func (s *ServerEndpoint) Port() int {
	return s.port
}

// DataSourceName returns the data source name.
func (s *ServerEndpoint) DataSourceName() string {
	return s.dataSourceName
}

// Address returns host:port string.
func (s *ServerEndpoint) Address() string {
	return fmt.Sprintf("%s:%d", s.host, s.port)
}

// IsHealthy returns whether the server is healthy.
func (s *ServerEndpoint) IsHealthy() bool {
	return s.healthy.Load()
}

// SetHealthy sets the health status.
func (s *ServerEndpoint) SetHealthy(healthy bool) {
	s.healthy.Store(healthy)
	if healthy {
		s.lastFailureTime.Store(0)
	}
}

// MarkHealthy marks the server as healthy.
func (s *ServerEndpoint) MarkHealthy() {
	s.healthy.Store(true)
	s.lastFailureTime.Store(0)
}

// MarkUnhealthy marks the server as unhealthy.
func (s *ServerEndpoint) MarkUnhealthy() {
	s.healthy.Store(false)
	s.lastFailureTime.Store(time.Now().UnixMilli())
}

// LastFailureTime returns the last failure timestamp.
func (s *ServerEndpoint) LastFailureTime() int64 {
	return s.lastFailureTime.Load()
}

// IncrConnection increments the connection count.
func (s *ServerEndpoint) IncrConnection() {
	s.connCount.Add(1)
}

// DecrConnection decrements the connection count.
func (s *ServerEndpoint) DecrConnection() {
	s.connCount.Add(-1)
}

// ConnectionCount returns the current connection count.
func (s *ServerEndpoint) ConnectionCount() int64 {
	return s.connCount.Load()
}

// SetLoadMetric sets the load metric for load-aware selection.
func (s *ServerEndpoint) SetLoadMetric(load int64) {
	s.loadMetric.Store(load)
}

// LoadMetric returns the current load metric.
func (s *ServerEndpoint) LoadMetric() int64 {
	return s.loadMetric.Load()
}

// Equals compares two endpoints for equality.
func (s *ServerEndpoint) Equals(other *ServerEndpoint) bool {
	if other == nil {
		return false
	}
	return s.host == other.host && s.port == other.port
}

func (s *ServerEndpoint) String() string {
	return s.Address()
}