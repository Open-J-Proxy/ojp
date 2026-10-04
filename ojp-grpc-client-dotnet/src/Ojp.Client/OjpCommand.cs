using System.Data;
using System.Data.Common;
using System.Diagnostics.CodeAnalysis;
using Com.Openjproxy.Grpc;
using Grpc.Core;
using Google.Protobuf.WellKnownTypes;

namespace Ojp.Client;

public sealed class OjpCommand : DbCommand
{
    private readonly OjpParameterCollection parameters = new();
    private string commandText = string.Empty;
    private OjpConnection? connection;
    private DbTransaction? transaction;
    private int commandTimeout = 30;

    [AllowNull]
    public override string CommandText
    {
        get => commandText;
        set => commandText = value ?? string.Empty;
    }

    public override int CommandTimeout
    {
        get => commandTimeout;
        set => commandTimeout = value >= 0
            ? value
            : throw new ArgumentOutOfRangeException(nameof(value), "Command timeout must be zero or greater.");
    }

    public override CommandType CommandType { get; set; } = CommandType.Text;

    public override bool DesignTimeVisible { get; set; }

    public override UpdateRowSource UpdatedRowSource { get; set; } = UpdateRowSource.None;

    protected override DbConnection? DbConnection
    {
        get => connection;
        set => connection = value as OjpConnection ??
                            (value is null ? null : throw new ArgumentException("OjpCommand requires an OjpConnection."));
    }

    protected override DbParameterCollection DbParameterCollection => parameters;

    protected override DbTransaction? DbTransaction
    {
        get => transaction;
        set => transaction = value;
    }

    public override void Cancel()
    {
    }

    public override int ExecuteNonQuery()
    {
        var currentConnection = GetOpenConnection();
        lock (currentConnection.SyncRoot)
        {
            try
            {
                var result = currentConnection.RpcClient.executeUpdate(
                    CreateRequest(currentConnection), deadline: GetDeadline());
                currentConnection.ReplaceSession(result.Session);
                if (result.Type != ResultType.Integer || result.ResultCase != OpResult.ResultOneofCase.IntValue)
                {
                    throw new InvalidOperationException("OJP server returned an invalid update result.");
                }

                return result.IntValue;
            }
            catch (RpcException exception)
            {
                throw OjpErrors.Map(exception);
            }
        }
    }

    public override object? ExecuteScalar()
    {
        using var reader = ExecuteDbDataReader(CommandBehavior.SingleRow);
        return reader.Read() ? reader.GetValue(0) : null;
    }

    public override void Prepare()
    {
        ValidateCommand();
    }

    protected override DbParameter CreateDbParameter() => new OjpParameter();

    protected override DbDataReader ExecuteDbDataReader(CommandBehavior behavior)
    {
        var currentConnection = GetOpenConnection();
        lock (currentConnection.SyncRoot)
        {
            try
            {
                using var call = currentConnection.RpcClient.executeQuery(
                    CreateRequest(currentConnection), deadline: GetDeadline());
                var columns = new List<string>();
                var rows = new List<object?[]>();
                while (call.ResponseStream.MoveNext().GetAwaiter().GetResult())
                {
                    var result = call.ResponseStream.Current;
                    currentConnection.ReplaceSession(result.Session);
                    if (result.ResultCase != OpResult.ResultOneofCase.QueryResult)
                    {
                        continue;
                    }

                    var query = result.QueryResult;
                    if (columns.Count == 0)
                    {
                        columns.AddRange(query.Labels);
                    }

                    foreach (var row in query.Rows)
                    {
                        if (row.Columns.Count != columns.Count)
                        {
                            throw new InvalidOperationException("OJP server returned a row with an unexpected column count.");
                        }

                        rows.Add(row.Columns.Select(OjpValues.Decode).ToArray());
                    }
                }

                return new OjpDataReader(columns, rows);
            }
            catch (RpcException exception)
            {
                throw OjpErrors.Map(exception);
            }
        }
    }

    private OjpConnection GetOpenConnection()
    {
        ValidateCommand();
        var currentConnection = connection!;
        if (currentConnection.State != ConnectionState.Open)
        {
            throw new InvalidOperationException("The OJP connection is not open.");
        }

        return currentConnection;
    }

    private void ValidateCommand()
    {
        if (CommandType != CommandType.Text)
        {
            throw new NotSupportedException("Only text commands are supported by the OJP .NET L1 implementation.");
        }

        if (string.IsNullOrWhiteSpace(CommandText))
        {
            throw new InvalidOperationException("SQL command text is required.");
        }
    }

    private StatementRequest CreateRequest(OjpConnection currentConnection)
    {
        var request = new StatementRequest
        {
            Session = currentConnection.CurrentSession.Clone(),
            Sql = CommandText
        };
        for (var index = 0; index < parameters.Count; index++)
        {
            request.Parameters.Add(OjpValues.Encode((OjpParameter)parameters[index]!, index + 1));
        }

        return request;
    }

    private DateTime? GetDeadline() =>
        CommandTimeout == 0 ? null : DateTime.UtcNow.AddSeconds(CommandTimeout);
}

internal static class OjpValues
{
    internal static ParameterProto Encode(OjpParameter parameter, int index)
    {
        if (parameter.Direction != ParameterDirection.Input)
        {
            throw new NotSupportedException("Only input parameters are supported by the OJP .NET L1 implementation.");
        }

        var value = parameter.Value;
        var proto = new ParameterProto { Index = index };
        if (value is null or DBNull)
        {
            proto.Type = ParameterTypeProto.PtNull;
            proto.Values.Add(new ParameterValue { IsNull = true });
            return proto;
        }

        var parameterValue = new ParameterValue();
        switch (parameter.DbType)
        {
            case DbType.Date:
                SetDate(proto, parameterValue, Convert.ToDateTime(value));
                break;
            case DbType.Time:
                SetTime(proto, parameterValue, Convert.ToDateTime(value));
                break;
            case DbType.DateTime:
            case DbType.DateTime2:
            case DbType.DateTimeOffset:
                SetTimestamp(proto, parameterValue, value);
                break;
            default:
                SetScalar(proto, parameterValue, value);
                break;
        }

        proto.Values.Add(parameterValue);
        return proto;
    }

    internal static object? Decode(ParameterValue value) =>
        value.ValueCase switch
        {
            ParameterValue.ValueOneofCase.BoolValue => value.BoolValue,
            ParameterValue.ValueOneofCase.IntValue => value.IntValue,
            ParameterValue.ValueOneofCase.LongValue => value.LongValue,
            ParameterValue.ValueOneofCase.FloatValue => value.FloatValue,
            ParameterValue.ValueOneofCase.DoubleValue => value.DoubleValue,
            ParameterValue.ValueOneofCase.StringValue => value.StringValue,
            ParameterValue.ValueOneofCase.BytesValue => value.BytesValue.ToByteArray(),
            ParameterValue.ValueOneofCase.IsNull => null,
            ParameterValue.ValueOneofCase.TimestampValue => value.TimestampValue.Instant?.ToDateTime(),
            ParameterValue.ValueOneofCase.DateValue => new DateTime(
                value.DateValue.Year, value.DateValue.Month, value.DateValue.Day, 0, 0, 0, DateTimeKind.Unspecified),
            ParameterValue.ValueOneofCase.TimeValue => new DateTime(
                1, 1, 1, value.TimeValue.Hours, value.TimeValue.Minutes, value.TimeValue.Seconds, DateTimeKind.Unspecified)
                .AddTicks(value.TimeValue.Nanos / 100),
            _ => throw new NotSupportedException($"OJP returned an unsupported L1 value: {value.ValueCase}.")
        };

    private static void SetScalar(ParameterProto proto, ParameterValue value, object input)
    {
        switch (input)
        {
            case bool boolean:
                proto.Type = ParameterTypeProto.PtBoolean;
                value.BoolValue = boolean;
                break;
            case byte number:
                proto.Type = ParameterTypeProto.PtByte;
                value.IntValue = number;
                break;
            case short number:
                proto.Type = ParameterTypeProto.PtShort;
                value.IntValue = number;
                break;
            case int number:
                proto.Type = ParameterTypeProto.PtInt;
                value.IntValue = number;
                break;
            case long number:
                proto.Type = ParameterTypeProto.PtLong;
                value.LongValue = number;
                break;
            case float number:
                proto.Type = ParameterTypeProto.PtFloat;
                value.FloatValue = number;
                break;
            case double number:
                proto.Type = ParameterTypeProto.PtDouble;
                value.DoubleValue = number;
                break;
            case string text:
                proto.Type = ParameterTypeProto.PtString;
                value.StringValue = text;
                break;
            case byte[] bytes:
                proto.Type = ParameterTypeProto.PtBytes;
                value.BytesValue = Google.Protobuf.ByteString.CopyFrom(bytes);
                break;
            case DateTime timestamp:
                SetTimestamp(proto, value, timestamp);
                break;
            case DateTimeOffset timestamp:
                SetTimestamp(proto, value, timestamp);
                break;
            default:
                throw new NotSupportedException($"OJP does not support the parameter type {input.GetType().FullName} at L1.");
        }
    }

    private static void SetDate(ParameterProto proto, ParameterValue value, DateTime date)
    {
        proto.Type = ParameterTypeProto.PtDate;
        value.DateValue = new Google.Type.Date { Year = date.Year, Month = date.Month, Day = date.Day };
    }

    private static void SetTime(ParameterProto proto, ParameterValue value, DateTime time)
    {
        proto.Type = ParameterTypeProto.PtTime;
        value.TimeValue = new Google.Type.TimeOfDay
        {
            Hours = time.Hour,
            Minutes = time.Minute,
            Seconds = time.Second,
            Nanos = (int)((time.Ticks % TimeSpan.TicksPerSecond) * 100)
        };
    }

    private static void SetTimestamp(ParameterProto proto, ParameterValue value, object timestamp)
    {
        var wireTimestamp = timestamp switch
        {
            DateTime dateTime => Timestamp.FromDateTime(
                dateTime.Kind == DateTimeKind.Utc ? dateTime : DateTime.SpecifyKind(dateTime, DateTimeKind.Utc)),
            DateTimeOffset dateTimeOffset => Timestamp.FromDateTimeOffset(dateTimeOffset),
            _ => throw new ArgumentException("Timestamp parameter must be DateTime or DateTimeOffset.", nameof(timestamp))
        };
        proto.Type = ParameterTypeProto.PtTimestamp;
        value.TimestampValue = new TimestampWithZone
        {
            Instant = wireTimestamp,
            Timezone = "UTC",
            OriginalType = TemporalType.Timestamp
        };
    }
}
