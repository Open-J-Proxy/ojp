package org.openjproxy.grpc.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HydratedResultSetMetadataTest {
    private static final int TWO_HUNDRED_FIFTY_FIVE = 255;
    private static final int TEN = 10;

    private ResultSetMetaData mockMetaData;

    @BeforeEach
    void setup() throws SQLException {
        mockMetaData = mock(ResultSetMetaData.class);

        when(mockMetaData.getColumnCount()).thenReturn(1);
        when(mockMetaData.getColumnType(1)).thenReturn(java.sql.Types.VARCHAR);
        when(mockMetaData.getColumnTypeName(1)).thenReturn("VARCHAR");
        when(mockMetaData.getColumnLabel(1)).thenReturn("label");
        when(mockMetaData.getColumnName(1)).thenReturn("name");
        when(mockMetaData.getSchemaName(1)).thenReturn("schema");
        when(mockMetaData.getTableName(1)).thenReturn("table");
        when(mockMetaData.getCatalogName(1)).thenReturn("catalog");
        when(mockMetaData.getColumnDisplaySize(1)).thenReturn(TWO_HUNDRED_FIFTY_FIVE);
        when(mockMetaData.getPrecision(1)).thenReturn(TEN);
        when(mockMetaData.getScale(1)).thenReturn(2);
        when(mockMetaData.isNullable(1)).thenReturn(ResultSetMetaData.columnNullable);
        when(mockMetaData.isAutoIncrement(1)).thenReturn(true);
        when(mockMetaData.isCaseSensitive(1)).thenReturn(true);
        when(mockMetaData.isSearchable(1)).thenReturn(true);
        when(mockMetaData.isCurrency(1)).thenReturn(false);
        when(mockMetaData.isSigned(1)).thenReturn(true);
        when(mockMetaData.isReadOnly(1)).thenReturn(false);
        when(mockMetaData.isWritable(1)).thenReturn(true);
        when(mockMetaData.isDefinitelyWritable(1)).thenReturn(false);
        when(mockMetaData.getColumnClassName(1)).thenReturn("java.lang.String");
    }

    @Test
    void testHydratedValues() throws SQLException {
        HydratedResultSetMetadata hydrated = new HydratedResultSetMetadata(mockMetaData);

        assertEquals(1, hydrated.getColumnCount());
        assertEquals("label", hydrated.getColumnLabel(1));
        assertEquals("name", hydrated.getColumnName(1));
        assertEquals("schema", hydrated.getSchemaName(1));
        assertEquals("table", hydrated.getTableName(1));
        assertEquals("catalog", hydrated.getCatalogName(1));
        assertEquals(TWO_HUNDRED_FIFTY_FIVE, hydrated.getColumnDisplaySize(1));
        assertEquals(TEN, hydrated.getPrecision(1));
        assertEquals(2, hydrated.getScale(1));
        assertEquals(ResultSetMetaData.columnNullable, hydrated.isNullable(1));
        assertTrue(hydrated.isAutoIncrement(1));
        assertTrue(hydrated.isCaseSensitive(1));
        assertTrue(hydrated.isSearchable(1));
        assertFalse(hydrated.isCurrency(1));
        assertTrue(hydrated.isSigned(1));
        assertFalse(hydrated.isReadOnly(1));
        assertTrue(hydrated.isWritable(1));
        assertFalse(hydrated.isDefinitelyWritable(1));
        assertEquals("java.lang.String", hydrated.getColumnClassName(1));
    }

    @Test
    void testInvalidColumnAccess() throws SQLException {
        HydratedResultSetMetadata hydrated = new HydratedResultSetMetadata(mockMetaData);
        assertThrows(SQLException.class, () -> hydrated.getColumnLabel(2));
    }
}
