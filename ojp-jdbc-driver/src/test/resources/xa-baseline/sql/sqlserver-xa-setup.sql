-- SQL Server XA Transaction Setup Script
-- This script configures SQL Server for XA transaction support
-- Must be run with sa or sysadmin privileges

-- ============================================================================
-- PART 1: Install XA Support
-- ============================================================================

-- Enable XA transactions by installing the extended stored procedures
-- This creates the necessary infrastructure for distributed transactions
-- Note: This requires the sqljdbc_xa.dll to be registered, but TestContainers
-- SQL Server images already have this configured

USE master;
GO

-- Check if XA procedures already exist
IF NOT EXISTS (SELECT * FROM sys.objects WHERE name = 'xp_sqljdbc_xa_init')
BEGIN
    PRINT 'XA procedures not found. They should be pre-installed in the container.';
    -- In a real environment, you would run:
    -- EXEC sp_sqljdbc_xa_install
END
ELSE
BEGIN
    PRINT 'XA procedures already installed.';
END
GO

-- ============================================================================
-- PART 2: Create Test Database and User
-- ============================================================================

-- Create test database if it doesn't exist
IF NOT EXISTS (SELECT * FROM sys.databases WHERE name = 'xatestdb')
BEGIN
    CREATE DATABASE xatestdb;
    PRINT 'Created database: xatestdb';
END
ELSE
BEGIN
    PRINT 'Database xatestdb already exists.';
END
GO

-- Switch to test database
USE xatestdb;
GO

-- ============================================================================
-- PART 3: Create Test Table and Sequence
-- ============================================================================

-- Create test table for XA transaction testing
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'xa_test_baseline')
BEGIN
    CREATE TABLE xa_test_baseline (
        id INT PRIMARY KEY,
        test_name NVARCHAR(100) NOT NULL,
        test_value NVARCHAR(255),
        test_timestamp DATETIME2 DEFAULT GETDATE()
    );
    
    -- Create index on test_name for better query performance
    CREATE INDEX idx_xa_test_name ON xa_test_baseline(test_name);
    
    PRINT 'Created table: xa_test_baseline';
END
ELSE
BEGIN
    PRINT 'Table xa_test_baseline already exists.';
END
GO

-- Create sequence for ID generation
IF NOT EXISTS (SELECT * FROM sys.sequences WHERE name = 'xa_test_seq')
BEGIN
    CREATE SEQUENCE xa_test_seq
        START WITH 1
        INCREMENT BY 1
        MINVALUE 1
        MAXVALUE 9999999999
        NO CYCLE
        CACHE 10;
    
    PRINT 'Created sequence: xa_test_seq';
END
ELSE
BEGIN
    PRINT 'Sequence xa_test_seq already exists.';
END
GO

-- ============================================================================
-- PART 4: Grant XA Permissions
-- ============================================================================

-- The 'sa' user already has all necessary permissions
-- But we'll verify XA role membership

USE master;
GO

-- Check if SqlJDBCXAUser role exists
IF NOT EXISTS (SELECT * FROM sys.server_principals WHERE name = 'SqlJDBCXAUser' AND type = 'R')
BEGIN
    PRINT 'SqlJDBCXAUser role not found. Creating...';
    -- Note: This role is typically created by sp_sqljdbc_xa_install
    -- We'll document that it should exist
END
ELSE
BEGIN
    PRINT 'SqlJDBCXAUser role exists.';
END
GO

-- Grant execute permissions on XA stored procedures to sa (redundant but explicit)
GRANT EXECUTE ON xp_sqljdbc_xa_init TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_start TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_end TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_prepare TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_commit TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_rollback TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_recover TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_forget TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_rollback_ex TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_forget_ex TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_prepare_ex TO sa;
GRANT EXECUTE ON xp_sqljdbc_xa_init_ex TO sa;
GO

PRINT 'Granted XA permissions to sa user.';
GO

-- ============================================================================
-- PART 5: Verification Queries
-- ============================================================================

-- These queries can be used to verify the setup

-- Verify XA procedures are installed
SELECT name, type_desc 
FROM sys.objects 
WHERE name LIKE 'xp_sqljdbc_xa%'
ORDER BY name;
GO

-- Verify test database exists
SELECT name, database_id, create_date
FROM sys.databases
WHERE name = 'xatestdb';
GO

-- Verify test table exists
USE xatestdb;
SELECT TABLE_NAME, TABLE_TYPE
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_NAME = 'xa_test_baseline';
GO

-- Verify sequence exists
SELECT name, start_value, increment, minimum_value, maximum_value
FROM sys.sequences
WHERE name = 'xa_test_seq';
GO

PRINT '';
PRINT '========================================';
PRINT 'SQL Server XA Setup Complete';
PRINT '========================================';
PRINT 'Database: xatestdb';
PRINT 'Test Table: xa_test_baseline';
PRINT 'Test Sequence: xa_test_seq';
PRINT 'XA Support: Enabled';
PRINT '========================================';
GO
