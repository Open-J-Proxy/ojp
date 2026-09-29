import { fromParameterValue, OjpTypedParam, toParameterProto, encodeBigDecimalWire as realEncodeBigDecimalWire } from '../src/client/valueMapper';

describe('valueMapper', () => {
  it('should convert null to PT_NULL (with the java.sql.Types.NULL code)', () => {
    const proto = toParameterProto(1, null);
    expect(proto.type).toBe('PT_NULL');
    expect(proto.values[0].intValue).toBe(0);
  });

  it('should convert boolean to PT_BOOLEAN and back', () => {
    const proto = toParameterProto(1, true);
    expect(proto.type).toBe('PT_BOOLEAN');
    expect(fromParameterValue(proto.values[0])).toBe(true);
  });

  it('should convert a small integer to PT_INT', () => {
    const proto = toParameterProto(1, 42);
    expect(proto.type).toBe('PT_INT');
    expect(fromParameterValue(proto.values[0])).toBe(42);
  });

  it('should convert a decimal number to PT_DOUBLE', () => {
    const proto = toParameterProto(1, 3.14);
    expect(proto.type).toBe('PT_DOUBLE');
    expect(fromParameterValue(proto.values[0])).toBeCloseTo(3.14);
  });

  it('should convert bigint to PT_LONG and back as bigint', () => {
    const proto = toParameterProto(1, 9007199254740993n);
    expect(proto.type).toBe('PT_LONG');
    expect(fromParameterValue(proto.values[0])).toBe(9007199254740993n);
  });

  it('should convert string to PT_STRING', () => {
    const proto = toParameterProto(1, 'hello');
    expect(proto.type).toBe('PT_STRING');
    expect(fromParameterValue(proto.values[0])).toBe('hello');
  });

  it('should convert Buffer to PT_BYTES', () => {
    const buf = Buffer.from('abc');
    const proto = toParameterProto(1, buf);
    expect(proto.type).toBe('PT_BYTES');
    expect(fromParameterValue(proto.values[0])).toEqual(buf);
  });

  it('should convert Date to PT_TIMESTAMP and back preserving the instant', () => {
    const date = new Date('2026-01-15T10:30:00.000Z');
    const proto = toParameterProto(1, date);
    expect(proto.type).toBe('PT_TIMESTAMP');
    const roundTrip = fromParameterValue(proto.values[0]) as Date;
    expect(roundTrip.getTime()).toBe(date.getTime());
  });

  it('should throw an error for unsupported types', () => {
    expect(() => toParameterProto(1, Symbol('x'))).toThrow(TypeError);
  });

  describe('BigDecimalWire decoding (bytesValue coming from DECIMAL/NUMERIC/MONEY columns)', () => {
    /** Builds the same binary layout the ojp-server uses to serialize a BigDecimal. */
    function encodeBigDecimalWire(unscaled: string, scale: number): Buffer {
      const unscaledBytes = Buffer.from(unscaled, 'utf8');
      const buf = Buffer.alloc(1 + 4 + unscaledBytes.length + 4);
      let offset = 0;
      buf.writeUInt8(1, offset); offset += 1;
      buf.writeInt32BE(unscaledBytes.length, offset); offset += 4;
      unscaledBytes.copy(buf, offset); offset += unscaledBytes.length;
      buf.writeInt32BE(scale, offset);
      return buf;
    }

    it('should decode a positive BigDecimal with positive scale (e.g. 1234.56)', () => {
      const wire = encodeBigDecimalWire('123456', 2);
      expect(fromParameterValue({ bytesValue: wire })).toBe('1234.56');
    });

    it('should decode a negative BigDecimal', () => {
      const wire = encodeBigDecimalWire('-123456', 2);
      expect(fromParameterValue({ bytesValue: wire })).toBe('-1234.56');
    });

    it('should decode a BigDecimal with zero scale (integer)', () => {
      const wire = encodeBigDecimalWire('42', 0);
      expect(fromParameterValue({ bytesValue: wire })).toBe('42');
    });

    it('should decode a BigDecimal with negative scale (e.g. 4.2E+3 -> 4200)', () => {
      const wire = encodeBigDecimalWire('42', -2);
      expect(fromParameterValue({ bytesValue: wire })).toBe('4200');
    });

    it('should decode a fractional BigDecimal smaller than 1 (left-padding with zeros)', () => {
      const wire = encodeBigDecimalWire('5', 3);
      expect(fromParameterValue({ bytesValue: wire })).toBe('0.005');
    });

    it('should return raw bytes when the buffer does not match the BigDecimalWire layout', () => {
      const buf = Buffer.from('abc');
      expect(fromParameterValue({ bytesValue: buf })).toEqual(buf);
    });
  });

  describe('OjpTypedParam (explicit type hints, used by dialect shims such as the SQL Server adapter)', () => {
    it('should fall back to PT_NULL regardless of the hinted type when the value is null', () => {
      const proto = toParameterProto(1, new OjpTypedParam(null, 'PT_BIG_DECIMAL'));
      expect(proto.type).toBe('PT_NULL');
      expect(proto.values[0].intValue).toBe(0);
    });

    it('should honor PT_LONG for a JS number outside the 32-bit range', () => {
      const proto = toParameterProto(1, new OjpTypedParam(5000000000, 'PT_LONG'));
      expect(proto.type).toBe('PT_LONG');
      expect(fromParameterValue(proto.values[0])).toBe(5000000000n);
    });

    it('should honor PT_SHORT and PT_BYTE by encoding as intValue', () => {
      const shortProto = toParameterProto(1, new OjpTypedParam(300, 'PT_SHORT'));
      expect(shortProto.type).toBe('PT_SHORT');
      expect(shortProto.values[0].intValue).toBe(300);

      const byteProto = toParameterProto(1, new OjpTypedParam(7, 'PT_BYTE'));
      expect(byteProto.type).toBe('PT_BYTE');
      expect(byteProto.values[0].intValue).toBe(7);
    });

    it('should honor PT_N_STRING (SQL Server NVARCHAR) as stringValue', () => {
      const proto = toParameterProto(1, new OjpTypedParam('nvarchar text', 'PT_N_STRING'));
      expect(proto.type).toBe('PT_N_STRING');
      expect(fromParameterValue(proto.values[0])).toBe('nvarchar text');
    });

    it('should honor PT_DATE (date-only) instead of the default PT_TIMESTAMP inference for a Date value', () => {
      const proto = toParameterProto(1, new OjpTypedParam(new Date('2026-03-10T00:00:00.000Z'), 'PT_DATE'));
      expect(proto.type).toBe('PT_DATE');
      expect(proto.values[0].dateValue).toEqual({ year: 2026, month: 3, day: 10 });
    });

    it('should honor PT_TIME (time-only) for a Date value', () => {
      const proto = toParameterProto(1, new OjpTypedParam(new Date('1970-01-01T13:45:30.000Z'), 'PT_TIME'));
      expect(proto.type).toBe('PT_TIME');
      expect(proto.values[0].timeValue).toEqual({ hours: 13, minutes: 45, seconds: 30, nanos: 0 });
    });

    it('should honor PT_TIMESTAMP with a custom temporalType (e.g. SQL Server DATETIMEOFFSET)', () => {
      const date = new Date('2026-01-15T10:30:00.000Z');
      const proto = toParameterProto(1, new OjpTypedParam(date, 'PT_TIMESTAMP', 'TEMPORAL_TYPE_OFFSET_DATE_TIME'));
      expect(proto.type).toBe('PT_TIMESTAMP');
      expect(proto.values[0].timestampValue?.originalType).toBe('TEMPORAL_TYPE_OFFSET_DATE_TIME');
      const roundTrip = fromParameterValue(proto.values[0]) as Date;
      expect(roundTrip.getTime()).toBe(date.getTime());
    });

    it('should honor PT_BIG_DECIMAL preserving exact precision (unlike plain-number PT_DOUBLE inference)', () => {
      const proto = toParameterProto(1, new OjpTypedParam('1234.56', 'PT_BIG_DECIMAL'));
      expect(proto.type).toBe('PT_BIG_DECIMAL');
      expect(fromParameterValue(proto.values[0])).toBe('1234.56');
    });

    it('should fall back to value-based inference for hints without dedicated handling (e.g. PT_URL)', () => {
      const proto = toParameterProto(1, new OjpTypedParam('hello', 'PT_URL'));
      // PT_URL has no dedicated encoder above; falls back to plain string inference (PT_STRING).
      expect(proto.type).toBe('PT_STRING');
      expect(fromParameterValue(proto.values[0])).toBe('hello');
    });
  });

  describe('encodeBigDecimalWire (write path for PT_BIG_DECIMAL, mirrors BigDecimalWire.writeBigDecimal)', () => {
    it('should round-trip a positive decimal string through encode -> decode', () => {
      const wire = realEncodeBigDecimalWire('1234.56');
      expect(fromParameterValue({ bytesValue: wire })).toBe('1234.56');
    });

    it('should round-trip a negative decimal string', () => {
      const wire = realEncodeBigDecimalWire('-0.005');
      expect(fromParameterValue({ bytesValue: wire })).toBe('-0.005');
    });

    it('should round-trip an integer-only string with scale 0', () => {
      const wire = realEncodeBigDecimalWire('42');
      expect(fromParameterValue({ bytesValue: wire })).toBe('42');
    });

    it('should round-trip zero', () => {
      const wire = realEncodeBigDecimalWire('0');
      expect(fromParameterValue({ bytesValue: wire })).toBe('0');
    });

    it('should accept a plain JS number, converting via toString()', () => {
      const wire = realEncodeBigDecimalWire(19.99);
      expect(fromParameterValue({ bytesValue: wire })).toBe('19.99');
    });

    it('should accept a bigint', () => {
      const wire = realEncodeBigDecimalWire(9007199254740993n);
      expect(fromParameterValue({ bytesValue: wire })).toBe('9007199254740993');
    });

    it('should strip redundant leading zeros while preserving the represented value', () => {
      const wire = realEncodeBigDecimalWire('007.50');
      expect(fromParameterValue({ bytesValue: wire })).toBe('7.50');
    });

    it('should throw a TypeError for exponential/scientific notation', () => {
      expect(() => realEncodeBigDecimalWire('1e-7')).toThrow(TypeError);
    });

    it('should throw a TypeError for non-numeric strings', () => {
      expect(() => realEncodeBigDecimalWire('not-a-number')).toThrow(TypeError);
    });
  });
});
