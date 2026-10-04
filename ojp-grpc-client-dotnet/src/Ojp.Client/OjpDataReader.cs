using System.Collections;
using System.Data;
using System.Data.Common;

namespace Ojp.Client;

public sealed class OjpDataReader(IReadOnlyList<string> columns, IReadOnlyList<object?[]> rows) : DbDataReader
{
    private int rowIndex = -1;
    private bool isClosed;

    public override int Depth => 0;

    public override int FieldCount => columns.Count;

    public override bool HasRows => rows.Count > 0;

    public override bool IsClosed => isClosed;

    public override int RecordsAffected => -1;

    public override object this[int ordinal] => GetValue(ordinal);

    public override object this[string name] => GetValue(GetOrdinal(name));

    public override bool GetBoolean(int ordinal) => Convert.ToBoolean(GetValue(ordinal));

    public override byte GetByte(int ordinal) => Convert.ToByte(GetValue(ordinal));

    public override long GetBytes(int ordinal, long dataOffset, byte[]? buffer, int bufferOffset, int length)
    {
        var bytes = (byte[])GetValue(ordinal);
        if (buffer is null)
        {
            return bytes.Length;
        }

        var count = Math.Min(length, bytes.Length - checked((int)dataOffset));
        Array.Copy(bytes, dataOffset, buffer, bufferOffset, count);
        return count;
    }

    public override char GetChar(int ordinal) => Convert.ToChar(GetValue(ordinal));

    public override long GetChars(int ordinal, long dataOffset, char[]? buffer, int bufferOffset, int length)
    {
        var characters = Convert.ToString(GetValue(ordinal))!.ToCharArray();
        if (buffer is null)
        {
            return characters.Length;
        }

        var count = Math.Min(length, characters.Length - checked((int)dataOffset));
        Array.Copy(characters, dataOffset, buffer, bufferOffset, count);
        return count;
    }

    public override string GetDataTypeName(int ordinal) => GetFieldType(ordinal).Name;

    public override DateTime GetDateTime(int ordinal) => Convert.ToDateTime(GetValue(ordinal));

    public override decimal GetDecimal(int ordinal) => Convert.ToDecimal(GetValue(ordinal));

    public override double GetDouble(int ordinal) => Convert.ToDouble(GetValue(ordinal));

    public override IEnumerator GetEnumerator() => new DbEnumerator(this, closeReader: false);

    public override Type GetFieldType(int ordinal)
    {
        _ = columns[ordinal];
        foreach (var row in rows)
        {
            if (row[ordinal] is { } value)
            {
                return value.GetType();
            }
        }

        return typeof(object);
    }

    public override float GetFloat(int ordinal) => Convert.ToSingle(GetValue(ordinal));

    public override Guid GetGuid(int ordinal) => GetValue(ordinal) is Guid guid ? guid : Guid.Parse(GetString(ordinal));

    public override short GetInt16(int ordinal) => Convert.ToInt16(GetValue(ordinal));

    public override int GetInt32(int ordinal) => Convert.ToInt32(GetValue(ordinal));

    public override long GetInt64(int ordinal) => Convert.ToInt64(GetValue(ordinal));

    public override string GetName(int ordinal) => columns[ordinal];

    public override int GetOrdinal(string name)
    {
        for (var index = 0; index < columns.Count; index++)
        {
            if (string.Equals(columns[index], name, StringComparison.OrdinalIgnoreCase))
            {
                return index;
            }
        }

        throw new IndexOutOfRangeException($"Column '{name}' was not found.");
    }

    public override DataTable? GetSchemaTable() => null;

    public override string GetString(int ordinal) => Convert.ToString(GetValue(ordinal))!;

    public override object GetValue(int ordinal)
    {
        EnsureOpen();
        if (rowIndex < 0 || rowIndex >= rows.Count)
        {
            throw new InvalidOperationException("The reader is not positioned on a row.");
        }

        var value = rows[rowIndex][ordinal];
        return value ?? DBNull.Value;
    }

    public override int GetValues(object[] values)
    {
        var count = Math.Min(values.Length, FieldCount);
        for (var index = 0; index < count; index++)
        {
            values[index] = GetValue(index);
        }

        return count;
    }

    public override bool IsDBNull(int ordinal) => GetValue(ordinal) is DBNull;

    public override bool NextResult() => false;

    public override bool Read()
    {
        EnsureOpen();
        if (rowIndex + 1 >= rows.Count)
        {
            rowIndex = rows.Count;
            return false;
        }

        rowIndex++;
        return true;
    }

    public override void Close() => isClosed = true;

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            Close();
        }

        base.Dispose(disposing);
    }

    private void EnsureOpen()
    {
        if (isClosed)
        {
            throw new InvalidOperationException("The OJP data reader is closed.");
        }
    }
}
