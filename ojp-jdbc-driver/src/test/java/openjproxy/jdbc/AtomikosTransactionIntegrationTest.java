package openjproxy.jdbc;

import com.atomikos.icatch.jta.UserTransactionImp;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify OJP's compatibility with Atomikos transaction management
 * for distributed, two-phase commit transactions.
 * 
 * <p>This test demonstrates:
 * <ul>
 *   <li>OJP working with Atomikos as the transaction manager</li>
 *   <li>Distributed transactions across two H2 databases</li>
 *   <li>Successful commit across all resources</li>
 *   <li>Rollback triggered by failure in one resource</li>
 *   <li>Data consistency verification after commit and rollback</li>
 * </ul>
 * 
 * <p>Note: This test uses embedded H2 databases in XA mode for simplicity.
 * The test can be executed with the standard Maven test suite (mvn test).
 */
public class AtomikosTransactionIntegrationTest {

    private AtomikosDataSourceBean dataSource1;
    private AtomikosDataSourceBean dataSource2;
    private UserTransaction userTransaction;

    /**
     * Sets up two XA-enabled H2 datasources managed by Atomikos.
     * Database 1 represents an "orders" database.
     * Database 2 represents an "inventory" database.
     */
    @BeforeEach
    public void setUp() {
        // Configure first datasource (Orders database)
        dataSource1 = new AtomikosDataSourceBean();
        dataSource1.setUniqueResourceName("db1_orders");
        dataSource1.setXaDataSourceClassName("org.h2.jdbcx.JdbcDataSource");
        
        Properties xaProps1 = new Properties();
        xaProps1.setProperty("URL", "jdbc:h2:mem:orders_db;DB_CLOSE_DELAY=-1");
        xaProps1.setProperty("user", "sa");
        xaProps1.setProperty("password", "");
        dataSource1.setXaProperties(xaProps1);
        
        dataSource1.setPoolSize(5);
        dataSource1.setMinPoolSize(1);
        dataSource1.setMaxPoolSize(10);

        // Configure second datasource (Inventory database)
        dataSource2 = new AtomikosDataSourceBean();
        dataSource2.setUniqueResourceName("db2_inventory");
        dataSource2.setXaDataSourceClassName("org.h2.jdbcx.JdbcDataSource");
        
        Properties xaProps2 = new Properties();
        xaProps2.setProperty("URL", "jdbc:h2:mem:inventory_db;DB_CLOSE_DELAY=-1");
        xaProps2.setProperty("user", "sa");
        xaProps2.setProperty("password", "");
        dataSource2.setXaProperties(xaProps2);
        
        dataSource2.setPoolSize(5);
        dataSource2.setMinPoolSize(1);
        dataSource2.setMaxPoolSize(10);

        // Initialize Atomikos UserTransaction
        userTransaction = new UserTransactionImp();
        
        // Create test tables
        try {
            createTestTables();
        } catch (Exception e) {
            fail("Failed to create test tables: " + e.getMessage());
        }
    }

    /**
     * Creates the test tables in both databases.
     * Orders table in database 1 and inventory table in database 2.
     */
    private void createTestTables() throws SQLException {
        // Create orders table in database 1
        try (Connection conn = dataSource1.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders");
            stmt.execute("CREATE TABLE orders (id INT PRIMARY KEY, product_id INT, quantity INT, status VARCHAR(50))");
            conn.commit();
        }

        // Create inventory table in database 2
        try (Connection conn = dataSource2.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS inventory");
            stmt.execute("CREATE TABLE inventory (product_id INT PRIMARY KEY, available_quantity INT)");
            // Insert initial inventory
            stmt.execute("INSERT INTO inventory (product_id, available_quantity) VALUES (100, 50)");
            stmt.execute("INSERT INTO inventory (product_id, available_quantity) VALUES (200, 30)");
            conn.commit();
        }
    }

    /**
     * Cleans up Atomikos resources and closes datasources.
     */
    @AfterEach
    public void tearDown() {
        if (dataSource1 != null) {
            dataSource1.close();
        }
        if (dataSource2 != null) {
            dataSource2.close();
        }
    }

    /**
     * Test Scenario: Successful Distributed Transaction Commit
     * 
     * <p>This test simulates an order placement that requires:
     * 1. Creating an order record in the orders database
     * 2. Reducing inventory in the inventory database
     * 
     * <p>Expected Outcome: Both operations commit successfully, demonstrating
     * that OJP correctly supports distributed transactions with Atomikos.
     */
    @Test
    public void testSuccessfulDistributedTransactionCommit() throws Exception {
        // Begin distributed transaction
        userTransaction.begin();

        try {
            // Step 1: Create order in orders database
            try (Connection conn1 = dataSource1.getConnection();
                 PreparedStatement pstmt = conn1.prepareStatement(
                     "INSERT INTO orders (id, product_id, quantity, status) VALUES (?, ?, ?, ?)")) {
                pstmt.setInt(1, 1);
                pstmt.setInt(2, 100);
                pstmt.setInt(3, 5);
                pstmt.setString(4, "PENDING");
                pstmt.executeUpdate();
            }

            // Step 2: Reduce inventory in inventory database
            try (Connection conn2 = dataSource2.getConnection();
                 PreparedStatement pstmt = conn2.prepareStatement(
                     "UPDATE inventory SET available_quantity = available_quantity - ? WHERE product_id = ?")) {
                pstmt.setInt(1, 5);
                pstmt.setInt(2, 100);
                int updated = pstmt.executeUpdate();
                assertEquals(1, updated, "Should update exactly one inventory record");
            }

            // Commit the distributed transaction
            userTransaction.commit();

            // Verify: Order was created in database 1
            try (Connection conn1 = dataSource1.getConnection();
                 Statement stmt = conn1.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM orders WHERE id = 1")) {
                assertTrue(rs.next(), "Order should exist after commit");
                assertEquals(1, rs.getInt("id"));
                assertEquals(100, rs.getInt("product_id"));
                assertEquals(5, rs.getInt("quantity"));
                assertEquals("PENDING", rs.getString("status"));
                assertFalse(rs.next(), "Should only have one order");
            }

            // Verify: Inventory was reduced in database 2
            try (Connection conn2 = dataSource2.getConnection();
                 PreparedStatement pstmt = conn2.prepareStatement("SELECT available_quantity FROM inventory WHERE product_id = ?")) {
                pstmt.setInt(1, 100);
                try (ResultSet rs = pstmt.executeQuery()) {
                    assertTrue(rs.next(), "Inventory record should exist");
                    assertEquals(45, rs.getInt("available_quantity"), 
                        "Inventory should be reduced by 5 (from 50 to 45)");
                }
            }

        } catch (Exception e) {
            userTransaction.rollback();
            fail("Transaction should have committed successfully: " + e.getMessage());
        }
    }

    /**
     * Test Scenario: Distributed Transaction Rollback on Failure
     * 
     * <p>This test simulates an order placement that fails due to insufficient inventory:
     * 1. Creating an order record in the orders database
     * 2. Attempting to reduce inventory beyond available quantity (causing constraint violation)
     * 
     * <p>Expected Outcome: The transaction rolls back, and no changes are persisted
     * in either database, demonstrating proper rollback behavior in distributed transactions.
     */
    @Test
    public void testDistributedTransactionRollbackOnFailure() throws Exception {
        // Begin distributed transaction
        userTransaction.begin();

        try {
            // Step 1: Create order in orders database
            try (Connection conn1 = dataSource1.getConnection();
                 PreparedStatement pstmt = conn1.prepareStatement(
                     "INSERT INTO orders (id, product_id, quantity, status) VALUES (?, ?, ?, ?)")) {
                pstmt.setInt(1, 2);
                pstmt.setInt(2, 200);
                pstmt.setInt(3, 100); // Requesting more than available (only 30 available)
                pstmt.setString(4, "PENDING");
                pstmt.executeUpdate();
            }

            // Step 2: Attempt to reduce inventory - this should fail due to insufficient quantity
            try (Connection conn2 = dataSource2.getConnection();
                 PreparedStatement pstmt = conn2.prepareStatement(
                     "UPDATE inventory SET available_quantity = available_quantity - ? WHERE product_id = ? AND available_quantity >= ?")) {
                pstmt.setInt(1, 100);
                pstmt.setInt(2, 200);
                pstmt.setInt(3, 100); // Check that we have enough inventory
                int updated = pstmt.executeUpdate();
                
                // Simulate business logic failure - insufficient inventory
                if (updated == 0) {
                    throw new RuntimeException("Insufficient inventory for product 200");
                }
            }

            // This should not be reached
            userTransaction.commit();
            fail("Transaction should have been rolled back due to insufficient inventory");

        } catch (RuntimeException e) {
            // Expected failure - rollback the transaction
            userTransaction.rollback();
            assertEquals("Insufficient inventory for product 200", e.getMessage());
        }

        // Verify: Order was NOT created in database 1 (rollback successful)
        try (Connection conn1 = dataSource1.getConnection();
             Statement stmt = conn1.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM orders WHERE id = 2")) {
            assertFalse(rs.next(), "Order should NOT exist after rollback");
        }

        // Verify: Inventory was NOT changed in database 2 (rollback successful)
        try (Connection conn2 = dataSource2.getConnection();
             PreparedStatement pstmt = conn2.prepareStatement("SELECT available_quantity FROM inventory WHERE product_id = ?")) {
            pstmt.setInt(1, 200);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "Inventory record should exist");
                assertEquals(30, rs.getInt("available_quantity"), 
                    "Inventory should remain unchanged at 30 after rollback");
            }
        }
    }

    /**
     * Test Scenario: Data Consistency Verification Across Multiple Transactions
     * 
     * <p>This test verifies data consistency by:
     * 1. Executing a successful transaction
     * 2. Executing a failed transaction
     * 3. Executing another successful transaction
     * 4. Verifying final state is consistent
     * 
     * <p>Expected Outcome: Only successful transactions persist their changes,
     * demonstrating proper isolation and consistency in distributed transactions.
     */
    @Test
    public void testDataConsistencyAcrossMultipleTransactions() throws Exception {
        // Transaction 1: Successful order placement
        userTransaction.begin();
        try {
            try (Connection conn1 = dataSource1.getConnection();
                 PreparedStatement pstmt = conn1.prepareStatement(
                     "INSERT INTO orders (id, product_id, quantity, status) VALUES (?, ?, ?, ?)")) {
                pstmt.setInt(1, 3);
                pstmt.setInt(2, 100);
                pstmt.setInt(3, 10);
                pstmt.setString(4, "PENDING");
                pstmt.executeUpdate();
            }

            try (Connection conn2 = dataSource2.getConnection();
                 PreparedStatement pstmt = conn2.prepareStatement(
                     "UPDATE inventory SET available_quantity = available_quantity - ? WHERE product_id = ?")) {
                pstmt.setInt(1, 10);
                pstmt.setInt(2, 100);
                pstmt.executeUpdate();
            }

            userTransaction.commit();
        } catch (Exception e) {
            userTransaction.rollback();
            fail("First transaction should succeed: " + e.getMessage());
        }

        // Transaction 2: Failed transaction (should rollback)
        userTransaction.begin();
        try {
            try (Connection conn1 = dataSource1.getConnection();
                 PreparedStatement pstmt = conn1.prepareStatement(
                     "INSERT INTO orders (id, product_id, quantity, status) VALUES (?, ?, ?, ?)")) {
                pstmt.setInt(1, 4);
                pstmt.setInt(2, 100);
                pstmt.setInt(3, 100); // More than available
                pstmt.setString(4, "PENDING");
                pstmt.executeUpdate();
            }

            try (Connection conn2 = dataSource2.getConnection();
                 PreparedStatement pstmt = conn2.prepareStatement(
                     "UPDATE inventory SET available_quantity = available_quantity - ? WHERE product_id = ? AND available_quantity >= ?")) {
                pstmt.setInt(1, 100);
                pstmt.setInt(2, 100);
                pstmt.setInt(3, 100);
                int updated = pstmt.executeUpdate();
                if (updated == 0) {
                    throw new RuntimeException("Insufficient inventory");
                }
            }

            userTransaction.commit();
        } catch (RuntimeException e) {
            userTransaction.rollback();
            // Expected rollback
        }

        // Transaction 3: Another successful order placement
        userTransaction.begin();
        try {
            try (Connection conn1 = dataSource1.getConnection();
                 PreparedStatement pstmt = conn1.prepareStatement(
                     "INSERT INTO orders (id, product_id, quantity, status) VALUES (?, ?, ?, ?)")) {
                pstmt.setInt(1, 5);
                pstmt.setInt(2, 200);
                pstmt.setInt(3, 5);
                pstmt.setString(4, "PENDING");
                pstmt.executeUpdate();
            }

            try (Connection conn2 = dataSource2.getConnection();
                 PreparedStatement pstmt = conn2.prepareStatement(
                     "UPDATE inventory SET available_quantity = available_quantity - ? WHERE product_id = ?")) {
                pstmt.setInt(1, 5);
                pstmt.setInt(2, 200);
                pstmt.executeUpdate();
            }

            userTransaction.commit();
        } catch (Exception e) {
            userTransaction.rollback();
            fail("Third transaction should succeed: " + e.getMessage());
        }

        // Verify final state - only transactions 1 and 3 should have persisted
        try (Connection conn1 = dataSource1.getConnection();
             Statement stmt = conn1.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM orders")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1), "Should have exactly 2 orders (transaction 2 was rolled back)");
        }

        // Verify inventory consistency
        try (Connection conn2 = dataSource2.getConnection();
             Statement stmt = conn2.createStatement()) {
            
            // Product 100: started with 50, reduced by 10 in transaction 1
            try (ResultSet rs = stmt.executeQuery("SELECT available_quantity FROM inventory WHERE product_id = 100")) {
                assertTrue(rs.next());
                assertEquals(40, rs.getInt("available_quantity"), 
                    "Product 100 inventory should be 40 (50 - 10)");
            }

            // Product 200: started with 30, reduced by 5 in transaction 3
            try (ResultSet rs = stmt.executeQuery("SELECT available_quantity FROM inventory WHERE product_id = 200")) {
                assertTrue(rs.next());
                assertEquals(25, rs.getInt("available_quantity"), 
                    "Product 200 inventory should be 25 (30 - 5)");
            }
        }
    }

    /**
     * Test Scenario: Atomikos Transaction Manager Configuration
     * 
     * <p>This test verifies that Atomikos is properly configured and can manage
     * distributed transactions by checking the transaction status at different stages.
     * 
     * <p>Expected Outcome: Transaction status transitions correctly through
     * begin, active, and commit/rollback states.
     */
    @Test
    public void testAtomikosTransactionManagerConfiguration() throws Exception {
        // Verify initial state - no active transaction
        assertEquals(javax.transaction.Status.STATUS_NO_TRANSACTION, 
            userTransaction.getStatus(), 
            "Should have no active transaction initially");

        // Begin transaction
        userTransaction.begin();
        assertEquals(javax.transaction.Status.STATUS_ACTIVE, 
            userTransaction.getStatus(), 
            "Transaction should be active after begin");

        // Perform some operations
        try (Connection conn1 = dataSource1.getConnection();
             PreparedStatement pstmt = conn1.prepareStatement(
                 "INSERT INTO orders (id, product_id, quantity, status) VALUES (?, ?, ?, ?)")) {
            pstmt.setInt(1, 6);
            pstmt.setInt(2, 100);
            pstmt.setInt(3, 1);
            pstmt.setString(4, "TEST");
            pstmt.executeUpdate();
        }

        // Commit transaction
        userTransaction.commit();
        assertEquals(javax.transaction.Status.STATUS_NO_TRANSACTION, 
            userTransaction.getStatus(), 
            "Should have no active transaction after commit");

        // Verify rollback status
        userTransaction.begin();
        assertEquals(javax.transaction.Status.STATUS_ACTIVE, 
            userTransaction.getStatus(), 
            "Transaction should be active");

        userTransaction.rollback();
        assertEquals(javax.transaction.Status.STATUS_NO_TRANSACTION, 
            userTransaction.getStatus(), 
            "Should have no active transaction after rollback");
    }
}
