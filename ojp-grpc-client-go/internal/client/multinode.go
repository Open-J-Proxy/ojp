package client

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	ojpgrpc "github.com/open-j-proxy/ojp-client/internal/gen/go/com/openjproxy/grpc"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type ChannelAndStub struct {
	channel *grpc.ClientConn
}

type MultinodeConnectionManager struct {
	endpoints       []*ServerEndpoint
	channelMap      map[*ServerEndpoint]*ChannelAndStub
	connTracker     *ConnectionTracker
	healthConfig    *HealthCheckConfig
	healthCheckStop chan struct{}
	started         atomic.Bool
	mu              sync.RWMutex
	roundRobinIdx   atomic.Int32
	originalURL     string
}

type ConnectionTracker struct {
	sessions    map[string]*ServerEndpoint
	sessionLock sync.RWMutex
}

func NewConnectionTracker() *ConnectionTracker {
	return &ConnectionTracker{
		sessions: make(map[string]*ServerEndpoint),
	}
}

func NewMultinodeConnectionManager(urls []string, healthConfig *HealthCheckConfig) (*MultinodeConnectionManager, error) {
	if len(urls) == 0 {
		return nil, fmt.Errorf("no server URLs provided")
	}
	if healthConfig == nil {
		healthConfig = DefaultHealthCheckConfig()
	}

	endpoints := make([]*ServerEndpoint, 0, len(urls))
	for _, url := range urls {
		ep, err := parseURL(url)
		if err != nil {
			return nil, fmt.Errorf("failed to parse URL %s: %w", url, err)
		}
		endpoints = append(endpoints, ep)
	}

	channelMap := make(map[*ServerEndpoint]*ChannelAndStub)
	for _, ep := range endpoints {
		channelMap[ep] = nil
	}

	return &MultinodeConnectionManager{
		endpoints:       endpoints,
		channelMap:      channelMap,
		connTracker:     NewConnectionTracker(),
		healthConfig:    healthConfig,
		healthCheckStop: make(chan struct{}),
		originalURL:     strings.Join(urls, ","),
	}, nil
}

func parseURL(url string) (*ServerEndpoint, error) {
	url = strings.TrimSpace(url)
	if strings.HasPrefix(url, "ojp://") {
		url = url[5:]
	} else if strings.HasPrefix(url, "jdbc:ojp[") {
		start := strings.Index(url, "]_")
		if start == -1 {
			return nil, fmt.Errorf("invalid JDBC URL format")
		}
		url = url[start+2:]
	}

	var host string
	var port int
	if strings.Contains(url, ":") {
		parts := strings.SplitN(url, ":", 2)
		host = parts[0]
		fmt.Sscanf(parts[1], "%d", &port)
	} else {
		host = url
		port = DefaultOJPGRPCPort
	}

	if host == "" {
		host = "localhost"
	}
	if port == 0 {
		port = DefaultOJPGRPCPort
	}

	return NewServerEndpoint(host, port), nil
}

func (m *MultinodeConnectionManager) Connect(ctx context.Context, details *ojpgrpc.ConnectionDetails) (*ojpgrpc.SessionInfo, error) {
	server := m.selectServer("")
	if server == nil {
		// Retry with a different server after a brief delay
		if err := RetryWithBackoff(ctx, 3, 100*time.Millisecond, 500*time.Millisecond, func() error {
			server = m.selectServer("")
			if server == nil {
				return fmt.Errorf("no healthy server available")
			}
			return nil
		}); err != nil {
            return nil, err
        }
	}

	channel, err := m.getChannel(server)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to %s: %w", server.Address(), err)
	}

	client := ojpgrpc.NewStatementServiceClient(channel)
	resp, err := client.Connect(ctx, details)
	if err != nil {
		server.MarkUnhealthy()
		m.handleServerFailure(server, err)
		return nil, fmt.Errorf("connect failed: %w", err)
	}

	if resp.GetSessionUUID() != "" {
		m.connTracker.BindSession(resp.GetSessionUUID(), server)
	}

	return resp, nil
}

func (m *MultinodeConnectionManager) selectServer(sessionKey string) *ServerEndpoint {
	if sessionKey != "" {
		if bound := m.connTracker.GetBoundServer(sessionKey); bound != nil && bound.IsHealthy() {
			return bound
		}
	}

	if m.healthConfig.IsLoadAwareSelectionEnabled() {
		return m.loadAwareServer()
	}
	return m.roundRobinServer()
}

func (m *MultinodeConnectionManager) roundRobinServer() *ServerEndpoint {
	m.mu.RLock()
	defer m.mu.RUnlock()

	count := len(m.endpoints)
	if count == 0 {
		return nil
	}

	idx := int(m.roundRobinIdx.Add(1)) % count

	for i := 0; i < count; i++ {
		ep := m.endpoints[(idx+i)%count]
		if ep.IsHealthy() {
			return ep
		}
	}

	// No healthy endpoint found; return the first as a last resort.
	return m.endpoints[0]
}

func (m *MultinodeConnectionManager) loadAwareServer() *ServerEndpoint {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var healthy []*ServerEndpoint
	for _, ep := range m.endpoints {
		if ep.IsHealthy() {
			healthy = append(healthy, ep)
		}
	}

	if len(healthy) == 0 {
		if len(m.endpoints) == 0 {
			return nil
		}
		return m.endpoints[0]
	}

	sort.Slice(healthy, func(i, j int) bool {
		return healthy[i].LoadMetric() < healthy[j].LoadMetric()
	})

	return healthy[0]
}

func (m *MultinodeConnectionManager) getChannel(server *ServerEndpoint) (*grpc.ClientConn, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if cached, ok := m.channelMap[server]; ok && cached != nil && cached.channel != nil {
		return cached.channel, nil
	}

	addr := server.Address()
	conn, err := grpc.NewClient(addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		return nil, err
	}

	m.channelMap[server] = &ChannelAndStub{
		channel: conn,
	}

	return conn, nil
}

func (m *MultinodeConnectionManager) handleServerFailure(server *ServerEndpoint, err error) {
	if IsConnectionLevelError(err) {
		server.MarkUnhealthy()
		m.connTracker.InvalidateSessionsForServer(server)
	}
}

func (m *MultinodeConnectionManager) StartHealthChecks() {
	if m.started.Swap(true) {
		return
	}
	go m.runHealthCheck()
}

func (m *MultinodeConnectionManager) runHealthCheck() {
	ticker := time.NewTicker(m.healthConfig.HealthCheckInterval())
	defer ticker.Stop()

	for {
		select {
		case <-m.healthCheckStop:
			return
		case <-ticker.C:
			m.checkAllEndpoints()
		}
	}
}

func (m *MultinodeConnectionManager) checkAllEndpoints() {
	for _, ep := range m.endpoints {
		m.checkEndpoint(ep)
	}
}

func (m *MultinodeConnectionManager) checkEndpoint(ep *ServerEndpoint) {
	channel, err := m.getChannel(ep)
	if err != nil {
		ep.MarkUnhealthy()
		return
	}

	client := ojpgrpc.NewStatementServiceClient(channel)
	ctx, cancel := context.WithTimeout(context.Background(), m.healthConfig.HealthCheckTimeout())
	defer cancel()

	details := &ojpgrpc.ConnectionDetails{
		Url:        "health-check",
		ClientUUID: "health-check-client",
	}

	session, err := client.Connect(ctx, details)
	if err != nil {
		ep.MarkUnhealthy()
		return
	}

	// Terminate the health check session to avoid server-side session leaks
	_, _ = client.TerminateSession(ctx, session)

	ep.MarkHealthy()
}

func (m *MultinodeConnectionManager) Shutdown() {
	if !m.started.Swap(false) {
		return
	}
	select {
	case <-m.healthCheckStop:
	default:
		close(m.healthCheckStop)
	}
	m.mu.Lock()
	defer m.mu.Unlock()

	for _, cs := range m.channelMap {
		if cs != nil && cs.channel != nil {
			cs.channel.Close()
		}
	}
	m.channelMap = make(map[*ServerEndpoint]*ChannelAndStub)
}

func (m *MultinodeConnectionManager) GetServerEndpoints() []*ServerEndpoint {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*ServerEndpoint, len(m.endpoints))
	copy(result, m.endpoints)
	return result
}

func (m *MultinodeConnectionManager) GenerateClusterHealth() string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var parts []string
	for _, ep := range m.endpoints {
		status := "UP"
		if !ep.IsHealthy() {
			status = "DOWN"
		}
		parts = append(parts, fmt.Sprintf("%s(%s)", ep.Address(), status))
	}
	return strings.Join(parts, ";")
}

func (t *ConnectionTracker) BindSession(session string, server *ServerEndpoint) {
	t.sessionLock.Lock()
	defer t.sessionLock.Unlock()
	t.sessions[session] = server
}

func (t *ConnectionTracker) GetBoundServer(session string) *ServerEndpoint {
	t.sessionLock.RLock()
	defer t.sessionLock.RUnlock()
	return t.sessions[session]
}

func (t *ConnectionTracker) InvalidateSessionsForServer(server *ServerEndpoint) {
	t.sessionLock.Lock()
	defer t.sessionLock.Unlock()

	for sess, ep := range t.sessions {
		if ep == server {
			delete(t.sessions, sess)
		}
	}
}

func (t *ConnectionTracker) TerminateSession(session string) {
	t.sessionLock.Lock()
	defer t.sessionLock.Unlock()
	delete(t.sessions, session)
}
