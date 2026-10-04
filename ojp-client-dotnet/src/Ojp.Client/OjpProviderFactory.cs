using System.Data.Common;

namespace Ojp.Client;

public sealed class OjpProviderFactory : DbProviderFactory
{
    public static OjpProviderFactory Instance { get; } = new();

    private OjpProviderFactory()
    {
    }

    public override DbConnection CreateConnection() => new OjpConnection();

    public override DbCommand CreateCommand() => new OjpCommand();

    public override DbParameter CreateParameter() => new OjpParameter();
}
