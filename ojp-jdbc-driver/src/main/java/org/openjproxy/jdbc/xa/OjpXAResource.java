package org.openjproxy.jdbc.xa;

import com.google.protobuf.ByteString;
import com.openjproxy.grpc.*;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.client.StatementService;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.SQLException;

/**
 * Implementation of XAResource that delegates all operations to the OJP server via StatementService.
 */
@Slf4j
public class OjpXAResource implements XAResource {

    private final StatementService statementService;
    private final SessionInfo sessionInfo;
    private int transactionTimeout = 0;

    public OjpXAResource(StatementService statementService, SessionInfo sessionInfo) {
        this.statementService = statementService;
        this.sessionInfo = sessionInfo;
    }

    @Override
    public void start(Xid xid, int flags) throws XAException {
        log.debug("start: xid={}, flags={}", xid, flags);
        try {
            XaStartRequest request = XaStartRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .setFlags(flags)
                    .build();
            XaResponse response = statementService.xaStart(request);
            if (!response.getSuccess()) {
                throw new XAException(response.getMessage());
            }
        } catch (XAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in start", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public void end(Xid xid, int flags) throws XAException {
        log.debug("end: xid={}, flags={}", xid, flags);
        try {
            XaEndRequest request = XaEndRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .setFlags(flags)
                    .build();
            XaResponse response = statementService.xaEnd(request);
            if (!response.getSuccess()) {
                throw new XAException(response.getMessage());
            }
        } catch (XAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in end", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public int prepare(Xid xid) throws XAException {
        log.debug("prepare: xid={}", xid);
        try {
            XaPrepareRequest request = XaPrepareRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .build();
            XaPrepareResponse response = statementService.xaPrepare(request);
            return response.getResult();
        } catch (Exception e) {
            log.error("Error in prepare", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public void commit(Xid xid, boolean onePhase) throws XAException {
        log.debug("commit: xid={}, onePhase={}", xid, onePhase);
        try {
            XaCommitRequest request = XaCommitRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .setOnePhase(onePhase)
                    .build();
            XaResponse response = statementService.xaCommit(request);
            if (!response.getSuccess()) {
                throw new XAException(response.getMessage());
            }
        } catch (XAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in commit", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public void rollback(Xid xid) throws XAException {
        log.debug("rollback: xid={}", xid);
        try {
            XaRollbackRequest request = XaRollbackRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .build();
            XaResponse response = statementService.xaRollback(request);
            if (!response.getSuccess()) {
                throw new XAException(response.getMessage());
            }
        } catch (XAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in rollback", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public Xid[] recover(int flag) throws XAException {
        log.debug("recover: flag={}", flag);
        try {
            XaRecoverRequest request = XaRecoverRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setFlag(flag)
                    .build();
            XaRecoverResponse response = statementService.xaRecover(request);
            return response.getXidsList().stream()
                    .map(this::fromXidProto)
                    .toArray(Xid[]::new);
        } catch (Exception e) {
            log.error("Error in recover", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public void forget(Xid xid) throws XAException {
        log.debug("forget: xid={}", xid);
        try {
            XaForgetRequest request = XaForgetRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setXid(toXidProto(xid))
                    .build();
            XaResponse response = statementService.xaForget(request);
            if (!response.getSuccess()) {
                throw new XAException(response.getMessage());
            }
        } catch (XAException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in forget", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public boolean setTransactionTimeout(int seconds) throws XAException {
        log.debug("setTransactionTimeout: seconds={}", seconds);
        try {
            XaSetTransactionTimeoutRequest request = XaSetTransactionTimeoutRequest.newBuilder()
                    .setSession(sessionInfo)
                    .setSeconds(seconds)
                    .build();
            XaSetTransactionTimeoutResponse response = statementService.xaSetTransactionTimeout(request);
            if (response.getSuccess()) {
                this.transactionTimeout = seconds;
            }
            return response.getSuccess();
        } catch (Exception e) {
            log.error("Error in setTransactionTimeout", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public int getTransactionTimeout() throws XAException {
        log.debug("getTransactionTimeout");
        try {
            XaGetTransactionTimeoutRequest request = XaGetTransactionTimeoutRequest.newBuilder()
                    .setSession(sessionInfo)
                    .build();
            XaGetTransactionTimeoutResponse response = statementService.xaGetTransactionTimeout(request);
            return response.getSeconds();
        } catch (Exception e) {
            log.error("Error in getTransactionTimeout", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    public boolean isSameRM(XAResource xares) throws XAException {
        log.debug("isSameRM: xares={}", xares);
        if (!(xares instanceof OjpXAResource)) {
            return false;
        }
        try {
            OjpXAResource other = (OjpXAResource) xares;
            XaIsSameRMRequest request = XaIsSameRMRequest.newBuilder()
                    .setSession1(sessionInfo)
                    .setSession2(other.sessionInfo)
                    .build();
            XaIsSameRMResponse response = statementService.xaIsSameRM(request);
            return response.getIsSame();
        } catch (Exception e) {
            log.error("Error in isSameRM", e);
            XAException xae = new XAException(XAException.XAER_RMERR);
            xae.initCause(e);
            throw xae;
        }
    }

    /**
     * Convert javax.transaction.xa.Xid to protobuf XidProto.
     */
    private XidProto toXidProto(Xid xid) {
        return XidProto.newBuilder()
                .setFormatId(xid.getFormatId())
                .setGlobalTransactionId(ByteString.copyFrom(xid.getGlobalTransactionId()))
                .setBranchQualifier(ByteString.copyFrom(xid.getBranchQualifier()))
                .build();
    }

    /**
     * Convert protobuf XidProto to javax.transaction.xa.Xid.
     */
    private Xid fromXidProto(XidProto xidProto) {
        return new OjpXid(
                xidProto.getFormatId(),
                xidProto.getGlobalTransactionId().toByteArray(),
                xidProto.getBranchQualifier().toByteArray()
        );
    }

    /**
     * Simple implementation of Xid interface.
     */
    private static class OjpXid implements Xid {
        private final int formatId;
        private final byte[] globalTransactionId;
        private final byte[] branchQualifier;

        public OjpXid(int formatId, byte[] globalTransactionId, byte[] branchQualifier) {
            this.formatId = formatId;
            this.globalTransactionId = globalTransactionId;
            this.branchQualifier = branchQualifier;
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalTransactionId;
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchQualifier;
        }
    }
}
