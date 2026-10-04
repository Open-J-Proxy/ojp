using System.Collections;
using System.Data.Common;

namespace Ojp.Client;

public sealed class OjpParameterCollection : DbParameterCollection
{
    private readonly List<DbParameter> parameters = [];

    public override int Count => parameters.Count;

    public override object SyncRoot => ((ICollection)parameters).SyncRoot;

    public override int Add(object value)
    {
        parameters.Add(RequireParameter(value));
        return parameters.Count - 1;
    }

    public override void AddRange(Array values)
    {
        foreach (var value in values)
        {
            Add(value!);
        }
    }

    public override void Clear() => parameters.Clear();

    public override bool Contains(object value) => value is DbParameter parameter && parameters.Contains(parameter);

    public override bool Contains(string value) => IndexOf(value) >= 0;

    public override void CopyTo(Array array, int index) => ((ICollection)parameters).CopyTo(array, index);

    public override IEnumerator GetEnumerator() => parameters.GetEnumerator();

    public override int IndexOf(object value) => value is DbParameter parameter ? parameters.IndexOf(parameter) : -1;

    public override int IndexOf(string parameterName) =>
        parameters.FindIndex(parameter => string.Equals(parameter.ParameterName, parameterName, StringComparison.OrdinalIgnoreCase));

    public override void Insert(int index, object value) => parameters.Insert(index, RequireParameter(value));

    public override void Remove(object value)
    {
        if (value is DbParameter parameter)
        {
            parameters.Remove(parameter);
        }
    }

    public override void RemoveAt(int index) => parameters.RemoveAt(index);

    public override void RemoveAt(string parameterName)
    {
        var index = IndexOf(parameterName);
        if (index < 0)
        {
            throw new IndexOutOfRangeException($"Parameter '{parameterName}' was not found.");
        }

        RemoveAt(index);
    }

    protected override DbParameter GetParameter(int index) => parameters[index];

    protected override DbParameter GetParameter(string parameterName)
    {
        var index = IndexOf(parameterName);
        if (index < 0)
        {
            throw new IndexOutOfRangeException($"Parameter '{parameterName}' was not found.");
        }

        return parameters[index];
    }

    protected override void SetParameter(int index, DbParameter value) => parameters[index] = RequireParameter(value);

    protected override void SetParameter(string parameterName, DbParameter value)
    {
        var index = IndexOf(parameterName);
        if (index < 0)
        {
            Add(value);
        }
        else
        {
            parameters[index] = RequireParameter(value);
        }
    }

    private static DbParameter RequireParameter(object value) =>
        value as DbParameter ?? throw new ArgumentException("Only OJP parameters can be added to this collection.", nameof(value));
}
