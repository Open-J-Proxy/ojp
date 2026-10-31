using System.Data.Common;

namespace Ojp.Client;

public sealed class OjpSqlException(string message, string sqlState, int vendorCode, Exception? innerException)
    : DbException(message, innerException)
{
    public override string? SqlState { get; } = sqlState;

    public override int ErrorCode { get; } = vendorCode;
}
