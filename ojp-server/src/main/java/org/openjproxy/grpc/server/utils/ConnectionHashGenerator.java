package org.openjproxy.grpc.server.utils;

import com.openjproxy.grpc.ConnectionDetails;

import java.security.MessageDigest;
import java.util.Properties;

import static org.openjproxy.grpc.SerializationHandler.deserialize;
import static org.openjproxy.grpc.server.Constants.SHA_256;

/**
 * Utility class for generating connection hashes.
 * Extracted from StatementServiceImpl to improve modularity.
 * Updated to include dataSource name in hash for multi-datasource support.
 */
public class ConnectionHashGenerator {

    /**
     * Generates a hash for connection details using SHA-256.
     * Now includes dataSource name to ensure separate pools for different dataSources
     * even when using the same connection parameters.
     *
     * @param connectionDetails The connection details to hash
     * @return Hash string for the connection details
     * @throws RuntimeException if hashing fails
     */
    public static String hashConnectionDetails(ConnectionDetails connectionDetails) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(SHA_256);
            
            // Concatenate all parts at once: URL, user, password, and dataSource name
            String hashInput = connectionDetails.getUrl() + connectionDetails.getUser() + connectionDetails.getPassword()+
                    extractDataSourceName(connectionDetails);
            
            messageDigest.update(hashInput.getBytes());
            return new String(messageDigest.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate connection hash", e);
        }
    }
    
    /**
     * Extracts the dataSource name from connection details properties.
     * Returns "default" if no dataSource name is specified.
     */
    private static String extractDataSourceName(ConnectionDetails connectionDetails) {
        if (connectionDetails.getProperties().isEmpty()) {
            return "default";
        }
        
        try {
            Properties properties = deserialize(connectionDetails.getProperties().toByteArray(), Properties.class);
            return properties.getProperty("ojp.datasource.name", "default");
        } catch (Exception e) {
            // If we can't deserialize properties, fall back to default
            return "default";
        }
    }
}