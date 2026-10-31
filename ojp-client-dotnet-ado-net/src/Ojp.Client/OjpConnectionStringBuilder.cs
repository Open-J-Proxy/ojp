using System.Data.Common;

namespace Ojp.Client;

public sealed class OjpConnectionStringBuilder : DbConnectionStringBuilder
{
    public string OjpUrl
    {
        get => GetString("OjpUrl");
        set => this["OjpUrl"] = value;
    }

    public string UserID
    {
        get => GetString("User ID");
        set => this["User ID"] = value;
    }

    public string Password
    {
        get => GetString("Password");
        set => this["Password"] = value;
    }

    private string GetString(string key) =>
        TryGetValue(key, out var value) ? Convert.ToString(value) ?? string.Empty : string.Empty;
}
