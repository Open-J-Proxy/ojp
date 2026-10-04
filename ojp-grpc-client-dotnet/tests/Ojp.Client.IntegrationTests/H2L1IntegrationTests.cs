using System.Data;
using Microsoft.VisualBasic.FileIO;
using Ojp.Client;

namespace Ojp.Client.IntegrationTests;

public sealed class H2L1IntegrationTests
{
    [Fact]
    public void ShouldReadTheH2ConnectionCsv()
    {
        var (url, user, password) = ReadConnectionConfiguration();

        Assert.Equal("jdbc:h2:mem:ojp_dotnet_h2_l1;DB_CLOSE_DELAY=-1", url);
        Assert.Equal("sa", user);
        Assert.Empty(password);
    }

    [Fact]
    public void ShouldPreserveSemicolonsInTheBackendJdbcUrl()
    {
        var builder = new OjpConnectionStringBuilder
        {
            OjpUrl = "jdbc:ojp[localhost:1059]_jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
            UserID = "sa",
            Password = string.Empty
        };
        using var connection = new OjpConnection(builder.ConnectionString);

        Assert.Equal("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", connection.Database);
        Assert.Equal("localhost:1059", connection.DataSource);
    }

    [Fact]
    public void ShouldExposeAnObjectFieldTypeForEmptyResultColumns()
    {
        using var reader = new OjpDataReader(["id"], Array.Empty<object?[]>());

        Assert.Equal(typeof(object), reader.GetFieldType(0));
    }

    [Fact]
    public void ShouldRejectNegativeCommandTimeouts()
    {
        var command = new OjpCommand();

        Assert.Throws<ArgumentOutOfRangeException>(() => command.CommandTimeout = -1);
    }

    [Fact]
    [Trait("Category", "Integration")]
    public void ShouldSupportL1CrudAndSessionLifecycleAgainstH2()
    {
        var enabled = Environment.GetEnvironmentVariable("OJP_TEST_H2");
        if (!IsEnabled(enabled))
        {
            return;
        }

        var endpoint = Environment.GetEnvironmentVariable("OJP_TEST_H2_ADDR")?.Trim();
        if (string.IsNullOrWhiteSpace(endpoint))
        {
            throw new InvalidOperationException("OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true.");
        }

        var (url, user, password) = ReadConnectionConfiguration();
        var connectionString = new OjpConnectionStringBuilder
        {
            OjpUrl = $"jdbc:ojp[{endpoint}]_{url}",
            UserID = user,
            Password = password
        }.ConnectionString;
        using var connection = new OjpConnection(connectionString);
        connection.Open();

        using (var readiness = connection.CreateCommand())
        {
            readiness.CommandText = "SELECT 1";
            Assert.Equal(1, Convert.ToInt32(readiness.ExecuteScalar()));
        }

        var tableName = "ojp_dotnet_l1_" + Guid.NewGuid().ToString("N");
        ExecuteNonQuery(connection, $"CREATE TABLE {tableName} (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        try
        {
            using (var insert = connection.CreateCommand())
            {
                insert.CommandText = $"INSERT INTO {tableName} (id, name) VALUES (?, ?)";
                insert.Parameters.Add(new OjpParameter { Value = 1 });
                insert.Parameters.Add(new OjpParameter { Value = "before" });
                Assert.Equal(1, insert.ExecuteNonQuery());
            }

            AssertRow(connection, tableName, "before");

            using (var update = connection.CreateCommand())
            {
                update.CommandText = $"UPDATE {tableName} SET name=? WHERE id=?";
                update.Parameters.Add(new OjpParameter { Value = "after" });
                update.Parameters.Add(new OjpParameter { Value = 1 });
                Assert.Equal(1, update.ExecuteNonQuery());
            }

            AssertRow(connection, tableName, "after");
            AssertSqlError(connection, $"INSERT INTO {tableName} (id, name) VALUES (1, 'duplicate')", "23505");
            AssertSqlError(connection, "THIS IS NOT VALID SQL", "42001");

            using (var empty = connection.CreateCommand())
            {
                empty.CommandText = $"SELECT id, name FROM {tableName} WHERE id=999";
                using var reader = empty.ExecuteReader();
                Assert.Equal(2, reader.FieldCount);
                Assert.Equal("ID", reader.GetName(0));
                Assert.Equal(typeof(object), reader.GetFieldType(0));
                Assert.False(reader.Read());
            }

            using (var delete = connection.CreateCommand())
            {
                delete.CommandText = $"DELETE FROM {tableName} WHERE id=1";
                Assert.Equal(1, delete.ExecuteNonQuery());
            }
        }
        finally
        {
            ExecuteNonQuery(connection, $"DROP TABLE IF EXISTS {tableName}");
        }

        connection.Close();
        Assert.Equal(ConnectionState.Closed, connection.State);
        Assert.Throws<InvalidOperationException>(() => ExecuteNonQuery(connection, "DELETE FROM demo"));
    }

    private static (string Url, string User, string Password) ReadConnectionConfiguration()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "TestData", "h2_l1_connection.csv");
        using var parser = new TextFieldParser(path)
        {
            TextFieldType = FieldType.Delimited,
            HasFieldsEnclosedInQuotes = true
        };
        parser.SetDelimiters(",");
        var fields = parser.ReadFields();
        if (fields is not { Length: 3 } || string.IsNullOrWhiteSpace(fields[0]))
        {
            throw new InvalidDataException("H2 connection CSV must contain JDBC URL, username, and password.");
        }

        if (!parser.EndOfData)
        {
            throw new InvalidDataException("H2 connection CSV must contain exactly one record.");
        }

        return (fields[0].Trim(), fields[1].Trim(), fields[2]);
    }

    private static bool IsEnabled(string? value) =>
        value?.Trim().ToLowerInvariant() switch
        {
            "true" or "1" or "yes" => true,
            "false" or "0" or "no" or null or "" => false,
            _ => throw new InvalidOperationException($"OJP_TEST_H2 must be true or false, got '{value}'.")
        };

    private static void ExecuteNonQuery(OjpConnection connection, string sql)
    {
        using var command = connection.CreateCommand();
        command.CommandText = sql;
        command.ExecuteNonQuery();
    }

    private static void AssertRow(OjpConnection connection, string tableName, string expectedName)
    {
        using var command = connection.CreateCommand();
        command.CommandText = $"SELECT id, name FROM {tableName} WHERE id=?";
        command.Parameters.Add(new OjpParameter { Value = 1 });
        using var reader = command.ExecuteReader();
        Assert.True(reader.Read());
        Assert.Equal(1, reader.GetInt32(0));
        Assert.Equal(expectedName, reader.GetString(1));
        Assert.False(reader.Read());
    }

    private static void AssertSqlError(OjpConnection connection, string sql, string sqlState)
    {
        using var command = connection.CreateCommand();
        command.CommandText = sql;
        var exception = Assert.Throws<OjpSqlException>(() => command.ExecuteNonQuery());
        Assert.Equal(sqlState, exception.SqlState);
        Assert.NotEmpty(exception.Message);
        Assert.NotEqual(0, exception.ErrorCode);
    }
}
