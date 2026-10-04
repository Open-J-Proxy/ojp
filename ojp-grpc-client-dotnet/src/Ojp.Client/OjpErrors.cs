using Com.Openjproxy.Grpc;
using Grpc.Core;

namespace Ojp.Client;

internal static class OjpErrors
{
    private const string SqlErrorTrailer = "com.openjproxy.grpc.sqlerrorresponse-bin";

    internal static Exception Map(RpcException exception)
    {
        foreach (var entry in exception.Trailers)
        {
            if (!entry.Key.Equals(SqlErrorTrailer, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var response = SqlErrorResponse.Parser.ParseFrom(entry.ValueBytes);
            return new OjpSqlException(
                response.Reason.Length == 0 ? exception.Status.Detail : response.Reason,
                response.SqlState,
                response.VendorCode,
                exception);
        }

        return new InvalidOperationException($"OJP gRPC request failed ({exception.StatusCode}): {exception.Status.Detail}", exception);
    }
}
