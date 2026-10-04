using System.Data;
using System.Data.Common;
using System.Diagnostics.CodeAnalysis;
using Com.Openjproxy.Grpc;
using Grpc.Core;
using Grpc.Net.Client;

namespace Ojp.Client;

public sealed class OjpConnection : DbConnection
{
    private static readonly string ClientUuid = Guid.NewGuid().ToString();
    private readonly object sync = new();
    private string connectionString = string.Empty;
    private GrpcChannel? channel;
    private StatementService.StatementServiceClient? rpcClient;
    private SessionInfo? session;
    private ConnectionState state;

    public OjpConnection()
    {
    }

    public OjpConnection(string connectionString)
    {
        ConnectionString = connectionString;
    }

    [AllowNull]
    public override string ConnectionString
    {
        get => connectionString;
        set
        {
            if (state != ConnectionState.Closed)
            {
                throw new InvalidOperationException("The connection string cannot be changed while the connection is open.");
            }

            connectionString = value ?? string.Empty;
        }
    }

    public override string Database => ParseConfiguration().BackendUrl;

    public override string DataSource => ParseConfiguration().Endpoint;

    public override string ServerVersion => "OJP";

    public override ConnectionState State => state;

    internal SessionInfo CurrentSession => session ?? throw new InvalidOperationException("The OJP connection is not open.");

    internal StatementService.StatementServiceClient RpcClient =>
        rpcClient ?? throw new InvalidOperationException("The OJP connection is not open.");

    internal object SyncRoot => sync;

    public override void ChangeDatabase(string databaseName) =>
        throw new NotSupportedException("Changing the database on an OJP connection is not supported.");

    public override void Open()
    {
        lock (sync)
        {
            if (state != ConnectionState.Closed)
            {
                throw new InvalidOperationException("The OJP connection is already open.");
            }

            var configuration = ParseConfiguration();
            var newChannel = GrpcChannel.ForAddress($"http://{configuration.Endpoint}");
            try
            {
                var client = new StatementService.StatementServiceClient(newChannel);
                var details = new ConnectionDetails
                {
                    Url = configuration.BackendUrl,
                    User = configuration.User,
                    Password = configuration.Password,
                    ClientUUID = ClientUuid
                };
                var newSession = client.connect(details);
                if (newSession is null)
                {
                    throw new InvalidOperationException("OJP server returned an empty session.");
                }

                channel = newChannel;
                rpcClient = client;
                session = newSession.Clone();
                state = ConnectionState.Open;
            }
            catch (RpcException exception)
            {
                newChannel.Dispose();
                throw OjpErrors.Map(exception);
            }
            catch
            {
                newChannel.Dispose();
                throw;
            }
        }
    }

    public override void Close()
    {
        lock (sync)
        {
            if (state == ConnectionState.Closed)
            {
                return;
            }

            Exception? closeError = null;
            try
            {
                var termination = RpcClient.terminateSession(CurrentSession.Clone());
                if (termination is null || !termination.Terminated)
                {
                    closeError = new InvalidOperationException("OJP server did not terminate the session.");
                }
            }
            catch (RpcException exception)
            {
                closeError = OjpErrors.Map(exception);
            }
            finally
            {
                channel?.Dispose();
                channel = null;
                rpcClient = null;
                session = null;
                state = ConnectionState.Closed;
            }

            if (closeError is not null)
            {
                throw closeError;
            }
        }
    }

    protected override DbTransaction BeginDbTransaction(IsolationLevel isolationLevel) =>
        throw new NotSupportedException("Transactions are not part of the OJP .NET L1 implementation.");

    protected override DbCommand CreateDbCommand() => new OjpCommand { Connection = this };

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            Close();
        }

        base.Dispose(disposing);
    }

    internal void ReplaceSession(SessionInfo? updated)
    {
        if (updated is not null)
        {
            session = updated.Clone();
        }
    }

    private ConnectionConfiguration ParseConfiguration()
    {
        var builder = new DbConnectionStringBuilder { ConnectionString = connectionString };
        if (!builder.TryGetValue("OjpUrl", out var value) || string.IsNullOrWhiteSpace(Convert.ToString(value)))
        {
            throw new ArgumentException("The OjpUrl connection string property is required.", nameof(ConnectionString));
        }

        var ojpUrl = Convert.ToString(value)!.Trim();
        const string prefix = "jdbc:ojp[";
        if (!ojpUrl.StartsWith(prefix, StringComparison.Ordinal))
        {
            throw new ArgumentException($"OjpUrl must start with '{prefix}'.", nameof(ConnectionString));
        }

        var separator = ojpUrl.IndexOf("]_", prefix.Length, StringComparison.Ordinal);
        if (separator < 0)
        {
            throw new ArgumentException("OjpUrl must contain the endpoint separator ']_'.", nameof(ConnectionString));
        }

        var endpoint = ojpUrl[prefix.Length..separator].Trim();
        var backendUrl = ojpUrl[(separator + 2)..].Trim();
        if (endpoint.Length == 0 || endpoint.Contains(',', StringComparison.Ordinal))
        {
            throw new ArgumentException("OjpUrl must specify exactly one OJP endpoint.", nameof(ConnectionString));
        }

        if (!Uri.TryCreate($"http://{endpoint}", UriKind.Absolute, out var endpointUri) ||
            string.IsNullOrWhiteSpace(endpointUri.Host) || endpointUri.Port < 1)
        {
            throw new ArgumentException("OjpUrl contains an invalid OJP host:port endpoint.", nameof(ConnectionString));
        }

        if (backendUrl.Length == 0)
        {
            throw new ArgumentException("OjpUrl must include the backend JDBC URL.", nameof(ConnectionString));
        }

        if (!backendUrl.StartsWith("jdbc:", StringComparison.Ordinal))
        {
            backendUrl = "jdbc:" + backendUrl;
        }

        return new ConnectionConfiguration(
            endpoint,
            backendUrl,
            GetString(builder, "User ID", "User"),
            GetString(builder, "Password"));
    }

    private static string GetString(DbConnectionStringBuilder builder, params string[] keys)
    {
        foreach (var key in keys)
        {
            if (builder.TryGetValue(key, out var value))
            {
                return Convert.ToString(value) ?? string.Empty;
            }
        }

        return string.Empty;
    }

    private sealed record ConnectionConfiguration(string Endpoint, string BackendUrl, string User, string Password);
}
