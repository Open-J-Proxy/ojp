package org.openjdbcproxy.grpc.server.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for DatabaseNameExtractor utility.
 */
public class DatabaseNameExtractorTest {

    @Test
    public void testPostgreSQLUrls() {
        assertEquals("mydatabase", DatabaseNameExtractor.extractDatabaseName("jdbc:postgresql://localhost:5432/mydatabase"));
        assertEquals("testdb", DatabaseNameExtractor.extractDatabaseName("jdbc:postgresql://hostname/testdb?ssl=true"));
        assertEquals("app_db", DatabaseNameExtractor.extractDatabaseName("jdbc:postgresql://127.0.0.1:5432/app_db"));
        assertEquals("production", DatabaseNameExtractor.extractDatabaseName("jdbc:postgresql://db.example.com:5432/production?sslmode=require&user=admin"));
    }

    @Test
    public void testMySQLUrls() {
        assertEquals("mydb", DatabaseNameExtractor.extractDatabaseName("jdbc:mysql://localhost:3306/mydb"));
        assertEquals("test_database", DatabaseNameExtractor.extractDatabaseName("jdbc:mysql://hostname/test_database?useSSL=false"));
        assertEquals("app", DatabaseNameExtractor.extractDatabaseName("jdbc:mysql://127.0.0.1:3306/app?serverTimezone=UTC"));
    }

    @Test
    public void testMariaDBUrls() {
        assertEquals("mariadb_test", DatabaseNameExtractor.extractDatabaseName("jdbc:mariadb://localhost:3306/mariadb_test"));
        assertEquals("production_db", DatabaseNameExtractor.extractDatabaseName("jdbc:mariadb://hostname/production_db?useSSL=true"));
    }

    @Test
    public void testOracleUrls() {
        // Service name format
        assertEquals("XEPDB1", DatabaseNameExtractor.extractDatabaseName("jdbc:oracle:thin:@localhost:1521/XEPDB1"));
        assertEquals("myservice", DatabaseNameExtractor.extractDatabaseName("jdbc:oracle:thin:@db.example.com:1521/myservice"));
        
        // SID format
        assertEquals("XE", DatabaseNameExtractor.extractDatabaseName("jdbc:oracle:thin:@localhost:1521:XE"));
        assertEquals("ORCL", DatabaseNameExtractor.extractDatabaseName("jdbc:oracle:thin:@hostname:1521:ORCL"));
    }

    @Test
    public void testSQLServerUrls() {
        assertEquals("MyDatabase", DatabaseNameExtractor.extractDatabaseName("jdbc:sqlserver://localhost:1433;databaseName=MyDatabase"));
        assertEquals("TestDB", DatabaseNameExtractor.extractDatabaseName("jdbc:sqlserver://hostname\\instance;databaseName=TestDB;encrypt=true"));
        assertEquals("ProductionDB", DatabaseNameExtractor.extractDatabaseName("jdbc:sqlserver://server:1433;databaseName=ProductionDB;user=admin;password=secret"));
    }

    @Test
    public void testDB2Urls() {
        assertEquals("sample", DatabaseNameExtractor.extractDatabaseName("jdbc:db2://localhost:50000/sample"));
        assertEquals("testdb", DatabaseNameExtractor.extractDatabaseName("jdbc:db2://hostname:50000/testdb"));
    }

    @Test
    public void testH2Urls() {
        // Memory database
        assertEquals("testdb", DatabaseNameExtractor.extractDatabaseName("jdbc:h2:mem:testdb"));
        assertEquals("test", DatabaseNameExtractor.extractDatabaseName("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"));
        
        // File database
        assertEquals("mydb", DatabaseNameExtractor.extractDatabaseName("jdbc:h2:file:./data/mydb"));
        assertEquals("testfile", DatabaseNameExtractor.extractDatabaseName("jdbc:h2:file:/path/to/testfile"));
        assertEquals("database", DatabaseNameExtractor.extractDatabaseName("jdbc:h2:file:C:\\data\\database"));
    }

    @Test
    public void testEdgeCases() {
        // Null and empty strings
        assertNull(DatabaseNameExtractor.extractDatabaseName(null));
        assertNull(DatabaseNameExtractor.extractDatabaseName(""));
        assertNull(DatabaseNameExtractor.extractDatabaseName("   "));
        
        // Unsupported database types
        assertNull(DatabaseNameExtractor.extractDatabaseName("jdbc:derby://localhost:1527/mydb"));
        assertNull(DatabaseNameExtractor.extractDatabaseName("jdbc:hsqldb:hsql://localhost/testdb"));
        
        // Malformed URLs
        assertNull(DatabaseNameExtractor.extractDatabaseName("jdbc:postgresql://"));
        assertNull(DatabaseNameExtractor.extractDatabaseName("jdbc:mysql://hostname"));
        assertNull(DatabaseNameExtractor.extractDatabaseName("not-a-jdbc-url"));
    }

    @Test
    public void testCaseInsensitivity() {
        // URLs should be handled case-insensitively for protocol detection
        assertEquals("mydatabase", DatabaseNameExtractor.extractDatabaseName("JDBC:POSTGRESQL://localhost:5432/mydatabase"));
        assertEquals("testdb", DatabaseNameExtractor.extractDatabaseName("Jdbc:MySQL://hostname/testdb"));
    }

    @Test
    public void testComplexParameters() {
        // URLs with multiple parameters
        assertEquals("mydb", DatabaseNameExtractor.extractDatabaseName("jdbc:postgresql://localhost:5432/mydb?ssl=true&sslmode=require&user=admin&password=secret"));
        assertEquals("testdb", DatabaseNameExtractor.extractDatabaseName("jdbc:mysql://localhost:3306/testdb?useSSL=false&serverTimezone=UTC&characterEncoding=utf8"));
    }

    @Test
    public void testSpecialCharacters() {
        // Database names with underscores, numbers, etc.
        assertEquals("my_database_123", DatabaseNameExtractor.extractDatabaseName("jdbc:postgresql://localhost:5432/my_database_123"));
        assertEquals("test-db", DatabaseNameExtractor.extractDatabaseName("jdbc:mysql://localhost:3306/test-db"));
        assertEquals("db2021", DatabaseNameExtractor.extractDatabaseName("jdbc:mariadb://localhost:3306/db2021"));
    }
}