using System.Data;
using System.Data.Common;
using System.Diagnostics.CodeAnalysis;

namespace Ojp.Client;

public sealed class OjpParameter : DbParameter
{
    public override DbType DbType { get; set; } = DbType.Object;

    public override ParameterDirection Direction { get; set; } = ParameterDirection.Input;

    public override bool IsNullable { get; set; }

    [AllowNull]
    public override string ParameterName { get; set; } = string.Empty;

    [AllowNull]
    public override string SourceColumn { get; set; } = string.Empty;

    public override object? Value { get; set; }

    public override bool SourceColumnNullMapping { get; set; }

    public override int Size { get; set; }

    public override void ResetDbType()
    {
        DbType = DbType.Object;
    }
}
