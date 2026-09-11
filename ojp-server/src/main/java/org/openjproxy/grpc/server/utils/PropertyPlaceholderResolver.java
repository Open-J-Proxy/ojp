package org.openjproxy.grpc.server.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for resolving property placeholders in strings.
 *
 * <p>Supports placeholders in the format: ${property.name}</p>
 *
 * <p>Property values are resolved in the following order:
 * <ol>
 *   <li>JVM system properties (e.g., -Dproperty.name=value)</li>
 *   <li>Environment variables (e.g., PROPERTY_NAME=value)</li>
 * </ol>
 * </p>
 *
 * <p><strong>Security:</strong> Only property names matching the allowed pattern are resolved.
 * Property names must start with a whitelisted prefix (e.g., "ojp.server.") to prevent
 * malicious property access from compromised clients.</p>
 *
 * <p>Example usage:
 * <pre>
 * String url = "jdbc:postgresql://host:5432/db?sslrootcert=${ojp.server.sslrootcert}";
 * String resolved = PropertyPlaceholderResolver.resolvePlaceholders(url);
 * // If ojp.server.sslrootcert=/etc/certs/ca.pem, then:
 * // resolved = "jdbc:postgresql://host:5432/db?sslrootcert=/etc/certs/ca.pem"
 * </pre>
 * </p>
 */
@Slf4j
public class PropertyPlaceholderResolver {

    // Pattern to match ${property.name} placeholders
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    /**
     * Pattern for validating property names.
     *
     * <p>Property names must:
     * <ul>
     *   <li>Start with "ojp.server." or "ojp.client." (whitelisted prefixes)</li>
     *   <li>Contain only alphanumeric characters, dots, hyphens, and underscores in the suffix</li>
     *   <li>Have a suffix (after prefix) between 1 and 200 characters in length</li>
     *   <li>Total length can be up to 211 characters (11 for prefix + 200 for suffix)</li>
     * </ul>
     * </p>
     *
     * <p>This prevents access to arbitrary system properties and protects against
     * potential security vulnerabilities if a client is compromised.</p>
     */
    private static final Pattern ALLOWED_PROPERTY_NAME_PATTERN =
        Pattern.compile("^(ojp\\.server\\.|ojp\\.client\\.)[a-zA-Z0-9._-]{1,200}$");

    /**
     * Resolves all placeholders in the given input string.
     *
     * <p>Placeholders are in the format ${property.name}. The property value is looked up
     * first in JVM system properties, then in environment variables (with dots converted
     * to underscores and converted to uppercase).</p>
     *
     * <p><strong>Security:</strong> Only property names matching the whitelist pattern
     * are resolved. This prevents malicious access to arbitrary system properties.</p>
     *
     * @param input The string containing placeholders to resolve
     * @return The string with all placeholders replaced by their values
     * @throws IllegalArgumentException if a placeholder cannot be resolved or contains an invalid property name
     * @throws SecurityException if a placeholder references a property name that doesn't match the whitelist
     */
    public static String resolvePlaceholders(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String placeholder = matcher.group(1); // Extract property name without ${}

            // Validate property name against whitelist
            if (!isValidPropertyName(placeholder)) {
                String errorMsg = String.format(
                    "Security violation: Property name '${%s}' does not match allowed pattern. " +
                    "Property names must start with 'ojp.server.' or 'ojp.client.' and contain only " +
                    "alphanumeric characters, dots, hyphens, and underscores. " +
                    "The suffix after the prefix must be 1-200 characters.",
                    placeholder
                );
                log.error(errorMsg);
                throw new SecurityException(errorMsg);
            }

            String value = resolveProperty(placeholder);

            if (value == null) {
                String errorMsg = String.format(
                    "Unable to resolve placeholder '${%s}'. " +
                    "Please set JVM property '-D%s=<value>' or environment variable '%s'",
                    placeholder, placeholder, propertyNameToEnvVar(placeholder)
                );
                log.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            log.debug("Resolved placeholder ${{{}}}: {} characters", placeholder, value.length());
            // Escape backslashes and dollar signs in the replacement string for regex
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Validates that a property name matches the allowed pattern.
     *
     * <p>This is a security measure to prevent access to arbitrary system properties
     * if a client is compromised. Only properties starting with whitelisted prefixes
     * are allowed.</p>
     *
     * @param propertyName The property name to validate
     * @return true if the property name is valid, false otherwise
     */
    static boolean isValidPropertyName(String propertyName) {
        if (propertyName == null || propertyName.isEmpty()) {
            return false;
        }
        return ALLOWED_PROPERTY_NAME_PATTERN.matcher(propertyName).matches();
    }

    /**
     * Checks if the given string contains any placeholders.
     *
     * @param input The string to check
     * @return true if the string contains at least one placeholder, false otherwise
     */
    public static boolean containsPlaceholders(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        return PLACEHOLDER_PATTERN.matcher(input).find();
    }

    /**
     * Resolves a single property value.
     *
     * <p>Looks up the property in the following order:
     * <ol>
     *   <li>JVM system properties</li>
     *   <li>Environment variables (with property name converted to uppercase with underscores)</li>
     * </ol>
     * </p>
     *
     * @param propertyName The property name to resolve
     * @return The property value, or null if not found
     */
    private static String resolveProperty(String propertyName) {
        // First check JVM system properties
        String value = System.getProperty(propertyName);
        if (value != null) {
            log.debug("Resolved property '{}' from JVM system property", propertyName);
            return value;
        }

        // Then check environment variables (convert dots to underscores and uppercase)
        String envKey = propertyNameToEnvVar(propertyName);
        value = System.getenv(envKey);
        if (value != null) {
            log.debug("Resolved property '{}' from environment variable '{}'", propertyName, envKey);
            return value;
        }

        log.debug("Property '{}' not found in system properties or environment variables", propertyName);
        return null;
    }

    /**
     * Converts a property name to an environment variable name.
     *
     * <p>Converts dots to underscores and converts to uppercase.
     * For example: "ojp.server.sslrootcert" becomes "OJP_SERVER_SSLROOTCERT"</p>
     *
     * @param propertyName The property name
     * @return The environment variable name
     */
    private static String propertyNameToEnvVar(String propertyName) {
        return propertyName.replace('.', '_').toUpperCase();
    }
}
