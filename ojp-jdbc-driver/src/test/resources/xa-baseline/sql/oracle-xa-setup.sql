-- Oracle XA Setup Script
-- This script configures Oracle Database for XA transaction testing

-- =====================================================
-- 1. Grant XA Permissions to Test User
-- =====================================================

-- The test user needs specific privileges for XA transactions
-- These grants are required for XA operations to work properly

-- Grant SELECT privilege on V$XATRANS$ (required for XA recovery)
GRANT SELECT ON V$XATRANS$ TO testuser;

-- Grant EXECUTE on DBMS_XA package (required for XA operations)
GRANT EXECUTE ON DBMS_XA TO testuser;

-- Grant FORCE TRANSACTION privilege (required for manual transaction management)
GRANT FORCE TRANSACTION TO testuser;

-- Grant FORCE ANY TRANSACTION privilege (for advanced XA scenarios)
GRANT FORCE ANY TRANSACTION TO testuser;

-- =====================================================
-- 2. Create Test Tables for XA Testing
-- =====================================================

-- Create a simple test table that will be used in XA transaction tests
-- This table is created in the test user's schema

CREATE TABLE testuser.xa_test_baseline (
    id NUMBER(10) PRIMARY KEY,
    test_name VARCHAR2(100),
    test_value VARCHAR2(200),
    test_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create an index for performance
CREATE INDEX testuser.idx_xa_test_name ON testuser.xa_test_baseline(test_name);

-- Grant permissions on the test table
GRANT ALL ON testuser.xa_test_baseline TO testuser;

-- =====================================================
-- 3. Configure XA Transaction Parameters
-- =====================================================

-- Set appropriate values for XA transaction timeouts
-- These can be adjusted based on test requirements

-- Note: Most XA configuration is done at the session or application level
-- The following are system-level settings that affect XA behavior

-- Enable distributed transactions (should already be enabled by default)
-- ALTER SYSTEM SET distributed_transactions = 'ENABLED' SCOPE=BOTH;

-- Commit comment (for tracking distributed transactions)
-- This is optional but useful for debugging
COMMIT COMMENT 'XA Setup Complete';

-- =====================================================
-- 4. Create Additional Test Objects (Optional)
-- =====================================================

-- Create a sequence for generating test IDs
CREATE SEQUENCE testuser.xa_test_seq
    START WITH 1
    INCREMENT BY 1
    NOCACHE
    NOCYCLE;

GRANT SELECT ON testuser.xa_test_seq TO testuser;

-- =====================================================
-- 5. Verification Queries
-- =====================================================

-- These queries can be used to verify the setup
-- They are included as comments for reference

-- Verify XA-related privileges:
-- SELECT * FROM DBA_SYS_PRIVS WHERE GRANTEE = 'TESTUSER';
-- SELECT * FROM DBA_TAB_PRIVS WHERE GRANTEE = 'TESTUSER';

-- Check distributed transaction settings:
-- SELECT name, value FROM V$PARAMETER WHERE name LIKE '%distributed%';

-- View active XA transactions (during testing):
-- SELECT * FROM V$XATRANS$;
-- SELECT * FROM DBA_2PC_PENDING;

-- =====================================================
-- 6. Setup Completion Message
-- =====================================================

-- Output success message (will appear in container logs)
COMMIT;

-- End of Oracle XA Setup Script
