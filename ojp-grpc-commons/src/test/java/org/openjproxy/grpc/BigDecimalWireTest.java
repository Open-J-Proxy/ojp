package org.openjproxy.grpc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BigDecimalWire serialization/deserialization.
 */
class BigDecimalWireTest {

    @Test
    void testNull() throws IOException {
        assertRoundTrip(null);
    }

    @Test
    void testZero() throws IOException {
        assertRoundTrip(BigDecimal.ZERO);
    }

    @Test
    void testPositiveInteger() throws IOException {
        assertRoundTrip(new BigDecimal("123"));
    }

    @Test
    void testNegativeInteger() throws IOException {
        assertRoundTrip(new BigDecimal("-456"));
    }

    @Test
    void testPositiveDecimal() throws IOException {
        assertRoundTrip(new BigDecimal("123.456"));
    }

    @Test
    void testNegativeDecimal() throws IOException {
        assertRoundTrip(new BigDecimal("-789.012"));
    }

    @Test
    void testSmallScale() throws IOException {
        assertRoundTrip(new BigDecimal("1.23"));
    }

    @Test
    void testLargePositiveScale() throws IOException {
        // Scale of 10 means 10 digits after decimal point
        assertRoundTrip(new BigDecimal("1.2345678901"));
    }

    @Test
    void testNegativeScale() throws IOException {
        // Negative scale means significant digits before decimal
        // BigDecimal with unscaled value 123 and scale -2 = 12300
        BigDecimal value = new BigDecimal(new BigInteger("123"), -2);
        assertRoundTrip(value);
        // Verify it equals the expected value (they may have different string
        // representations)
        assertEquals(0, new BigDecimal("12300").compareTo(value));
    }

    @Test
    void testVeryLargeUnscaledValue() throws IOException {
        // Test with a very large BigInteger (> 64-bit)
        String largeNumber = "123456789012345678901234567890123456789012345678901234567890";
        assertRoundTrip(new BigDecimal(largeNumber));
    }

    @Test
    void testVeryLargeNegativeUnscaledValue() throws IOException {
        // Test with a very large negative BigInteger
        String largeNumber = "-987654321098765432109876543210987654321098765432109876543210";
        assertRoundTrip(new BigDecimal(largeNumber));
    }

    @Test
    void testVerySmallDecimal() throws IOException {
        // Test with many decimal places
        assertRoundTrip(new BigDecimal("0.000000000000000001"));
    }

    @Test
    void testScientificNotation() throws IOException {
        // BigDecimal can be created from scientific notation
        assertRoundTrip(new BigDecimal("1.23E+10"));
        assertRoundTrip(new BigDecimal("4.56E-8"));
    }

    @Test
    void testOne() throws IOException {
        assertRoundTrip(BigDecimal.ONE);
    }

    @Test
    void testTen() throws IOException {
        assertRoundTrip(BigDecimal.TEN);
    }

    @Test
    void testSpecialScales() throws IOException {
        // Test with scale = 0
        assertRoundTrip(new BigDecimal(new BigInteger("12345"), 0));

        // Test with large positive scale
        assertRoundTrip(new BigDecimal(new BigInteger("12345"), 100));

        // Test with large negative scale
        assertRoundTrip(new BigDecimal(new BigInteger("12345"), -50));
    }

    @Test
    void testNegativeZeroScale() throws IOException {
        // BigDecimal("-0") should be handled properly
        BigDecimal negZero = new BigDecimal("0.00").negate();
        assertRoundTrip(negZero);
    }

    @Test
    void testInvalidNegativeLength() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Write presence flag
        dos.writeByte(1);
        // Write invalid negative length
        dos.writeInt(-1);
        dos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);

        assertThrows(IOException.class, () -> BigDecimalWire.readBigDecimal(dis));
    }

    @Test
    void testExcessiveLength() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Write presence flag
        dos.writeByte(1);
        // Write excessive length (> MAX_UNSCALED_LENGTH)
        dos.writeInt(20_000_000);
        dos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);

        assertThrows(IOException.class, () -> BigDecimalWire.readBigDecimal(dis));
    }

    @Test
    void testInvalidUnscaledValue() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Write presence flag
        dos.writeByte(1);
        // Write invalid unscaled value (not a number)
        String invalidValue = "not-a-number";
        byte[] bytes = invalidValue.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(bytes.length);
        dos.write(bytes);
        dos.writeInt(2); // scale
        dos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);

        assertThrows(IOException.class, () -> BigDecimalWire.readBigDecimal(dis));
    }

    @Test
    void testMultipleValues() throws IOException {
        // Test writing and reading multiple BigDecimal values in sequence
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        BigDecimal[] values = {
                null,
                BigDecimal.ZERO,
                new BigDecimal("123.456"),
                new BigDecimal("-789.012"),
                new BigDecimal("999999999999999999999999")
        };

        // Write all values
        for (BigDecimal value : values) {
            BigDecimalWire.writeBigDecimal(dos, value);
        }
        dos.flush();

        // Read all values back
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);

        for (BigDecimal expected : values) {
            BigDecimal actual = BigDecimalWire.readBigDecimal(dis);
            assertEquals(expected, actual);
        }
    }

    /**
     * Helper method to test serialization roundtrip.
     */
    private void assertRoundTrip(BigDecimal original) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Write
        BigDecimalWire.writeBigDecimal(dos, original);
        dos.flush();

        // Read
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        DataInputStream dis = new DataInputStream(bais);
        BigDecimal result = BigDecimalWire.readBigDecimal(dis);

        // Assert
        assertEquals(original, result);

        // If not null, also verify scale and unscaled value match
        if (original != null) {
            assertNotNull(result);
            assertEquals(original.scale(), result.scale());
            assertEquals(original.unscaledValue(), result.unscaledValue());
        }
    }
}
