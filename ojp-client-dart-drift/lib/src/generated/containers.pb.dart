// This is a generated file - do not edit.
//
// Generated from containers.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names, prefer_relative_imports

import 'dart:core' as $core;

import 'package:protobuf/protobuf.dart' as $pb;
import 'package:protobuf/well_known_types/google/protobuf/struct.pbenum.dart'
    as $0;

export 'package:protobuf/protobuf.dart' show GeneratedMessageGenericExtensions;

enum Container_Content { value, object, array, properties, notSet }

/// Container wraps any serializable value type to enable type-safe deserialization.
/// This allows us to know which type was serialized without ambiguity.
class Container extends $pb.GeneratedMessage {
  factory Container({
    Value? value,
    Object? object,
    Array? array,
    Properties? properties,
  }) {
    final result = Container._();
    if (value != null) result.value = value;
    if (object != null) result.object = object;
    if (array != null) result.array = array;
    if (properties != null) result.properties = properties;
    return result;
  }

  Container._();

  factory Container.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Container()..mergeFromBuffer(data, registry);
  factory Container.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Container()..mergeFromJson(json, registry);

  static const $core.Map<$core.int, Container_Content> _Container_ContentByTag =
      {
    1: Container_Content.value,
    2: Container_Content.object,
    3: Container_Content.array,
    4: Container_Content.properties,
    0: Container_Content.notSet
  };
  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'Container',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'ojp.transport.v1'),
      createEmptyInstance: Container.$_createMessage)
    ..oo(0, [1, 2, 3, 4])
    ..aOM<Value>(1, _omitFieldNames ? '' : 'value',
        subBuilder: Value.$_createMessage)
    ..aOM<Object>(2, _omitFieldNames ? '' : 'object',
        subBuilder: Object.$_createMessage)
    ..aOM<Array>(3, _omitFieldNames ? '' : 'array',
        subBuilder: Array.$_createMessage)
    ..aOM<Properties>(4, _omitFieldNames ? '' : 'properties',
        subBuilder: Properties.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Container clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Container copyWith(void Function(Container) updates) =>
      super.copyWith((message) => updates(message as Container)) as Container;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use Container() / Container.new instead')
  static Container create() => Container._();
  static $pb.GeneratedMessage $_createMessage() => Container._();
  @$core.override
  Container createEmptyInstance() => Container._();
  @$core.pragma('dart2js:noInline')
  static Container getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<Container>(Container.$_createMessage);
  static Container? _defaultInstance;

  @$pb.TagNumber(1)
  @$pb.TagNumber(2)
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  Container_Content whichContent() => _Container_ContentByTag[$_whichOneof(0)]!;
  @$pb.TagNumber(1)
  @$pb.TagNumber(2)
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  void clearContent() => $_clearField($_whichOneof(0));

  @$pb.TagNumber(1)
  Value get value => $_getN(0);
  @$pb.TagNumber(1)
  set value(Value value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasValue() => $_has(0);
  @$pb.TagNumber(1)
  void clearValue() => $_clearField(1);
  @$pb.TagNumber(1)
  Value ensureValue() => $_ensure(0);

  @$pb.TagNumber(2)
  Object get object => $_getN(1);
  @$pb.TagNumber(2)
  set object(Object value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasObject() => $_has(1);
  @$pb.TagNumber(2)
  void clearObject() => $_clearField(2);
  @$pb.TagNumber(2)
  Object ensureObject() => $_ensure(1);

  @$pb.TagNumber(3)
  Array get array => $_getN(2);
  @$pb.TagNumber(3)
  set array(Array value) => $_setField(3, value);
  @$pb.TagNumber(3)
  $core.bool hasArray() => $_has(2);
  @$pb.TagNumber(3)
  void clearArray() => $_clearField(3);
  @$pb.TagNumber(3)
  Array ensureArray() => $_ensure(2);

  @$pb.TagNumber(4)
  Properties get properties => $_getN(3);
  @$pb.TagNumber(4)
  set properties(Properties value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasProperties() => $_has(3);
  @$pb.TagNumber(4)
  void clearProperties() => $_clearField(4);
  @$pb.TagNumber(4)
  Properties ensureProperties() => $_ensure(3);
}

enum Value_Kind { s, n, b, o, a, nullValue, notSet }

/// Value represents a single value that can be a primitive, object, array, or null.
/// This is used to represent any JSON-like value structure in a type-safe manner.
class Value extends $pb.GeneratedMessage {
  factory Value({
    $core.String? s,
    $core.double? n,
    $core.bool? b,
    Object? o,
    Array? a,
    $0.NullValue? nullValue,
  }) {
    final result = Value._();
    if (s != null) result.s = s;
    if (n != null) result.n = n;
    if (b != null) result.b = b;
    if (o != null) result.o = o;
    if (a != null) result.a = a;
    if (nullValue != null) result.nullValue = nullValue;
    return result;
  }

  Value._();

  factory Value.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Value()..mergeFromBuffer(data, registry);
  factory Value.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Value()..mergeFromJson(json, registry);

  static const $core.Map<$core.int, Value_Kind> _Value_KindByTag = {
    1: Value_Kind.s,
    2: Value_Kind.n,
    3: Value_Kind.b,
    4: Value_Kind.o,
    5: Value_Kind.a,
    6: Value_Kind.nullValue,
    0: Value_Kind.notSet
  };
  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'Value',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'ojp.transport.v1'),
      createEmptyInstance: Value.$_createMessage)
    ..oo(0, [1, 2, 3, 4, 5, 6])
    ..aOS(1, _omitFieldNames ? '' : 's')
    ..aD(2, _omitFieldNames ? '' : 'n')
    ..aOB(3, _omitFieldNames ? '' : 'b')
    ..aOM<Object>(4, _omitFieldNames ? '' : 'o',
        subBuilder: Object.$_createMessage)
    ..aOM<Array>(5, _omitFieldNames ? '' : 'a',
        subBuilder: Array.$_createMessage)
    ..aE<$0.NullValue>(6, _omitFieldNames ? '' : 'nullValue',
        enumValues: $0.NullValue.values)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Value clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Value copyWith(void Function(Value) updates) =>
      super.copyWith((message) => updates(message as Value)) as Value;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use Value() / Value.new instead')
  static Value create() => Value._();
  static $pb.GeneratedMessage $_createMessage() => Value._();
  @$core.override
  Value createEmptyInstance() => Value._();
  @$core.pragma('dart2js:noInline')
  static Value getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<Value>(Value.$_createMessage);
  static Value? _defaultInstance;

  @$pb.TagNumber(1)
  @$pb.TagNumber(2)
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  @$pb.TagNumber(6)
  Value_Kind whichKind() => _Value_KindByTag[$_whichOneof(0)]!;
  @$pb.TagNumber(1)
  @$pb.TagNumber(2)
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  @$pb.TagNumber(6)
  void clearKind() => $_clearField($_whichOneof(0));

  @$pb.TagNumber(1)
  $core.String get s => $_getSZ(0);
  @$pb.TagNumber(1)
  set s($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasS() => $_has(0);
  @$pb.TagNumber(1)
  void clearS() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.double get n => $_getN(1);
  @$pb.TagNumber(2)
  set n($core.double value) => $_setDouble(1, value);
  @$pb.TagNumber(2)
  $core.bool hasN() => $_has(1);
  @$pb.TagNumber(2)
  void clearN() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.bool get b => $_getBF(2);
  @$pb.TagNumber(3)
  set b($core.bool value) => $_setBool(2, value);
  @$pb.TagNumber(3)
  $core.bool hasB() => $_has(2);
  @$pb.TagNumber(3)
  void clearB() => $_clearField(3);

  @$pb.TagNumber(4)
  Object get o => $_getN(3);
  @$pb.TagNumber(4)
  set o(Object value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasO() => $_has(3);
  @$pb.TagNumber(4)
  void clearO() => $_clearField(4);
  @$pb.TagNumber(4)
  Object ensureO() => $_ensure(3);

  @$pb.TagNumber(5)
  Array get a => $_getN(4);
  @$pb.TagNumber(5)
  set a(Array value) => $_setField(5, value);
  @$pb.TagNumber(5)
  $core.bool hasA() => $_has(4);
  @$pb.TagNumber(5)
  void clearA() => $_clearField(5);
  @$pb.TagNumber(5)
  Array ensureA() => $_ensure(4);

  @$pb.TagNumber(6)
  $0.NullValue get nullValue => $_getN(5);
  @$pb.TagNumber(6)
  set nullValue($0.NullValue value) => $_setField(6, value);
  @$pb.TagNumber(6)
  $core.bool hasNullValue() => $_has(5);
  @$pb.TagNumber(6)
  void clearNullValue() => $_clearField(6);
}

/// Object represents a map/dictionary structure with string keys and arbitrary values.
/// Uses map<string, Value> to preserve key-value pairs.
/// Note: Iteration order is not guaranteed by protobuf maps, but implementations
/// should use LinkedHashMap to preserve insertion order in Java.
class Object extends $pb.GeneratedMessage {
  factory Object({
    $core.Iterable<$core.MapEntry<$core.String, Value>>? entries,
  }) {
    final result = Object._();
    if (entries != null) result.entries.addEntries(entries);
    return result;
  }

  Object._();

  factory Object.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Object()..mergeFromBuffer(data, registry);
  factory Object.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Object()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'Object',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'ojp.transport.v1'),
      createEmptyInstance: Object.$_createMessage)
    ..m<$core.String, Value>(1, _omitFieldNames ? '' : 'entries',
        entryClassName: 'Object.EntriesEntry',
        keyFieldType: $pb.PbFieldType.OS,
        valueFieldType: $pb.PbFieldType.OM,
        valueCreator: Value.$_createMessage,
        valueDefaultOrMaker: Value.getDefault,
        packageName: const $pb.PackageName('ojp.transport.v1'))
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Object clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Object copyWith(void Function(Object) updates) =>
      super.copyWith((message) => updates(message as Object)) as Object;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use Object() / Object.new instead')
  static Object create() => Object._();
  static $pb.GeneratedMessage $_createMessage() => Object._();
  @$core.override
  Object createEmptyInstance() => Object._();
  @$core.pragma('dart2js:noInline')
  static Object getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<Object>(Object.$_createMessage);
  static Object? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbMap<$core.String, Value> get entries => $_getMap(0);
}

/// Array represents a list/array structure with ordered values.
class Array extends $pb.GeneratedMessage {
  factory Array({
    $core.Iterable<Value>? values,
  }) {
    final result = Array._();
    if (values != null) result.values.addAll(values);
    return result;
  }

  Array._();

  factory Array.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Array()..mergeFromBuffer(data, registry);
  factory Array.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Array()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'Array',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'ojp.transport.v1'),
      createEmptyInstance: Array.$_createMessage)
    ..pPM<Value>(1, _omitFieldNames ? '' : 'values',
        subBuilder: Value.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Array clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Array copyWith(void Function(Array) updates) =>
      super.copyWith((message) => updates(message as Array)) as Array;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use Array() / Array.new instead')
  static Array create() => Array._();
  static $pb.GeneratedMessage $_createMessage() => Array._();
  @$core.override
  Array createEmptyInstance() => Array._();
  @$core.pragma('dart2js:noInline')
  static Array getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<Array>(Array.$_createMessage);
  static Array? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbList<Value> get values => $_getList(0);
}

/// Properties represents a simple map with string keys and string values.
/// This is a special case for java.util.Properties where all values are strings.
class Properties extends $pb.GeneratedMessage {
  factory Properties({
    $core.Iterable<$core.MapEntry<$core.String, $core.String>>? entries,
  }) {
    final result = Properties._();
    if (entries != null) result.entries.addEntries(entries);
    return result;
  }

  Properties._();

  factory Properties.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Properties()..mergeFromBuffer(data, registry);
  factory Properties.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Properties()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'Properties',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'ojp.transport.v1'),
      createEmptyInstance: Properties.$_createMessage)
    ..m<$core.String, $core.String>(1, _omitFieldNames ? '' : 'entries',
        entryClassName: 'Properties.EntriesEntry',
        keyFieldType: $pb.PbFieldType.OS,
        valueFieldType: $pb.PbFieldType.OS,
        packageName: const $pb.PackageName('ojp.transport.v1'))
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Properties clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Properties copyWith(void Function(Properties) updates) =>
      super.copyWith((message) => updates(message as Properties)) as Properties;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use Properties() / Properties.new instead')
  static Properties create() => Properties._();
  static $pb.GeneratedMessage $_createMessage() => Properties._();
  @$core.override
  Properties createEmptyInstance() => Properties._();
  @$core.pragma('dart2js:noInline')
  static Properties getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<Properties>(Properties.$_createMessage);
  static Properties? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbMap<$core.String, $core.String> get entries => $_getMap(0);
}

const $core.bool _omitFieldNames =
    $core.bool.fromEnvironment('protobuf.omit_field_names');
const $core.bool _omitMessageNames =
    $core.bool.fromEnvironment('protobuf.omit_message_names');
