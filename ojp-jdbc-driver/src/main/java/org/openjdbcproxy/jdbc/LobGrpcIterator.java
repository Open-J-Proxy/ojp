package org.openjdbcproxy.jdbc;

import com.google.common.util.concurrent.SettableFuture;
import com.openjdbcproxy.grpc.LobDataBlock;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Iterator over the blocks received when getting a LOB object.
 * IMPORTANT: can only be consumed once as per the data is removed from memory once consumed.
 */
public class LobGrpcIterator implements Iterator<LobDataBlock> {
    private final List<LobDataBlock> blocksReceived = Collections.synchronizedList(new ArrayList<>());
    private boolean finished = false;
    @Setter
    private Throwable error;

    public void addBlock(LobDataBlock block) {
        this.blocksReceived.add(block);
    }

    @Override
    public boolean hasNext() {
        if (this.error != null) {
            throw new RuntimeException(this.error);
        }
        
        // Add timeout to prevent infinite loops
        long startTime = System.currentTimeMillis();
        long timeoutMs = 30000; // 30 seconds timeout
        
        while (blocksReceived.isEmpty() && !finished) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new RuntimeException("Timeout waiting for LOB data blocks after " + timeoutMs + "ms");
            }
            try {
                Thread.sleep(1);//TODO implement this wait in a more efficient way.
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return !blocksReceived.isEmpty();
    }

    @Override
    public LobDataBlock next() {
        if (this.error != null) {
            throw new RuntimeException(this.error);
        }
        LobDataBlock block = this.blocksReceived.get(0);
        this.blocksReceived.remove(0);
        return block;
    }

    public void finished() {
        this.finished = true;
    }
}
