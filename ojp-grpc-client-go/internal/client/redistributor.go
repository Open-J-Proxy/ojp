package client

import (
	"sync"
)

type ConnectionRedistributor struct {
	connTracker *ConnectionTracker
	config     *HealthCheckConfig
	mu         sync.RWMutex
}

func NewConnectionRedistributor(connTracker *ConnectionTracker, config *HealthCheckConfig) *ConnectionRedistributor {
	return &ConnectionRedistributor{
		connTracker: connTracker,
		config:     config,
	}
}

func (r *ConnectionRedistributor) Rebalance(recoveredServers []*ServerEndpoint, allHealthyServers []*ServerEndpoint) {
	if !r.config.IsRedistributionEnabled() {
		return
	}

	if len(recoveredServers) == 0 {
		return
	}

	if len(allHealthyServers) < 2 {
		return
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	totalConnections := r.countAllConnections(allHealthyServers)
	if totalConnections == 0 {
		return
	}

	idleFraction := r.config.IdleRebalanceFraction()
    maxClose := r.config.MaxClosePerRecovery()

    var totalClose int64
	for _, recovered := range recoveredServers {
		currentLoad := recovered.ConnectionCount()
		if currentLoad == 0 {
			continue
		}

		closeForRecovered := int64(float64(currentLoad) * idleFraction)
        if closeForRecovered > int64(maxClose) {
        	closeForRecovered = int64(maxClose)
        }
        if closeForRecovered < 0 {
        	closeForRecovered = 0
        }
        totalClose += closeForRecovered

		newLoad := recovered.LoadMetric() - closeForRecovered
        if newLoad < 0 {
         	newLoad = 0
        }
        recovered.SetLoadMetric(newLoad)
	}
 	receiverCount := int64(len(allHealthyServers) - len(recoveredServers))
 	if totalClose == 0 || receiverCount <= 0 {
 		return
 	}
 	redistribute := totalClose / receiverCount

	for _, server := range allHealthyServers {
		isRecovered := false
		for _, recovered := range recoveredServers {
			if server == recovered {
				isRecovered = true
				break
			}
		}
		if isRecovered {
			continue
		}
		server.SetLoadMetric(server.LoadMetric() + redistribute)
	}
}

func (r *ConnectionRedistributor) countAllConnections(servers []*ServerEndpoint) int64 {
	var total int64
	for _, s := range servers {
		total += s.ConnectionCount()
	}
	return total
}