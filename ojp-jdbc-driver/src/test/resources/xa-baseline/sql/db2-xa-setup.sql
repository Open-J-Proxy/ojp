-- =====================================================================================
-- DB2 XA Transaction Setup Script
-- =====================================================================================
--
-- This script configures IBM DB2 for XA distributed transaction support.
--
-- Requirements:
-- 1. TM_DATABASE configuration for transaction manager
-- 2. DBADM privileges for XA operations
-- 3. Archive logging enabled (set via container env)
-- 4. Test table and sequence for XA testing
--
-- DB2 XA Permissions:
-- - DBADM authority provides all necessary XA privileges
-- - CONNECT, BINDADD, CREATETAB, IMPLICIT_SCHEMA privileges
-- - Access to SYSTOOLSPACE tablespace for XA coordination
--
-- Reference: IBM DB2 XA Configuration Guide
-- =====================================================================================

-- =====================================================================================
-- SECTION 1: Database Configuration for XA
-- =====================================================================================

-- NOTE: DB2 database configuration (UPDATE DB CFG) commands cannot be run as SQL.
-- These are DB2 CLP commands and must be executed via container exec after startup.
-- The DB2XATestContainer.configureTmDatabase() method handles TM_DATABASE configuration.
--
-- Container environment variables handle other DB2 configuration:
-- - ARCHIVE_LOGS=true enables archive logging
-- - DB2INST1_PASSWORD sets the instance password
-- - DBNAME sets the database name
--
-- For reference, these configurations would be done via CLP:
-- - UPDATE DBM CFG USING TM_DATABASE xatestdb IMMEDIATE
-- - UPDATE DB CFG FOR xatestdb USING LOGARCHMETH1 LOGRETAIN
-- - UPDATE DB CFG FOR xatestdb USING LOGFILSIZ 4096
-- - UPDATE DB CFG FOR xatestdb USING LOGPRIMARY 10
-- - UPDATE DB CFG FOR xatestdb USING LOGSECOND 10

-- =====================================================================================
-- SECTION 2: User Privileges for XA
-- =====================================================================================

-- Grant DBADM authority to the test user
-- This provides all necessary privileges for XA operations
GRANT DBADM ON DATABASE TO USER db2inst1;

-- Grant CONNECT privilege
GRANT CONNECT ON DATABASE TO USER db2inst1;

-- Grant BINDADD privilege (for binding packages)
GRANT BINDADD ON DATABASE TO USER db2inst1;

-- Grant CREATETAB privilege
GRANT CREATETAB ON DATABASE TO USER db2inst1;

-- Grant IMPLICIT_SCHEMA privilege
GRANT IMPLICIT_SCHEMA ON DATABASE TO USER db2inst1;

-- =====================================================================================
-- SECTION 3: Test Table and Sequence Setup
-- =====================================================================================

-- Create test table for XA baseline tests
-- This table is used across all DB2 XA test cases
CREATE TABLE IF NOT EXISTS xa_test_baseline (
    id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
    test_name VARCHAR(100) NOT NULL,
    test_value VARCHAR(255),
    test_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

-- Create index on test_name for query performance
CREATE INDEX IF NOT EXISTS idx_xa_test_name ON xa_test_baseline(test_name);

-- Grant full access to test table
GRANT ALL PRIVILEGES ON TABLE xa_test_baseline TO USER db2inst1;

-- =====================================================================================
-- SECTION 4: Tablespace Configuration for XA
-- =====================================================================================

-- Verify SYSTOOLSPACE exists (required for XA coordination)
-- DB2 uses SYSTOOLSPACE for XA transaction management
-- This is typically created automatically, but we verify it exists

-- If SYSTOOLSPACE doesn't exist, it will be created automatically
-- when XA transactions are first used

-- =====================================================================================
-- SECTION 5: XA Transaction Monitoring Views
-- =====================================================================================

-- DB2 provides these system views for XA transaction monitoring:
-- - SYSIBMADM.SNAPXACT - Transaction snapshot
-- - SYSIBMADM.XACT - Active transactions
-- - SYSIBMADM.INDOUBT_TRANSACTIONS - In-doubt (prepared) transactions

-- Grant access to XA monitoring views
GRANT SELECT ON SYSIBMADM.SNAPXACT TO USER db2inst1;
GRANT SELECT ON SYSIBMADM.XACT TO USER db2inst1;
GRANT SELECT ON SYSIBMADM.INDOUBT_TRANSACTIONS TO USER db2inst1;

-- =====================================================================================
-- SECTION 6: XA Configuration Verification Queries
-- =====================================================================================

-- Verify TM_DATABASE is enabled
-- VALUES (SELECT VALUE FROM SYSIBMADM.DBCFG WHERE NAME = 'tm_database');

-- Verify archive logging is enabled
-- VALUES (SELECT VALUE FROM SYSIBMADM.DBCFG WHERE NAME = 'logarchmeth1');

-- Verify user has DBADM authority
-- VALUES (SELECT GRANTEETYPE FROM SYSCAT.DBAUTH WHERE GRANTEE = 'DB2INST1' AND DBADMAUTH = 'Y');

-- Check for in-doubt transactions (should be empty initially)
-- SELECT * FROM SYSIBMADM.INDOUBT_TRANSACTIONS;

-- =====================================================================================
-- SECTION 7: Test Data Cleanup
-- =====================================================================================

-- Clean up any existing test data
DELETE FROM xa_test_baseline WHERE test_name LIKE 'test-%';

COMMIT;

-- =====================================================================================
-- SECTION 8: DB2 XA Important Notes
-- =====================================================================================

-- 1. TM_DATABASE Configuration:
--    - Must be ON for XA transaction coordination
--    - Requires database restart to take effect
--    - Enables external transaction manager support

-- 2. Archive Logging:
--    - Required for XA transactions
--    - Set via LOGARCHMETH1 or LOGARCHMETH2
--    - LOGRETAIN enables circular logging for recovery

-- 3. DBADM Authority:
--    - Provides all XA-related privileges
--    - Includes CONNECT, BINDADD, CREATETAB, and more
--    - Sufficient for all XA operations

-- 4. XA Driver Configuration:
--    - Use DB2XADataSource class
--    - Set driverType=4 for Type 4 (pure Java) driver
--    - Configure serverName, portNumber, databaseName

-- 5. In-Doubt Transaction Recovery:
--    - Use SYSIBMADM.INDOUBT_TRANSACTIONS view
--    - Manual resolution with COMMIT or ROLLBACK
--    - forget() operation not directly supported (use HEURISTIC ABORT/COMMIT)

-- 6. DB2 vs Oracle/SQL Server XA Differences:
--    - DB2 uses TM_DATABASE instead of specific stored procedures
--    - DBADM provides all permissions (no specific XA grants like Oracle)
--    - Recovery uses system views instead of procedures
--    - Heuristic outcomes handled differently

-- 7. Performance Considerations:
--    - XA transactions have higher overhead than local transactions
--    - Log file size affects XA performance
--    - SYSTOOLSPACE usage grows with XA activity

-- 8. Troubleshooting:
--    - Check db2diag.log for XA errors
--    - Verify TM_DATABASE with GET DB CFG command
--    - Monitor SYSIBMADM.INDOUBT_TRANSACTIONS for stuck transactions
--    - Use db2 list indoubt transactions command for recovery

-- =====================================================================================
-- Setup Complete
-- =====================================================================================

-- DB2 XA setup is now complete.
-- The database is configured to support distributed transactions via XA protocol.
-- Test table xa_test_baseline is ready for use in XA test cases.
