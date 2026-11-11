package org.openjproxy.jdbc;

import lombok.extern.slf4j.Slf4j;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.sql.SQLException;

/**
 * Stub implementation of {@link java.sql.SQLXML} for the OJP JDBC driver.
 *
 * <p><strong>Status: not yet implemented.</strong> All methods return empty/null values and
 * log a debug message at the moment they are called.
 *
 * <p><strong>JDBC specification reference:</strong> JDBC 4.2 specification, Chapter 16
 * "Advanced Data Types", section 16.3 — {@code java.sql.SQLXML} is the Java mapping for the
 * SQL standard {@code XML} type, introduced in JDBC 4.0 (Java SE 6).
 *
 * <p><strong>Known use-cases that require a full implementation:</strong>
 * <ul>
 *   <li>Reading SQL {@code XML} / {@code XMLTYPE} columns from PostgreSQL, Oracle
 *       ({@code XMLType}), and SQL Server ({@code xml}) via
 *       {@link java.sql.ResultSet#getSQLXML(int)}.</li>
 *   <li>Writing XML values to a {@link java.sql.PreparedStatement} through
 *       {@link java.sql.PreparedStatement#setSQLXML(int, java.sql.SQLXML)}.</li>
 *   <li>Applications that exchange XML documents with the database — for example,
 *       configuration stores, document-centric schemas, or stored procedures that
 *       accept or return XML.</li>
 *   <li>XSLT / XQuery pipelines that retrieve an XML source via
 *       {@link java.sql.SQLXML#getSource(Class)} and transform it with
 *       {@code javax.xml.transform}.</li>
 * </ul>
 *
 * <p>When implementing this class, the XML payload will need to be transported over the
 * OJP gRPC protocol. The {@code .proto} contract in {@code ojp-grpc-contract} will likely
 * need a new field or message type (e.g., a {@code bytes} or {@code string} field) to carry
 * the serialised XML document.
 */
@Slf4j
public class SQLXML implements java.sql.SQLXML {

    @Override
    public void free() throws SQLException {
        log.debug("free called");
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        log.debug("getBinaryStream called");
        return null;
    }

    @Override
    public OutputStream setBinaryStream() throws SQLException {
        log.debug("setBinaryStream called");
        return null;
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        log.debug("getCharacterStream called");
        return null;
    }

    @Override
    public Writer setCharacterStream() throws SQLException {
        log.debug("setCharacterStream called");
        return null;
    }

    @Override
    public String getString() throws SQLException {
        log.debug("getString called");
        return "";
    }

    @Override
    public void setString(String value) throws SQLException {
        log.debug("setString called: {}", value);
    }

    @Override
    public <T extends Source> T getSource(Class<T> sourceClass) throws SQLException {
        log.debug("getSource called: {}", sourceClass);
        return null;
    }

    @Override
    public <T extends Result> T setResult(Class<T> resultClass) throws SQLException {
        log.debug("setResult called: {}", resultClass);
        return null;
    }
}
