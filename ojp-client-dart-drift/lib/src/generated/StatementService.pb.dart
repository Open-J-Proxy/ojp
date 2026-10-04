// This is a generated file - do not edit.
//
// Generated from StatementService.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names, prefer_relative_imports

import 'dart:core' as $core;

import 'package:fixnum/fixnum.dart' as $fixnum;
import 'package:protobuf/protobuf.dart' as $pb;
import 'package:protobuf/well_known_types/google/protobuf/timestamp.pb.dart'
    as $1;
import 'package:protobuf/well_known_types/google/protobuf/wrappers.pb.dart'
    as $4;

import 'StatementService.pbenum.dart';
import 'google/type/date.pb.dart' as $2;
import 'google/type/timeofday.pb.dart' as $3;

export 'package:protobuf/protobuf.dart' show GeneratedMessageGenericExtensions;

export 'StatementService.pbenum.dart';

class ConnectionDetails extends $pb.GeneratedMessage {
  factory ConnectionDetails({
    $core.String? url,
    $core.String? user,
    $core.String? password,
    $core.String? clientUUID,
    $core.Iterable<PropertyEntry>? properties,
    $core.bool? isXA,
    $core.Iterable<$core.String>? serverEndpoints,
    $core.String? clusterHealth,
  }) {
    final result = ConnectionDetails._();
    if (url != null) result.url = url;
    if (user != null) result.user = user;
    if (password != null) result.password = password;
    if (clientUUID != null) result.clientUUID = clientUUID;
    if (properties != null) result.properties.addAll(properties);
    if (isXA != null) result.isXA = isXA;
    if (serverEndpoints != null) result.serverEndpoints.addAll(serverEndpoints);
    if (clusterHealth != null) result.clusterHealth = clusterHealth;
    return result;
  }

  ConnectionDetails._();

  factory ConnectionDetails.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ConnectionDetails()..mergeFromBuffer(data, registry);
  factory ConnectionDetails.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ConnectionDetails()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'ConnectionDetails',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: ConnectionDetails.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'url')
    ..aOS(2, _omitFieldNames ? '' : 'user')
    ..aOS(3, _omitFieldNames ? '' : 'password')
    ..aOS(4, _omitFieldNames ? '' : 'clientUUID', protoName: 'clientUUID')
    ..pPM<PropertyEntry>(6, _omitFieldNames ? '' : 'properties',
        subBuilder: PropertyEntry.$_createMessage)
    ..aOB(7, _omitFieldNames ? '' : 'isXA', protoName: 'isXA')
    ..pPS(8, _omitFieldNames ? '' : 'serverEndpoints',
        protoName: 'serverEndpoints')
    ..aOS(9, _omitFieldNames ? '' : 'clusterHealth', protoName: 'clusterHealth')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ConnectionDetails clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ConnectionDetails copyWith(void Function(ConnectionDetails) updates) =>
      super.copyWith((message) => updates(message as ConnectionDetails))
          as ConnectionDetails;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use ConnectionDetails() / ConnectionDetails.new instead')
  static ConnectionDetails create() => ConnectionDetails._();
  static $pb.GeneratedMessage $_createMessage() => ConnectionDetails._();
  @$core.override
  ConnectionDetails createEmptyInstance() => ConnectionDetails._();
  @$core.pragma('dart2js:noInline')
  static ConnectionDetails getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ConnectionDetails>(
          ConnectionDetails.$_createMessage);
  static ConnectionDetails? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get url => $_getSZ(0);
  @$pb.TagNumber(1)
  set url($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasUrl() => $_has(0);
  @$pb.TagNumber(1)
  void clearUrl() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get user => $_getSZ(1);
  @$pb.TagNumber(2)
  set user($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasUser() => $_has(1);
  @$pb.TagNumber(2)
  void clearUser() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get password => $_getSZ(2);
  @$pb.TagNumber(3)
  set password($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasPassword() => $_has(2);
  @$pb.TagNumber(3)
  void clearPassword() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get clientUUID => $_getSZ(3);
  @$pb.TagNumber(4)
  set clientUUID($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasClientUUID() => $_has(3);
  @$pb.TagNumber(4)
  void clearClientUUID() => $_clearField(4);

  @$pb.TagNumber(6)
  $pb.PbList<PropertyEntry> get properties => $_getList(4);

  @$pb.TagNumber(7)
  $core.bool get isXA => $_getBF(5);
  @$pb.TagNumber(7)
  set isXA($core.bool value) => $_setBool(5, value);
  @$pb.TagNumber(7)
  $core.bool hasIsXA() => $_has(5);
  @$pb.TagNumber(7)
  void clearIsXA() => $_clearField(7);

  @$pb.TagNumber(8)
  $pb.PbList<$core.String> get serverEndpoints => $_getList(6);

  @$pb.TagNumber(9)
  $core.String get clusterHealth => $_getSZ(7);
  @$pb.TagNumber(9)
  set clusterHealth($core.String value) => $_setString(7, value);
  @$pb.TagNumber(9)
  $core.bool hasClusterHealth() => $_has(7);
  @$pb.TagNumber(9)
  void clearClusterHealth() => $_clearField(9);
}

/// Wrapper message for timestamp with timezone information
class TimestampWithZone extends $pb.GeneratedMessage {
  factory TimestampWithZone({
    $1.Timestamp? instant,
    $core.String? timezone,
    TemporalType? originalType,
  }) {
    final result = TimestampWithZone._();
    if (instant != null) result.instant = instant;
    if (timezone != null) result.timezone = timezone;
    if (originalType != null) result.originalType = originalType;
    return result;
  }

  TimestampWithZone._();

  factory TimestampWithZone.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      TimestampWithZone()..mergeFromBuffer(data, registry);
  factory TimestampWithZone.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      TimestampWithZone()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'TimestampWithZone',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: TimestampWithZone.$_createMessage)
    ..aOM<$1.Timestamp>(1, _omitFieldNames ? '' : 'instant',
        subBuilder: $1.Timestamp.$_createMessage)
    ..aOS(2, _omitFieldNames ? '' : 'timezone')
    ..aE<TemporalType>(3, _omitFieldNames ? '' : 'originalType',
        enumValues: TemporalType.values)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  TimestampWithZone clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  TimestampWithZone copyWith(void Function(TimestampWithZone) updates) =>
      super.copyWith((message) => updates(message as TimestampWithZone))
          as TimestampWithZone;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use TimestampWithZone() / TimestampWithZone.new instead')
  static TimestampWithZone create() => TimestampWithZone._();
  static $pb.GeneratedMessage $_createMessage() => TimestampWithZone._();
  @$core.override
  TimestampWithZone createEmptyInstance() => TimestampWithZone._();
  @$core.pragma('dart2js:noInline')
  static TimestampWithZone getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<TimestampWithZone>(
          TimestampWithZone.$_createMessage);
  static TimestampWithZone? _defaultInstance;

  @$pb.TagNumber(1)
  $1.Timestamp get instant => $_getN(0);
  @$pb.TagNumber(1)
  set instant($1.Timestamp value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasInstant() => $_has(0);
  @$pb.TagNumber(1)
  void clearInstant() => $_clearField(1);
  @$pb.TagNumber(1)
  $1.Timestamp ensureInstant() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.String get timezone => $_getSZ(1);
  @$pb.TagNumber(2)
  set timezone($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasTimezone() => $_has(1);
  @$pb.TagNumber(2)
  void clearTimezone() => $_clearField(2);

  @$pb.TagNumber(3)
  TemporalType get originalType => $_getN(2);
  @$pb.TagNumber(3)
  set originalType(TemporalType value) => $_setField(3, value);
  @$pb.TagNumber(3)
  $core.bool hasOriginalType() => $_has(2);
  @$pb.TagNumber(3)
  void clearOriginalType() => $_clearField(3);
}

enum ParameterValue_Value {
  boolValue,
  intValue,
  longValue,
  floatValue,
  doubleValue,
  stringValue,
  bytesValue,
  intArrayValue,
  longArrayValue,
  isNull,
  timestampValue,
  dateValue,
  timeValue,
  urlValue,
  rowidValue,
  uuidValue,
  bigintegerValue,
  stringArrayValue,
  rowidlifetimeValue,
  notSet
}

/// Message representing a single value in a parameter
class ParameterValue extends $pb.GeneratedMessage {
  factory ParameterValue({
    $core.bool? boolValue,
    $core.int? intValue,
    $fixnum.Int64? longValue,
    $core.double? floatValue,
    $core.double? doubleValue,
    $core.String? stringValue,
    $core.List<$core.int>? bytesValue,
    IntArray? intArrayValue,
    LongArray? longArrayValue,
    $core.bool? isNull,
    TimestampWithZone? timestampValue,
    $2.Date? dateValue,
    $3.TimeOfDay? timeValue,
    $4.StringValue? urlValue,
    $4.StringValue? rowidValue,
    $4.StringValue? uuidValue,
    $4.StringValue? bigintegerValue,
    StringArray? stringArrayValue,
    $4.StringValue? rowidlifetimeValue,
  }) {
    final result = ParameterValue._();
    if (boolValue != null) result.boolValue = boolValue;
    if (intValue != null) result.intValue = intValue;
    if (longValue != null) result.longValue = longValue;
    if (floatValue != null) result.floatValue = floatValue;
    if (doubleValue != null) result.doubleValue = doubleValue;
    if (stringValue != null) result.stringValue = stringValue;
    if (bytesValue != null) result.bytesValue = bytesValue;
    if (intArrayValue != null) result.intArrayValue = intArrayValue;
    if (longArrayValue != null) result.longArrayValue = longArrayValue;
    if (isNull != null) result.isNull = isNull;
    if (timestampValue != null) result.timestampValue = timestampValue;
    if (dateValue != null) result.dateValue = dateValue;
    if (timeValue != null) result.timeValue = timeValue;
    if (urlValue != null) result.urlValue = urlValue;
    if (rowidValue != null) result.rowidValue = rowidValue;
    if (uuidValue != null) result.uuidValue = uuidValue;
    if (bigintegerValue != null) result.bigintegerValue = bigintegerValue;
    if (stringArrayValue != null) result.stringArrayValue = stringArrayValue;
    if (rowidlifetimeValue != null)
      result.rowidlifetimeValue = rowidlifetimeValue;
    return result;
  }

  ParameterValue._();

  factory ParameterValue.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ParameterValue()..mergeFromBuffer(data, registry);
  factory ParameterValue.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ParameterValue()..mergeFromJson(json, registry);

  static const $core.Map<$core.int, ParameterValue_Value>
      _ParameterValue_ValueByTag = {
    1: ParameterValue_Value.boolValue,
    2: ParameterValue_Value.intValue,
    3: ParameterValue_Value.longValue,
    4: ParameterValue_Value.floatValue,
    5: ParameterValue_Value.doubleValue,
    6: ParameterValue_Value.stringValue,
    7: ParameterValue_Value.bytesValue,
    8: ParameterValue_Value.intArrayValue,
    9: ParameterValue_Value.longArrayValue,
    10: ParameterValue_Value.isNull,
    11: ParameterValue_Value.timestampValue,
    12: ParameterValue_Value.dateValue,
    13: ParameterValue_Value.timeValue,
    14: ParameterValue_Value.urlValue,
    15: ParameterValue_Value.rowidValue,
    16: ParameterValue_Value.uuidValue,
    17: ParameterValue_Value.bigintegerValue,
    18: ParameterValue_Value.stringArrayValue,
    19: ParameterValue_Value.rowidlifetimeValue,
    0: ParameterValue_Value.notSet
  };
  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'ParameterValue',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: ParameterValue.$_createMessage)
    ..oo(0, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19])
    ..aOB(1, _omitFieldNames ? '' : 'boolValue')
    ..aI(2, _omitFieldNames ? '' : 'intValue')
    ..aInt64(3, _omitFieldNames ? '' : 'longValue')
    ..aD(4, _omitFieldNames ? '' : 'floatValue', fieldType: $pb.PbFieldType.OF)
    ..aD(5, _omitFieldNames ? '' : 'doubleValue')
    ..aOS(6, _omitFieldNames ? '' : 'stringValue')
    ..a<$core.List<$core.int>>(
        7, _omitFieldNames ? '' : 'bytesValue', $pb.PbFieldType.OY)
    ..aOM<IntArray>(8, _omitFieldNames ? '' : 'intArrayValue',
        subBuilder: IntArray.$_createMessage)
    ..aOM<LongArray>(9, _omitFieldNames ? '' : 'longArrayValue',
        subBuilder: LongArray.$_createMessage)
    ..aOB(10, _omitFieldNames ? '' : 'isNull')
    ..aOM<TimestampWithZone>(11, _omitFieldNames ? '' : 'timestampValue',
        subBuilder: TimestampWithZone.$_createMessage)
    ..aOM<$2.Date>(12, _omitFieldNames ? '' : 'dateValue',
        subBuilder: $2.Date.$_createMessage)
    ..aOM<$3.TimeOfDay>(13, _omitFieldNames ? '' : 'timeValue',
        subBuilder: $3.TimeOfDay.$_createMessage)
    ..aOM<$4.StringValue>(14, _omitFieldNames ? '' : 'urlValue',
        subBuilder: $4.StringValue.$_createMessage)
    ..aOM<$4.StringValue>(15, _omitFieldNames ? '' : 'rowidValue',
        subBuilder: $4.StringValue.$_createMessage)
    ..aOM<$4.StringValue>(16, _omitFieldNames ? '' : 'uuidValue',
        subBuilder: $4.StringValue.$_createMessage)
    ..aOM<$4.StringValue>(17, _omitFieldNames ? '' : 'bigintegerValue',
        subBuilder: $4.StringValue.$_createMessage)
    ..aOM<StringArray>(18, _omitFieldNames ? '' : 'stringArrayValue',
        subBuilder: StringArray.$_createMessage)
    ..aOM<$4.StringValue>(19, _omitFieldNames ? '' : 'rowidlifetimeValue',
        subBuilder: $4.StringValue.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ParameterValue clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ParameterValue copyWith(void Function(ParameterValue) updates) =>
      super.copyWith((message) => updates(message as ParameterValue))
          as ParameterValue;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use ParameterValue() / ParameterValue.new instead')
  static ParameterValue create() => ParameterValue._();
  static $pb.GeneratedMessage $_createMessage() => ParameterValue._();
  @$core.override
  ParameterValue createEmptyInstance() => ParameterValue._();
  @$core.pragma('dart2js:noInline')
  static ParameterValue getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ParameterValue>(
          ParameterValue.$_createMessage);
  static ParameterValue? _defaultInstance;

  @$pb.TagNumber(1)
  @$pb.TagNumber(2)
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  @$pb.TagNumber(6)
  @$pb.TagNumber(7)
  @$pb.TagNumber(8)
  @$pb.TagNumber(9)
  @$pb.TagNumber(10)
  @$pb.TagNumber(11)
  @$pb.TagNumber(12)
  @$pb.TagNumber(13)
  @$pb.TagNumber(14)
  @$pb.TagNumber(15)
  @$pb.TagNumber(16)
  @$pb.TagNumber(17)
  @$pb.TagNumber(18)
  @$pb.TagNumber(19)
  ParameterValue_Value whichValue() =>
      _ParameterValue_ValueByTag[$_whichOneof(0)]!;
  @$pb.TagNumber(1)
  @$pb.TagNumber(2)
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  @$pb.TagNumber(6)
  @$pb.TagNumber(7)
  @$pb.TagNumber(8)
  @$pb.TagNumber(9)
  @$pb.TagNumber(10)
  @$pb.TagNumber(11)
  @$pb.TagNumber(12)
  @$pb.TagNumber(13)
  @$pb.TagNumber(14)
  @$pb.TagNumber(15)
  @$pb.TagNumber(16)
  @$pb.TagNumber(17)
  @$pb.TagNumber(18)
  @$pb.TagNumber(19)
  void clearValue() => $_clearField($_whichOneof(0));

  @$pb.TagNumber(1)
  $core.bool get boolValue => $_getBF(0);
  @$pb.TagNumber(1)
  set boolValue($core.bool value) => $_setBool(0, value);
  @$pb.TagNumber(1)
  $core.bool hasBoolValue() => $_has(0);
  @$pb.TagNumber(1)
  void clearBoolValue() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.int get intValue => $_getIZ(1);
  @$pb.TagNumber(2)
  set intValue($core.int value) => $_setSignedInt32(1, value);
  @$pb.TagNumber(2)
  $core.bool hasIntValue() => $_has(1);
  @$pb.TagNumber(2)
  void clearIntValue() => $_clearField(2);

  @$pb.TagNumber(3)
  $fixnum.Int64 get longValue => $_getI64(2);
  @$pb.TagNumber(3)
  set longValue($fixnum.Int64 value) => $_setInt64(2, value);
  @$pb.TagNumber(3)
  $core.bool hasLongValue() => $_has(2);
  @$pb.TagNumber(3)
  void clearLongValue() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.double get floatValue => $_getN(3);
  @$pb.TagNumber(4)
  set floatValue($core.double value) => $_setFloat(3, value);
  @$pb.TagNumber(4)
  $core.bool hasFloatValue() => $_has(3);
  @$pb.TagNumber(4)
  void clearFloatValue() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.double get doubleValue => $_getN(4);
  @$pb.TagNumber(5)
  set doubleValue($core.double value) => $_setDouble(4, value);
  @$pb.TagNumber(5)
  $core.bool hasDoubleValue() => $_has(4);
  @$pb.TagNumber(5)
  void clearDoubleValue() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.String get stringValue => $_getSZ(5);
  @$pb.TagNumber(6)
  set stringValue($core.String value) => $_setString(5, value);
  @$pb.TagNumber(6)
  $core.bool hasStringValue() => $_has(5);
  @$pb.TagNumber(6)
  void clearStringValue() => $_clearField(6);

  @$pb.TagNumber(7)
  $core.List<$core.int> get bytesValue => $_getN(6);
  @$pb.TagNumber(7)
  set bytesValue($core.List<$core.int> value) => $_setBytes(6, value);
  @$pb.TagNumber(7)
  $core.bool hasBytesValue() => $_has(6);
  @$pb.TagNumber(7)
  void clearBytesValue() => $_clearField(7);

  @$pb.TagNumber(8)
  IntArray get intArrayValue => $_getN(7);
  @$pb.TagNumber(8)
  set intArrayValue(IntArray value) => $_setField(8, value);
  @$pb.TagNumber(8)
  $core.bool hasIntArrayValue() => $_has(7);
  @$pb.TagNumber(8)
  void clearIntArrayValue() => $_clearField(8);
  @$pb.TagNumber(8)
  IntArray ensureIntArrayValue() => $_ensure(7);

  @$pb.TagNumber(9)
  LongArray get longArrayValue => $_getN(8);
  @$pb.TagNumber(9)
  set longArrayValue(LongArray value) => $_setField(9, value);
  @$pb.TagNumber(9)
  $core.bool hasLongArrayValue() => $_has(8);
  @$pb.TagNumber(9)
  void clearLongArrayValue() => $_clearField(9);
  @$pb.TagNumber(9)
  LongArray ensureLongArrayValue() => $_ensure(8);

  @$pb.TagNumber(10)
  $core.bool get isNull => $_getBF(9);
  @$pb.TagNumber(10)
  set isNull($core.bool value) => $_setBool(9, value);
  @$pb.TagNumber(10)
  $core.bool hasIsNull() => $_has(9);
  @$pb.TagNumber(10)
  void clearIsNull() => $_clearField(10);

  @$pb.TagNumber(11)
  TimestampWithZone get timestampValue => $_getN(10);
  @$pb.TagNumber(11)
  set timestampValue(TimestampWithZone value) => $_setField(11, value);
  @$pb.TagNumber(11)
  $core.bool hasTimestampValue() => $_has(10);
  @$pb.TagNumber(11)
  void clearTimestampValue() => $_clearField(11);
  @$pb.TagNumber(11)
  TimestampWithZone ensureTimestampValue() => $_ensure(10);

  @$pb.TagNumber(12)
  $2.Date get dateValue => $_getN(11);
  @$pb.TagNumber(12)
  set dateValue($2.Date value) => $_setField(12, value);
  @$pb.TagNumber(12)
  $core.bool hasDateValue() => $_has(11);
  @$pb.TagNumber(12)
  void clearDateValue() => $_clearField(12);
  @$pb.TagNumber(12)
  $2.Date ensureDateValue() => $_ensure(11);

  @$pb.TagNumber(13)
  $3.TimeOfDay get timeValue => $_getN(12);
  @$pb.TagNumber(13)
  set timeValue($3.TimeOfDay value) => $_setField(13, value);
  @$pb.TagNumber(13)
  $core.bool hasTimeValue() => $_has(12);
  @$pb.TagNumber(13)
  void clearTimeValue() => $_clearField(13);
  @$pb.TagNumber(13)
  $3.TimeOfDay ensureTimeValue() => $_ensure(12);

  /// URL encoded as string: URL.toExternalForm() on write, new URL(string) on read
  /// Presence-aware: unset means null, empty string is a valid (though unusual) URL value
  @$pb.TagNumber(14)
  $4.StringValue get urlValue => $_getN(13);
  @$pb.TagNumber(14)
  set urlValue($4.StringValue value) => $_setField(14, value);
  @$pb.TagNumber(14)
  $core.bool hasUrlValue() => $_has(13);
  @$pb.TagNumber(14)
  void clearUrlValue() => $_clearField(14);
  @$pb.TagNumber(14)
  $4.StringValue ensureUrlValue() => $_ensure(13);

  /// RowId encoded as base64 string of rowId.getBytes()
  /// Opaque bytes representation - vendor-specific, cannot be reconstructed into java.sql.RowId
  /// Presence-aware: unset means null, empty string means zero-length rowid bytes
  @$pb.TagNumber(15)
  $4.StringValue get rowidValue => $_getN(14);
  @$pb.TagNumber(15)
  set rowidValue($4.StringValue value) => $_setField(15, value);
  @$pb.TagNumber(15)
  $core.bool hasRowidValue() => $_has(14);
  @$pb.TagNumber(15)
  void clearRowidValue() => $_clearField(15);
  @$pb.TagNumber(15)
  $4.StringValue ensureRowidValue() => $_ensure(14);

  /// UUID encoded as canonical string representation (8-4-4-4-12 hex format)
  /// Presence-aware: unset means null
  @$pb.TagNumber(16)
  $4.StringValue get uuidValue => $_getN(15);
  @$pb.TagNumber(16)
  set uuidValue($4.StringValue value) => $_setField(16, value);
  @$pb.TagNumber(16)
  $core.bool hasUuidValue() => $_has(15);
  @$pb.TagNumber(16)
  void clearUuidValue() => $_clearField(16);
  @$pb.TagNumber(16)
  $4.StringValue ensureUuidValue() => $_ensure(15);

  /// BigInteger encoded as string (decimal representation)
  /// Presence-aware: unset means null
  @$pb.TagNumber(17)
  $4.StringValue get bigintegerValue => $_getN(16);
  @$pb.TagNumber(17)
  set bigintegerValue($4.StringValue value) => $_setField(17, value);
  @$pb.TagNumber(17)
  $core.bool hasBigintegerValue() => $_has(16);
  @$pb.TagNumber(17)
  void clearBigintegerValue() => $_clearField(17);
  @$pb.TagNumber(17)
  $4.StringValue ensureBigintegerValue() => $_ensure(16);

  /// String array for JDBC methods like execute(sql, String[] columnNames)
  @$pb.TagNumber(18)
  StringArray get stringArrayValue => $_getN(17);
  @$pb.TagNumber(18)
  set stringArrayValue(StringArray value) => $_setField(18, value);
  @$pb.TagNumber(18)
  $core.bool hasStringArrayValue() => $_has(17);
  @$pb.TagNumber(18)
  void clearStringArrayValue() => $_clearField(18);
  @$pb.TagNumber(18)
  StringArray ensureStringArrayValue() => $_ensure(17);

  /// RowIdLifetime encoded as enum name string (e.g., "ROWID_VALID_FOREVER")
  /// Presence-aware: unset means null
  @$pb.TagNumber(19)
  $4.StringValue get rowidlifetimeValue => $_getN(18);
  @$pb.TagNumber(19)
  set rowidlifetimeValue($4.StringValue value) => $_setField(19, value);
  @$pb.TagNumber(19)
  $core.bool hasRowidlifetimeValue() => $_has(18);
  @$pb.TagNumber(19)
  void clearRowidlifetimeValue() => $_clearField(19);
  @$pb.TagNumber(19)
  $4.StringValue ensureRowidlifetimeValue() => $_ensure(18);
}

/// Message for int array
class IntArray extends $pb.GeneratedMessage {
  factory IntArray({
    $core.Iterable<$core.int>? values,
  }) {
    final result = IntArray._();
    if (values != null) result.values.addAll(values);
    return result;
  }

  IntArray._();

  factory IntArray.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      IntArray()..mergeFromBuffer(data, registry);
  factory IntArray.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      IntArray()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'IntArray',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: IntArray.$_createMessage)
    ..p<$core.int>(1, _omitFieldNames ? '' : 'values', $pb.PbFieldType.K3)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  IntArray clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  IntArray copyWith(void Function(IntArray) updates) =>
      super.copyWith((message) => updates(message as IntArray)) as IntArray;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use IntArray() / IntArray.new instead')
  static IntArray create() => IntArray._();
  static $pb.GeneratedMessage $_createMessage() => IntArray._();
  @$core.override
  IntArray createEmptyInstance() => IntArray._();
  @$core.pragma('dart2js:noInline')
  static IntArray getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<IntArray>(IntArray.$_createMessage);
  static IntArray? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbList<$core.int> get values => $_getList(0);
}

/// Message for long array
class LongArray extends $pb.GeneratedMessage {
  factory LongArray({
    $core.Iterable<$fixnum.Int64>? values,
  }) {
    final result = LongArray._();
    if (values != null) result.values.addAll(values);
    return result;
  }

  LongArray._();

  factory LongArray.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      LongArray()..mergeFromBuffer(data, registry);
  factory LongArray.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      LongArray()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'LongArray',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: LongArray.$_createMessage)
    ..p<$fixnum.Int64>(1, _omitFieldNames ? '' : 'values', $pb.PbFieldType.K6)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  LongArray clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  LongArray copyWith(void Function(LongArray) updates) =>
      super.copyWith((message) => updates(message as LongArray)) as LongArray;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use LongArray() / LongArray.new instead')
  static LongArray create() => LongArray._();
  static $pb.GeneratedMessage $_createMessage() => LongArray._();
  @$core.override
  LongArray createEmptyInstance() => LongArray._();
  @$core.pragma('dart2js:noInline')
  static LongArray getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<LongArray>(LongArray.$_createMessage);
  static LongArray? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbList<$fixnum.Int64> get values => $_getList(0);
}

/// Message for string array
class StringArray extends $pb.GeneratedMessage {
  factory StringArray({
    $core.Iterable<$core.String>? values,
  }) {
    final result = StringArray._();
    if (values != null) result.values.addAll(values);
    return result;
  }

  StringArray._();

  factory StringArray.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      StringArray()..mergeFromBuffer(data, registry);
  factory StringArray.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      StringArray()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'StringArray',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: StringArray.$_createMessage)
    ..pPS(1, _omitFieldNames ? '' : 'values')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  StringArray clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  StringArray copyWith(void Function(StringArray) updates) =>
      super.copyWith((message) => updates(message as StringArray))
          as StringArray;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use StringArray() / StringArray.new instead')
  static StringArray create() => StringArray._();
  static $pb.GeneratedMessage $_createMessage() => StringArray._();
  @$core.override
  StringArray createEmptyInstance() => StringArray._();
  @$core.pragma('dart2js:noInline')
  static StringArray getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<StringArray>(
          StringArray.$_createMessage);
  static StringArray? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbList<$core.String> get values => $_getList(0);
}

/// Message representing a JDBC parameter
class ParameterProto extends $pb.GeneratedMessage {
  factory ParameterProto({
    $core.int? index,
    ParameterTypeProto? type,
    $core.Iterable<ParameterValue>? values,
  }) {
    final result = ParameterProto._();
    if (index != null) result.index = index;
    if (type != null) result.type = type;
    if (values != null) result.values.addAll(values);
    return result;
  }

  ParameterProto._();

  factory ParameterProto.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ParameterProto()..mergeFromBuffer(data, registry);
  factory ParameterProto.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ParameterProto()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'ParameterProto',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: ParameterProto.$_createMessage)
    ..aI(1, _omitFieldNames ? '' : 'index')
    ..aE<ParameterTypeProto>(2, _omitFieldNames ? '' : 'type',
        enumValues: ParameterTypeProto.values)
    ..pPM<ParameterValue>(3, _omitFieldNames ? '' : 'values',
        subBuilder: ParameterValue.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ParameterProto clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ParameterProto copyWith(void Function(ParameterProto) updates) =>
      super.copyWith((message) => updates(message as ParameterProto))
          as ParameterProto;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use ParameterProto() / ParameterProto.new instead')
  static ParameterProto create() => ParameterProto._();
  static $pb.GeneratedMessage $_createMessage() => ParameterProto._();
  @$core.override
  ParameterProto createEmptyInstance() => ParameterProto._();
  @$core.pragma('dart2js:noInline')
  static ParameterProto getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ParameterProto>(
          ParameterProto.$_createMessage);
  static ParameterProto? _defaultInstance;

  @$pb.TagNumber(1)
  $core.int get index => $_getIZ(0);
  @$pb.TagNumber(1)
  set index($core.int value) => $_setSignedInt32(0, value);
  @$pb.TagNumber(1)
  $core.bool hasIndex() => $_has(0);
  @$pb.TagNumber(1)
  void clearIndex() => $_clearField(1);

  @$pb.TagNumber(2)
  ParameterTypeProto get type => $_getN(1);
  @$pb.TagNumber(2)
  set type(ParameterTypeProto value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasType() => $_has(1);
  @$pb.TagNumber(2)
  void clearType() => $_clearField(2);

  @$pb.TagNumber(3)
  $pb.PbList<ParameterValue> get values => $_getList(2);
}

enum PropertyEntry_Value {
  boolValue,
  intValue,
  longValue,
  floatValue,
  doubleValue,
  stringValue,
  bytesValue,
  notSet
}

/// Message representing a property entry (key-value pair)
class PropertyEntry extends $pb.GeneratedMessage {
  factory PropertyEntry({
    $core.String? key,
    $core.bool? boolValue,
    $core.int? intValue,
    $fixnum.Int64? longValue,
    $core.double? floatValue,
    $core.double? doubleValue,
    $core.String? stringValue,
    $core.List<$core.int>? bytesValue,
  }) {
    final result = PropertyEntry._();
    if (key != null) result.key = key;
    if (boolValue != null) result.boolValue = boolValue;
    if (intValue != null) result.intValue = intValue;
    if (longValue != null) result.longValue = longValue;
    if (floatValue != null) result.floatValue = floatValue;
    if (doubleValue != null) result.doubleValue = doubleValue;
    if (stringValue != null) result.stringValue = stringValue;
    if (bytesValue != null) result.bytesValue = bytesValue;
    return result;
  }

  PropertyEntry._();

  factory PropertyEntry.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      PropertyEntry()..mergeFromBuffer(data, registry);
  factory PropertyEntry.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      PropertyEntry()..mergeFromJson(json, registry);

  static const $core.Map<$core.int, PropertyEntry_Value>
      _PropertyEntry_ValueByTag = {
    2: PropertyEntry_Value.boolValue,
    3: PropertyEntry_Value.intValue,
    4: PropertyEntry_Value.longValue,
    5: PropertyEntry_Value.floatValue,
    6: PropertyEntry_Value.doubleValue,
    7: PropertyEntry_Value.stringValue,
    8: PropertyEntry_Value.bytesValue,
    0: PropertyEntry_Value.notSet
  };
  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'PropertyEntry',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: PropertyEntry.$_createMessage)
    ..oo(0, [2, 3, 4, 5, 6, 7, 8])
    ..aOS(1, _omitFieldNames ? '' : 'key')
    ..aOB(2, _omitFieldNames ? '' : 'boolValue')
    ..aI(3, _omitFieldNames ? '' : 'intValue')
    ..aInt64(4, _omitFieldNames ? '' : 'longValue')
    ..aD(5, _omitFieldNames ? '' : 'floatValue', fieldType: $pb.PbFieldType.OF)
    ..aD(6, _omitFieldNames ? '' : 'doubleValue')
    ..aOS(7, _omitFieldNames ? '' : 'stringValue')
    ..a<$core.List<$core.int>>(
        8, _omitFieldNames ? '' : 'bytesValue', $pb.PbFieldType.OY)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PropertyEntry clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PropertyEntry copyWith(void Function(PropertyEntry) updates) =>
      super.copyWith((message) => updates(message as PropertyEntry))
          as PropertyEntry;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use PropertyEntry() / PropertyEntry.new instead')
  static PropertyEntry create() => PropertyEntry._();
  static $pb.GeneratedMessage $_createMessage() => PropertyEntry._();
  @$core.override
  PropertyEntry createEmptyInstance() => PropertyEntry._();
  @$core.pragma('dart2js:noInline')
  static PropertyEntry getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<PropertyEntry>(
          PropertyEntry.$_createMessage);
  static PropertyEntry? _defaultInstance;

  @$pb.TagNumber(2)
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  @$pb.TagNumber(6)
  @$pb.TagNumber(7)
  @$pb.TagNumber(8)
  PropertyEntry_Value whichValue() =>
      _PropertyEntry_ValueByTag[$_whichOneof(0)]!;
  @$pb.TagNumber(2)
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  @$pb.TagNumber(6)
  @$pb.TagNumber(7)
  @$pb.TagNumber(8)
  void clearValue() => $_clearField($_whichOneof(0));

  @$pb.TagNumber(1)
  $core.String get key => $_getSZ(0);
  @$pb.TagNumber(1)
  set key($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasKey() => $_has(0);
  @$pb.TagNumber(1)
  void clearKey() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.bool get boolValue => $_getBF(1);
  @$pb.TagNumber(2)
  set boolValue($core.bool value) => $_setBool(1, value);
  @$pb.TagNumber(2)
  $core.bool hasBoolValue() => $_has(1);
  @$pb.TagNumber(2)
  void clearBoolValue() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.int get intValue => $_getIZ(2);
  @$pb.TagNumber(3)
  set intValue($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasIntValue() => $_has(2);
  @$pb.TagNumber(3)
  void clearIntValue() => $_clearField(3);

  @$pb.TagNumber(4)
  $fixnum.Int64 get longValue => $_getI64(3);
  @$pb.TagNumber(4)
  set longValue($fixnum.Int64 value) => $_setInt64(3, value);
  @$pb.TagNumber(4)
  $core.bool hasLongValue() => $_has(3);
  @$pb.TagNumber(4)
  void clearLongValue() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.double get floatValue => $_getN(4);
  @$pb.TagNumber(5)
  set floatValue($core.double value) => $_setFloat(4, value);
  @$pb.TagNumber(5)
  $core.bool hasFloatValue() => $_has(4);
  @$pb.TagNumber(5)
  void clearFloatValue() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.double get doubleValue => $_getN(5);
  @$pb.TagNumber(6)
  set doubleValue($core.double value) => $_setDouble(5, value);
  @$pb.TagNumber(6)
  $core.bool hasDoubleValue() => $_has(5);
  @$pb.TagNumber(6)
  void clearDoubleValue() => $_clearField(6);

  @$pb.TagNumber(7)
  $core.String get stringValue => $_getSZ(6);
  @$pb.TagNumber(7)
  set stringValue($core.String value) => $_setString(6, value);
  @$pb.TagNumber(7)
  $core.bool hasStringValue() => $_has(6);
  @$pb.TagNumber(7)
  void clearStringValue() => $_clearField(7);

  @$pb.TagNumber(8)
  $core.List<$core.int> get bytesValue => $_getN(7);
  @$pb.TagNumber(8)
  set bytesValue($core.List<$core.int> value) => $_setBytes(7, value);
  @$pb.TagNumber(8)
  $core.bool hasBytesValue() => $_has(7);
  @$pb.TagNumber(8)
  void clearBytesValue() => $_clearField(8);
}

/// Message representing a row in a result set
class ResultRow extends $pb.GeneratedMessage {
  factory ResultRow({
    $core.Iterable<ParameterValue>? columns,
  }) {
    final result = ResultRow._();
    if (columns != null) result.columns.addAll(columns);
    return result;
  }

  ResultRow._();

  factory ResultRow.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ResultRow()..mergeFromBuffer(data, registry);
  factory ResultRow.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ResultRow()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'ResultRow',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: ResultRow.$_createMessage)
    ..pPM<ParameterValue>(1, _omitFieldNames ? '' : 'columns',
        subBuilder: ParameterValue.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ResultRow clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ResultRow copyWith(void Function(ResultRow) updates) =>
      super.copyWith((message) => updates(message as ResultRow)) as ResultRow;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use ResultRow() / ResultRow.new instead')
  static ResultRow create() => ResultRow._();
  static $pb.GeneratedMessage $_createMessage() => ResultRow._();
  @$core.override
  ResultRow createEmptyInstance() => ResultRow._();
  @$core.pragma('dart2js:noInline')
  static ResultRow getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<ResultRow>(ResultRow.$_createMessage);
  static ResultRow? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbList<ParameterValue> get columns => $_getList(0);
}

/// Message representing query result data
class OpQueryResultProto extends $pb.GeneratedMessage {
  factory OpQueryResultProto({
    $core.String? resultSetUUID,
    $core.Iterable<$core.String>? labels,
    $core.Iterable<ResultRow>? rows,
  }) {
    final result = OpQueryResultProto._();
    if (resultSetUUID != null) result.resultSetUUID = resultSetUUID;
    if (labels != null) result.labels.addAll(labels);
    if (rows != null) result.rows.addAll(rows);
    return result;
  }

  OpQueryResultProto._();

  factory OpQueryResultProto.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      OpQueryResultProto()..mergeFromBuffer(data, registry);
  factory OpQueryResultProto.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      OpQueryResultProto()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'OpQueryResultProto',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: OpQueryResultProto.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'resultSetUUID', protoName: 'resultSetUUID')
    ..pPS(2, _omitFieldNames ? '' : 'labels')
    ..pPM<ResultRow>(3, _omitFieldNames ? '' : 'rows',
        subBuilder: ResultRow.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  OpQueryResultProto clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  OpQueryResultProto copyWith(void Function(OpQueryResultProto) updates) =>
      super.copyWith((message) => updates(message as OpQueryResultProto))
          as OpQueryResultProto;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use OpQueryResultProto() / OpQueryResultProto.new instead')
  static OpQueryResultProto create() => OpQueryResultProto._();
  static $pb.GeneratedMessage $_createMessage() => OpQueryResultProto._();
  @$core.override
  OpQueryResultProto createEmptyInstance() => OpQueryResultProto._();
  @$core.pragma('dart2js:noInline')
  static OpQueryResultProto getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<OpQueryResultProto>(
          OpQueryResultProto.$_createMessage);
  static OpQueryResultProto? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get resultSetUUID => $_getSZ(0);
  @$pb.TagNumber(1)
  set resultSetUUID($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasResultSetUUID() => $_has(0);
  @$pb.TagNumber(1)
  void clearResultSetUUID() => $_clearField(1);

  @$pb.TagNumber(2)
  $pb.PbList<$core.String> get labels => $_getList(1);

  @$pb.TagNumber(3)
  $pb.PbList<ResultRow> get rows => $_getList(2);
}

class TransactionInfo extends $pb.GeneratedMessage {
  factory TransactionInfo({
    $core.String? transactionUUID,
    TransactionStatus? transactionStatus,
  }) {
    final result = TransactionInfo._();
    if (transactionUUID != null) result.transactionUUID = transactionUUID;
    if (transactionStatus != null) result.transactionStatus = transactionStatus;
    return result;
  }

  TransactionInfo._();

  factory TransactionInfo.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      TransactionInfo()..mergeFromBuffer(data, registry);
  factory TransactionInfo.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      TransactionInfo()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'TransactionInfo',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: TransactionInfo.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'transactionUUID',
        protoName: 'transactionUUID')
    ..aE<TransactionStatus>(2, _omitFieldNames ? '' : 'transactionStatus',
        protoName: 'transactionStatus', enumValues: TransactionStatus.values)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  TransactionInfo clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  TransactionInfo copyWith(void Function(TransactionInfo) updates) =>
      super.copyWith((message) => updates(message as TransactionInfo))
          as TransactionInfo;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use TransactionInfo() / TransactionInfo.new instead')
  static TransactionInfo create() => TransactionInfo._();
  static $pb.GeneratedMessage $_createMessage() => TransactionInfo._();
  @$core.override
  TransactionInfo createEmptyInstance() => TransactionInfo._();
  @$core.pragma('dart2js:noInline')
  static TransactionInfo getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<TransactionInfo>(
          TransactionInfo.$_createMessage);
  static TransactionInfo? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get transactionUUID => $_getSZ(0);
  @$pb.TagNumber(1)
  set transactionUUID($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasTransactionUUID() => $_has(0);
  @$pb.TagNumber(1)
  void clearTransactionUUID() => $_clearField(1);

  @$pb.TagNumber(2)
  TransactionStatus get transactionStatus => $_getN(1);
  @$pb.TagNumber(2)
  set transactionStatus(TransactionStatus value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasTransactionStatus() => $_has(1);
  @$pb.TagNumber(2)
  void clearTransactionStatus() => $_clearField(2);
}

class SessionInfo extends $pb.GeneratedMessage {
  factory SessionInfo({
    $core.String? connHash,
    $core.String? clientUUID,
    $core.String? sessionUUID,
    TransactionInfo? transactionInfo,
    SessionStatus? sessionStatus,
    $core.bool? isXA,
    $core.String? targetServer,
    $core.String? clusterHealth,
    $core.int? clientCount,
    $core.int? maxAdmission,
    $core.int? observedPeak,
  }) {
    final result = SessionInfo._();
    if (connHash != null) result.connHash = connHash;
    if (clientUUID != null) result.clientUUID = clientUUID;
    if (sessionUUID != null) result.sessionUUID = sessionUUID;
    if (transactionInfo != null) result.transactionInfo = transactionInfo;
    if (sessionStatus != null) result.sessionStatus = sessionStatus;
    if (isXA != null) result.isXA = isXA;
    if (targetServer != null) result.targetServer = targetServer;
    if (clusterHealth != null) result.clusterHealth = clusterHealth;
    if (clientCount != null) result.clientCount = clientCount;
    if (maxAdmission != null) result.maxAdmission = maxAdmission;
    if (observedPeak != null) result.observedPeak = observedPeak;
    return result;
  }

  SessionInfo._();

  factory SessionInfo.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      SessionInfo()..mergeFromBuffer(data, registry);
  factory SessionInfo.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      SessionInfo()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'SessionInfo',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: SessionInfo.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'connHash', protoName: 'connHash')
    ..aOS(2, _omitFieldNames ? '' : 'clientUUID', protoName: 'clientUUID')
    ..aOS(3, _omitFieldNames ? '' : 'sessionUUID', protoName: 'sessionUUID')
    ..aOM<TransactionInfo>(4, _omitFieldNames ? '' : 'transactionInfo',
        protoName: 'transactionInfo',
        subBuilder: TransactionInfo.$_createMessage)
    ..aE<SessionStatus>(5, _omitFieldNames ? '' : 'sessionStatus',
        protoName: 'sessionStatus', enumValues: SessionStatus.values)
    ..aOB(6, _omitFieldNames ? '' : 'isXA', protoName: 'isXA')
    ..aOS(7, _omitFieldNames ? '' : 'targetServer', protoName: 'targetServer')
    ..aOS(8, _omitFieldNames ? '' : 'clusterHealth', protoName: 'clusterHealth')
    ..aI(9, _omitFieldNames ? '' : 'clientCount', protoName: 'clientCount')
    ..aI(10, _omitFieldNames ? '' : 'maxAdmission', protoName: 'maxAdmission')
    ..aI(11, _omitFieldNames ? '' : 'observedPeak', protoName: 'observedPeak')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  SessionInfo clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  SessionInfo copyWith(void Function(SessionInfo) updates) =>
      super.copyWith((message) => updates(message as SessionInfo))
          as SessionInfo;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use SessionInfo() / SessionInfo.new instead')
  static SessionInfo create() => SessionInfo._();
  static $pb.GeneratedMessage $_createMessage() => SessionInfo._();
  @$core.override
  SessionInfo createEmptyInstance() => SessionInfo._();
  @$core.pragma('dart2js:noInline')
  static SessionInfo getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<SessionInfo>(
          SessionInfo.$_createMessage);
  static SessionInfo? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get connHash => $_getSZ(0);
  @$pb.TagNumber(1)
  set connHash($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasConnHash() => $_has(0);
  @$pb.TagNumber(1)
  void clearConnHash() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get clientUUID => $_getSZ(1);
  @$pb.TagNumber(2)
  set clientUUID($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasClientUUID() => $_has(1);
  @$pb.TagNumber(2)
  void clearClientUUID() => $_clearField(2);

  /// only set if connection has to be the same among different requests, within transaction boundaries or when using LOB objects.
  @$pb.TagNumber(3)
  $core.String get sessionUUID => $_getSZ(2);
  @$pb.TagNumber(3)
  set sessionUUID($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasSessionUUID() => $_has(2);
  @$pb.TagNumber(3)
  void clearSessionUUID() => $_clearField(3);

  @$pb.TagNumber(4)
  TransactionInfo get transactionInfo => $_getN(3);
  @$pb.TagNumber(4)
  set transactionInfo(TransactionInfo value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasTransactionInfo() => $_has(3);
  @$pb.TagNumber(4)
  void clearTransactionInfo() => $_clearField(4);
  @$pb.TagNumber(4)
  TransactionInfo ensureTransactionInfo() => $_ensure(3);

  @$pb.TagNumber(5)
  SessionStatus get sessionStatus => $_getN(4);
  @$pb.TagNumber(5)
  set sessionStatus(SessionStatus value) => $_setField(5, value);
  @$pb.TagNumber(5)
  $core.bool hasSessionStatus() => $_has(4);
  @$pb.TagNumber(5)
  void clearSessionStatus() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.bool get isXA => $_getBF(5);
  @$pb.TagNumber(6)
  set isXA($core.bool value) => $_setBool(5, value);
  @$pb.TagNumber(6)
  $core.bool hasIsXA() => $_has(5);
  @$pb.TagNumber(6)
  void clearIsXA() => $_clearField(6);

  @$pb.TagNumber(7)
  $core.String get targetServer => $_getSZ(6);
  @$pb.TagNumber(7)
  set targetServer($core.String value) => $_setString(6, value);
  @$pb.TagNumber(7)
  $core.bool hasTargetServer() => $_has(6);
  @$pb.TagNumber(7)
  void clearTargetServer() => $_clearField(7);

  @$pb.TagNumber(8)
  $core.String get clusterHealth => $_getSZ(7);
  @$pb.TagNumber(8)
  set clusterHealth($core.String value) => $_setString(7, value);
  @$pb.TagNumber(8)
  $core.bool hasClusterHealth() => $_has(7);
  @$pb.TagNumber(8)
  void clearClusterHealth() => $_clearField(8);

  @$pb.TagNumber(9)
  $core.int get clientCount => $_getIZ(8);
  @$pb.TagNumber(9)
  set clientCount($core.int value) => $_setSignedInt32(8, value);
  @$pb.TagNumber(9)
  $core.bool hasClientCount() => $_has(8);
  @$pb.TagNumber(9)
  void clearClientCount() => $_clearField(9);

  @$pb.TagNumber(10)
  $core.int get maxAdmission => $_getIZ(9);
  @$pb.TagNumber(10)
  set maxAdmission($core.int value) => $_setSignedInt32(9, value);
  @$pb.TagNumber(10)
  $core.bool hasMaxAdmission() => $_has(9);
  @$pb.TagNumber(10)
  void clearMaxAdmission() => $_clearField(10);

  @$pb.TagNumber(11)
  $core.int get observedPeak => $_getIZ(10);
  @$pb.TagNumber(11)
  set observedPeak($core.int value) => $_setSignedInt32(10, value);
  @$pb.TagNumber(11)
  $core.bool hasObservedPeak() => $_has(10);
  @$pb.TagNumber(11)
  void clearObservedPeak() => $_clearField(11);
}

enum OpResult_Result { intValue, queryResult, uuidValue, notSet }

class OpResult extends $pb.GeneratedMessage {
  factory OpResult({
    SessionInfo? session,
    ResultType? type,
    $core.int? intValue,
    OpQueryResultProto? queryResult,
    $core.String? uuidValue,
    $core.String? uuid,
    $core.String? flag,
  }) {
    final result = OpResult._();
    if (session != null) result.session = session;
    if (type != null) result.type = type;
    if (intValue != null) result.intValue = intValue;
    if (queryResult != null) result.queryResult = queryResult;
    if (uuidValue != null) result.uuidValue = uuidValue;
    if (uuid != null) result.uuid = uuid;
    if (flag != null) result.flag = flag;
    return result;
  }

  OpResult._();

  factory OpResult.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      OpResult()..mergeFromBuffer(data, registry);
  factory OpResult.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      OpResult()..mergeFromJson(json, registry);

  static const $core.Map<$core.int, OpResult_Result> _OpResult_ResultByTag = {
    3: OpResult_Result.intValue,
    4: OpResult_Result.queryResult,
    5: OpResult_Result.uuidValue,
    0: OpResult_Result.notSet
  };
  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'OpResult',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: OpResult.$_createMessage)
    ..oo(0, [3, 4, 5])
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aE<ResultType>(2, _omitFieldNames ? '' : 'type',
        enumValues: ResultType.values)
    ..aI(3, _omitFieldNames ? '' : 'intValue')
    ..aOM<OpQueryResultProto>(4, _omitFieldNames ? '' : 'queryResult',
        subBuilder: OpQueryResultProto.$_createMessage)
    ..aOS(5, _omitFieldNames ? '' : 'uuidValue')
    ..aOS(6, _omitFieldNames ? '' : 'uuid')
    ..aOS(7, _omitFieldNames ? '' : 'flag')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  OpResult clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  OpResult copyWith(void Function(OpResult) updates) =>
      super.copyWith((message) => updates(message as OpResult)) as OpResult;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use OpResult() / OpResult.new instead')
  static OpResult create() => OpResult._();
  static $pb.GeneratedMessage $_createMessage() => OpResult._();
  @$core.override
  OpResult createEmptyInstance() => OpResult._();
  @$core.pragma('dart2js:noInline')
  static OpResult getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<OpResult>(OpResult.$_createMessage);
  static OpResult? _defaultInstance;

  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  OpResult_Result whichResult() => _OpResult_ResultByTag[$_whichOneof(0)]!;
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  void clearResult() => $_clearField($_whichOneof(0));

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  ResultType get type => $_getN(1);
  @$pb.TagNumber(2)
  set type(ResultType value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasType() => $_has(1);
  @$pb.TagNumber(2)
  void clearType() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.int get intValue => $_getIZ(2);
  @$pb.TagNumber(3)
  set intValue($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasIntValue() => $_has(2);
  @$pb.TagNumber(3)
  void clearIntValue() => $_clearField(3);

  @$pb.TagNumber(4)
  OpQueryResultProto get queryResult => $_getN(3);
  @$pb.TagNumber(4)
  set queryResult(OpQueryResultProto value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasQueryResult() => $_has(3);
  @$pb.TagNumber(4)
  void clearQueryResult() => $_clearField(4);
  @$pb.TagNumber(4)
  OpQueryResultProto ensureQueryResult() => $_ensure(3);

  @$pb.TagNumber(5)
  $core.String get uuidValue => $_getSZ(4);
  @$pb.TagNumber(5)
  set uuidValue($core.String value) => $_setString(4, value);
  @$pb.TagNumber(5)
  $core.bool hasUuidValue() => $_has(4);
  @$pb.TagNumber(5)
  void clearUuidValue() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.String get uuid => $_getSZ(5);
  @$pb.TagNumber(6)
  set uuid($core.String value) => $_setString(5, value);
  @$pb.TagNumber(6)
  $core.bool hasUuid() => $_has(5);
  @$pb.TagNumber(6)
  void clearUuid() => $_clearField(6);

  @$pb.TagNumber(7)
  $core.String get flag => $_getSZ(6);
  @$pb.TagNumber(7)
  set flag($core.String value) => $_setString(6, value);
  @$pb.TagNumber(7)
  $core.bool hasFlag() => $_has(6);
  @$pb.TagNumber(7)
  void clearFlag() => $_clearField(7);
}

class StatementRequest extends $pb.GeneratedMessage {
  factory StatementRequest({
    SessionInfo? session,
    $core.String? sql,
    $core.Iterable<ParameterProto>? parameters,
    $core.String? statementUUID,
    $core.Iterable<PropertyEntry>? properties,
  }) {
    final result = StatementRequest._();
    if (session != null) result.session = session;
    if (sql != null) result.sql = sql;
    if (parameters != null) result.parameters.addAll(parameters);
    if (statementUUID != null) result.statementUUID = statementUUID;
    if (properties != null) result.properties.addAll(properties);
    return result;
  }

  StatementRequest._();

  factory StatementRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      StatementRequest()..mergeFromBuffer(data, registry);
  factory StatementRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      StatementRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'StatementRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: StatementRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOS(2, _omitFieldNames ? '' : 'sql')
    ..pPM<ParameterProto>(3, _omitFieldNames ? '' : 'parameters',
        subBuilder: ParameterProto.$_createMessage)
    ..aOS(4, _omitFieldNames ? '' : 'statementUUID', protoName: 'statementUUID')
    ..pPM<PropertyEntry>(5, _omitFieldNames ? '' : 'properties',
        subBuilder: PropertyEntry.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  StatementRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  StatementRequest copyWith(void Function(StatementRequest) updates) =>
      super.copyWith((message) => updates(message as StatementRequest))
          as StatementRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use StatementRequest() / StatementRequest.new instead')
  static StatementRequest create() => StatementRequest._();
  static $pb.GeneratedMessage $_createMessage() => StatementRequest._();
  @$core.override
  StatementRequest createEmptyInstance() => StatementRequest._();
  @$core.pragma('dart2js:noInline')
  static StatementRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<StatementRequest>(
          StatementRequest.$_createMessage);
  static StatementRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.String get sql => $_getSZ(1);
  @$pb.TagNumber(2)
  set sql($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasSql() => $_has(1);
  @$pb.TagNumber(2)
  void clearSql() => $_clearField(2);

  @$pb.TagNumber(3)
  $pb.PbList<ParameterProto> get parameters => $_getList(2);

  @$pb.TagNumber(4)
  $core.String get statementUUID => $_getSZ(3);
  @$pb.TagNumber(4)
  set statementUUID($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasStatementUUID() => $_has(3);
  @$pb.TagNumber(4)
  void clearStatementUUID() => $_clearField(4);

  @$pb.TagNumber(5)
  $pb.PbList<PropertyEntry> get properties => $_getList(4);
}

class SqlErrorResponse extends $pb.GeneratedMessage {
  factory SqlErrorResponse({
    $core.String? reason,
    $core.String? sqlState,
    $core.int? vendorCode,
    SqlErrorType? sqlErrorType,
  }) {
    final result = SqlErrorResponse._();
    if (reason != null) result.reason = reason;
    if (sqlState != null) result.sqlState = sqlState;
    if (vendorCode != null) result.vendorCode = vendorCode;
    if (sqlErrorType != null) result.sqlErrorType = sqlErrorType;
    return result;
  }

  SqlErrorResponse._();

  factory SqlErrorResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      SqlErrorResponse()..mergeFromBuffer(data, registry);
  factory SqlErrorResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      SqlErrorResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'SqlErrorResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: SqlErrorResponse.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'reason')
    ..aOS(2, _omitFieldNames ? '' : 'sqlState', protoName: 'sqlState')
    ..aI(3, _omitFieldNames ? '' : 'vendorCode', protoName: 'vendorCode')
    ..aE<SqlErrorType>(4, _omitFieldNames ? '' : 'sqlErrorType',
        protoName: 'sqlErrorType', enumValues: SqlErrorType.values)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  SqlErrorResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  SqlErrorResponse copyWith(void Function(SqlErrorResponse) updates) =>
      super.copyWith((message) => updates(message as SqlErrorResponse))
          as SqlErrorResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use SqlErrorResponse() / SqlErrorResponse.new instead')
  static SqlErrorResponse create() => SqlErrorResponse._();
  static $pb.GeneratedMessage $_createMessage() => SqlErrorResponse._();
  @$core.override
  SqlErrorResponse createEmptyInstance() => SqlErrorResponse._();
  @$core.pragma('dart2js:noInline')
  static SqlErrorResponse getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<SqlErrorResponse>(
          SqlErrorResponse.$_createMessage);
  static SqlErrorResponse? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get reason => $_getSZ(0);
  @$pb.TagNumber(1)
  set reason($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasReason() => $_has(0);
  @$pb.TagNumber(1)
  void clearReason() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get sqlState => $_getSZ(1);
  @$pb.TagNumber(2)
  set sqlState($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasSqlState() => $_has(1);
  @$pb.TagNumber(2)
  void clearSqlState() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.int get vendorCode => $_getIZ(2);
  @$pb.TagNumber(3)
  set vendorCode($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasVendorCode() => $_has(2);
  @$pb.TagNumber(3)
  void clearVendorCode() => $_clearField(3);

  @$pb.TagNumber(4)
  SqlErrorType get sqlErrorType => $_getN(3);
  @$pb.TagNumber(4)
  set sqlErrorType(SqlErrorType value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasSqlErrorType() => $_has(3);
  @$pb.TagNumber(4)
  void clearSqlErrorType() => $_clearField(4);
}

class LobReference extends $pb.GeneratedMessage {
  factory LobReference({
    SessionInfo? session,
    $core.String? uuid,
    $core.int? bytesWritten,
    LobType? lobType,
    $core.int? columnIndex,
    $core.String? stmtUUID,
  }) {
    final result = LobReference._();
    if (session != null) result.session = session;
    if (uuid != null) result.uuid = uuid;
    if (bytesWritten != null) result.bytesWritten = bytesWritten;
    if (lobType != null) result.lobType = lobType;
    if (columnIndex != null) result.columnIndex = columnIndex;
    if (stmtUUID != null) result.stmtUUID = stmtUUID;
    return result;
  }

  LobReference._();

  factory LobReference.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      LobReference()..mergeFromBuffer(data, registry);
  factory LobReference.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      LobReference()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'LobReference',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: LobReference.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOS(2, _omitFieldNames ? '' : 'uuid')
    ..aI(3, _omitFieldNames ? '' : 'bytesWritten', protoName: 'bytesWritten')
    ..aE<LobType>(4, _omitFieldNames ? '' : 'lobType',
        protoName: 'lobType', enumValues: LobType.values)
    ..aI(5, _omitFieldNames ? '' : 'columnIndex', protoName: 'columnIndex')
    ..aOS(6, _omitFieldNames ? '' : 'stmtUUID', protoName: 'stmtUUID')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  LobReference clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  LobReference copyWith(void Function(LobReference) updates) =>
      super.copyWith((message) => updates(message as LobReference))
          as LobReference;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use LobReference() / LobReference.new instead')
  static LobReference create() => LobReference._();
  static $pb.GeneratedMessage $_createMessage() => LobReference._();
  @$core.override
  LobReference createEmptyInstance() => LobReference._();
  @$core.pragma('dart2js:noInline')
  static LobReference getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<LobReference>(
          LobReference.$_createMessage);
  static LobReference? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.String get uuid => $_getSZ(1);
  @$pb.TagNumber(2)
  set uuid($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasUuid() => $_has(1);
  @$pb.TagNumber(2)
  void clearUuid() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.int get bytesWritten => $_getIZ(2);
  @$pb.TagNumber(3)
  set bytesWritten($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasBytesWritten() => $_has(2);
  @$pb.TagNumber(3)
  void clearBytesWritten() => $_clearField(3);

  @$pb.TagNumber(4)
  LobType get lobType => $_getN(3);
  @$pb.TagNumber(4)
  set lobType(LobType value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasLobType() => $_has(3);
  @$pb.TagNumber(4)
  void clearLobType() => $_clearField(4);

  /// 5 till 6 only used to query Binary streams.
  @$pb.TagNumber(5)
  $core.int get columnIndex => $_getIZ(4);
  @$pb.TagNumber(5)
  set columnIndex($core.int value) => $_setSignedInt32(4, value);
  @$pb.TagNumber(5)
  $core.bool hasColumnIndex() => $_has(4);
  @$pb.TagNumber(5)
  void clearColumnIndex() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.String get stmtUUID => $_getSZ(5);
  @$pb.TagNumber(6)
  set stmtUUID($core.String value) => $_setString(5, value);
  @$pb.TagNumber(6)
  $core.bool hasStmtUUID() => $_has(5);
  @$pb.TagNumber(6)
  void clearStmtUUID() => $_clearField(6);
}

class ReadLobRequest extends $pb.GeneratedMessage {
  factory ReadLobRequest({
    LobReference? lobReference,
    $fixnum.Int64? position,
    $core.int? length,
  }) {
    final result = ReadLobRequest._();
    if (lobReference != null) result.lobReference = lobReference;
    if (position != null) result.position = position;
    if (length != null) result.length = length;
    return result;
  }

  ReadLobRequest._();

  factory ReadLobRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ReadLobRequest()..mergeFromBuffer(data, registry);
  factory ReadLobRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ReadLobRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'ReadLobRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: ReadLobRequest.$_createMessage)
    ..aOM<LobReference>(1, _omitFieldNames ? '' : 'lobReference',
        protoName: 'lobReference', subBuilder: LobReference.$_createMessage)
    ..aInt64(2, _omitFieldNames ? '' : 'position')
    ..aI(3, _omitFieldNames ? '' : 'length')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReadLobRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ReadLobRequest copyWith(void Function(ReadLobRequest) updates) =>
      super.copyWith((message) => updates(message as ReadLobRequest))
          as ReadLobRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use ReadLobRequest() / ReadLobRequest.new instead')
  static ReadLobRequest create() => ReadLobRequest._();
  static $pb.GeneratedMessage $_createMessage() => ReadLobRequest._();
  @$core.override
  ReadLobRequest createEmptyInstance() => ReadLobRequest._();
  @$core.pragma('dart2js:noInline')
  static ReadLobRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ReadLobRequest>(
          ReadLobRequest.$_createMessage);
  static ReadLobRequest? _defaultInstance;

  @$pb.TagNumber(1)
  LobReference get lobReference => $_getN(0);
  @$pb.TagNumber(1)
  set lobReference(LobReference value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasLobReference() => $_has(0);
  @$pb.TagNumber(1)
  void clearLobReference() => $_clearField(1);
  @$pb.TagNumber(1)
  LobReference ensureLobReference() => $_ensure(0);

  @$pb.TagNumber(2)
  $fixnum.Int64 get position => $_getI64(1);
  @$pb.TagNumber(2)
  set position($fixnum.Int64 value) => $_setInt64(1, value);
  @$pb.TagNumber(2)
  $core.bool hasPosition() => $_has(1);
  @$pb.TagNumber(2)
  void clearPosition() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.int get length => $_getIZ(2);
  @$pb.TagNumber(3)
  set length($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasLength() => $_has(2);
  @$pb.TagNumber(3)
  void clearLength() => $_clearField(3);
}

class LobDataBlock extends $pb.GeneratedMessage {
  factory LobDataBlock({
    SessionInfo? session,
    $fixnum.Int64? position,
    $core.List<$core.int>? data,
    LobType? lobType,
    $core.Iterable<PropertyEntry>? metadata,
  }) {
    final result = LobDataBlock._();
    if (session != null) result.session = session;
    if (position != null) result.position = position;
    if (data != null) result.data = data;
    if (lobType != null) result.lobType = lobType;
    if (metadata != null) result.metadata.addAll(metadata);
    return result;
  }

  LobDataBlock._();

  factory LobDataBlock.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      LobDataBlock()..mergeFromBuffer(data, registry);
  factory LobDataBlock.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      LobDataBlock()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'LobDataBlock',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: LobDataBlock.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aInt64(2, _omitFieldNames ? '' : 'position')
    ..a<$core.List<$core.int>>(
        3, _omitFieldNames ? '' : 'data', $pb.PbFieldType.OY)
    ..aE<LobType>(4, _omitFieldNames ? '' : 'lobType',
        protoName: 'lobType', enumValues: LobType.values)
    ..pPM<PropertyEntry>(5, _omitFieldNames ? '' : 'metadata',
        subBuilder: PropertyEntry.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  LobDataBlock clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  LobDataBlock copyWith(void Function(LobDataBlock) updates) =>
      super.copyWith((message) => updates(message as LobDataBlock))
          as LobDataBlock;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use LobDataBlock() / LobDataBlock.new instead')
  static LobDataBlock create() => LobDataBlock._();
  static $pb.GeneratedMessage $_createMessage() => LobDataBlock._();
  @$core.override
  LobDataBlock createEmptyInstance() => LobDataBlock._();
  @$core.pragma('dart2js:noInline')
  static LobDataBlock getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<LobDataBlock>(
          LobDataBlock.$_createMessage);
  static LobDataBlock? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $fixnum.Int64 get position => $_getI64(1);
  @$pb.TagNumber(2)
  set position($fixnum.Int64 value) => $_setInt64(1, value);
  @$pb.TagNumber(2)
  $core.bool hasPosition() => $_has(1);
  @$pb.TagNumber(2)
  void clearPosition() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.List<$core.int> get data => $_getN(2);
  @$pb.TagNumber(3)
  set data($core.List<$core.int> value) => $_setBytes(2, value);
  @$pb.TagNumber(3)
  $core.bool hasData() => $_has(2);
  @$pb.TagNumber(3)
  void clearData() => $_clearField(3);

  @$pb.TagNumber(4)
  LobType get lobType => $_getN(3);
  @$pb.TagNumber(4)
  set lobType(LobType value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasLobType() => $_has(3);
  @$pb.TagNumber(4)
  void clearLobType() => $_clearField(4);

  @$pb.TagNumber(5)
  $pb.PbList<PropertyEntry> get metadata => $_getList(4);
}

class SessionTerminationStatus extends $pb.GeneratedMessage {
  factory SessionTerminationStatus({
    $core.bool? terminated,
  }) {
    final result = SessionTerminationStatus._();
    if (terminated != null) result.terminated = terminated;
    return result;
  }

  SessionTerminationStatus._();

  factory SessionTerminationStatus.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      SessionTerminationStatus()..mergeFromBuffer(data, registry);
  factory SessionTerminationStatus.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      SessionTerminationStatus()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'SessionTerminationStatus',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: SessionTerminationStatus.$_createMessage)
    ..aOB(1, _omitFieldNames ? '' : 'terminated')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  SessionTerminationStatus clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  SessionTerminationStatus copyWith(
          void Function(SessionTerminationStatus) updates) =>
      super.copyWith((message) => updates(message as SessionTerminationStatus))
          as SessionTerminationStatus;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated(
      'Use SessionTerminationStatus() / SessionTerminationStatus.new instead')
  static SessionTerminationStatus create() => SessionTerminationStatus._();
  static $pb.GeneratedMessage $_createMessage() => SessionTerminationStatus._();
  @$core.override
  SessionTerminationStatus createEmptyInstance() =>
      SessionTerminationStatus._();
  @$core.pragma('dart2js:noInline')
  static SessionTerminationStatus getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<SessionTerminationStatus>(
          SessionTerminationStatus.$_createMessage);
  static SessionTerminationStatus? _defaultInstance;

  @$pb.TagNumber(1)
  $core.bool get terminated => $_getBF(0);
  @$pb.TagNumber(1)
  set terminated($core.bool value) => $_setBool(0, value);
  @$pb.TagNumber(1)
  $core.bool hasTerminated() => $_has(0);
  @$pb.TagNumber(1)
  void clearTerminated() => $_clearField(1);
}

class TargetCall extends $pb.GeneratedMessage {
  factory TargetCall({
    CallType? callType,
    $core.String? resourceName,
    $core.Iterable<ParameterValue>? params,
    TargetCall? nextCall,
  }) {
    final result = TargetCall._();
    if (callType != null) result.callType = callType;
    if (resourceName != null) result.resourceName = resourceName;
    if (params != null) result.params.addAll(params);
    if (nextCall != null) result.nextCall = nextCall;
    return result;
  }

  TargetCall._();

  factory TargetCall.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      TargetCall()..mergeFromBuffer(data, registry);
  factory TargetCall.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      TargetCall()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'TargetCall',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: TargetCall.$_createMessage)
    ..aE<CallType>(1, _omitFieldNames ? '' : 'callType',
        protoName: 'callType', enumValues: CallType.values)
    ..aOS(2, _omitFieldNames ? '' : 'resourceName', protoName: 'resourceName')
    ..pPM<ParameterValue>(3, _omitFieldNames ? '' : 'params',
        subBuilder: ParameterValue.$_createMessage)
    ..aOM<TargetCall>(4, _omitFieldNames ? '' : 'nextCall',
        protoName: 'nextCall', subBuilder: TargetCall.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  TargetCall clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  TargetCall copyWith(void Function(TargetCall) updates) =>
      super.copyWith((message) => updates(message as TargetCall)) as TargetCall;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use TargetCall() / TargetCall.new instead')
  static TargetCall create() => TargetCall._();
  static $pb.GeneratedMessage $_createMessage() => TargetCall._();
  @$core.override
  TargetCall createEmptyInstance() => TargetCall._();
  @$core.pragma('dart2js:noInline')
  static TargetCall getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<TargetCall>(TargetCall.$_createMessage);
  static TargetCall? _defaultInstance;

  @$pb.TagNumber(1)
  CallType get callType => $_getN(0);
  @$pb.TagNumber(1)
  set callType(CallType value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasCallType() => $_has(0);
  @$pb.TagNumber(1)
  void clearCallType() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get resourceName => $_getSZ(1);
  @$pb.TagNumber(2)
  set resourceName($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasResourceName() => $_has(1);
  @$pb.TagNumber(2)
  void clearResourceName() => $_clearField(2);

  @$pb.TagNumber(3)
  $pb.PbList<ParameterValue> get params => $_getList(2);

  @$pb.TagNumber(4)
  TargetCall get nextCall => $_getN(3);
  @$pb.TagNumber(4)
  set nextCall(TargetCall value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasNextCall() => $_has(3);
  @$pb.TagNumber(4)
  void clearNextCall() => $_clearField(4);
  @$pb.TagNumber(4)
  TargetCall ensureNextCall() => $_ensure(3);
}

class CallResourceRequest extends $pb.GeneratedMessage {
  factory CallResourceRequest({
    SessionInfo? session,
    ResourceType? resourceType,
    $core.String? resourceUUID,
    TargetCall? target,
    $core.Iterable<PropertyEntry>? properties,
  }) {
    final result = CallResourceRequest._();
    if (session != null) result.session = session;
    if (resourceType != null) result.resourceType = resourceType;
    if (resourceUUID != null) result.resourceUUID = resourceUUID;
    if (target != null) result.target = target;
    if (properties != null) result.properties.addAll(properties);
    return result;
  }

  CallResourceRequest._();

  factory CallResourceRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      CallResourceRequest()..mergeFromBuffer(data, registry);
  factory CallResourceRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      CallResourceRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'CallResourceRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: CallResourceRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aE<ResourceType>(2, _omitFieldNames ? '' : 'resourceType',
        protoName: 'resourceType', enumValues: ResourceType.values)
    ..aOS(3, _omitFieldNames ? '' : 'resourceUUID', protoName: 'resourceUUID')
    ..aOM<TargetCall>(4, _omitFieldNames ? '' : 'target',
        subBuilder: TargetCall.$_createMessage)
    ..pPM<PropertyEntry>(5, _omitFieldNames ? '' : 'properties',
        subBuilder: PropertyEntry.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  CallResourceRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  CallResourceRequest copyWith(void Function(CallResourceRequest) updates) =>
      super.copyWith((message) => updates(message as CallResourceRequest))
          as CallResourceRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core
      .Deprecated('Use CallResourceRequest() / CallResourceRequest.new instead')
  static CallResourceRequest create() => CallResourceRequest._();
  static $pb.GeneratedMessage $_createMessage() => CallResourceRequest._();
  @$core.override
  CallResourceRequest createEmptyInstance() => CallResourceRequest._();
  @$core.pragma('dart2js:noInline')
  static CallResourceRequest getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<CallResourceRequest>(
          CallResourceRequest.$_createMessage);
  static CallResourceRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  ResourceType get resourceType => $_getN(1);
  @$pb.TagNumber(2)
  set resourceType(ResourceType value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasResourceType() => $_has(1);
  @$pb.TagNumber(2)
  void clearResourceType() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get resourceUUID => $_getSZ(2);
  @$pb.TagNumber(3)
  set resourceUUID($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasResourceUUID() => $_has(2);
  @$pb.TagNumber(3)
  void clearResourceUUID() => $_clearField(3);

  @$pb.TagNumber(4)
  TargetCall get target => $_getN(3);
  @$pb.TagNumber(4)
  set target(TargetCall value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasTarget() => $_has(3);
  @$pb.TagNumber(4)
  void clearTarget() => $_clearField(4);
  @$pb.TagNumber(4)
  TargetCall ensureTarget() => $_ensure(3);

  @$pb.TagNumber(5)
  $pb.PbList<PropertyEntry> get properties => $_getList(4);
}

class CallResourceResponse extends $pb.GeneratedMessage {
  factory CallResourceResponse({
    SessionInfo? session,
    $core.String? resourceUUID,
    $core.Iterable<ParameterValue>? values,
  }) {
    final result = CallResourceResponse._();
    if (session != null) result.session = session;
    if (resourceUUID != null) result.resourceUUID = resourceUUID;
    if (values != null) result.values.addAll(values);
    return result;
  }

  CallResourceResponse._();

  factory CallResourceResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      CallResourceResponse()..mergeFromBuffer(data, registry);
  factory CallResourceResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      CallResourceResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'CallResourceResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: CallResourceResponse.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOS(2, _omitFieldNames ? '' : 'resourceUUID', protoName: 'resourceUUID')
    ..pPM<ParameterValue>(3, _omitFieldNames ? '' : 'values',
        subBuilder: ParameterValue.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  CallResourceResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  CallResourceResponse copyWith(void Function(CallResourceResponse) updates) =>
      super.copyWith((message) => updates(message as CallResourceResponse))
          as CallResourceResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated(
      'Use CallResourceResponse() / CallResourceResponse.new instead')
  static CallResourceResponse create() => CallResourceResponse._();
  static $pb.GeneratedMessage $_createMessage() => CallResourceResponse._();
  @$core.override
  CallResourceResponse createEmptyInstance() => CallResourceResponse._();
  @$core.pragma('dart2js:noInline')
  static CallResourceResponse getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<CallResourceResponse>(
          CallResourceResponse.$_createMessage);
  static CallResourceResponse? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.String get resourceUUID => $_getSZ(1);
  @$pb.TagNumber(2)
  set resourceUUID($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasResourceUUID() => $_has(1);
  @$pb.TagNumber(2)
  void clearResourceUUID() => $_clearField(2);

  @$pb.TagNumber(3)
  $pb.PbList<ParameterValue> get values => $_getList(2);
}

class ResultSetFetchRequest extends $pb.GeneratedMessage {
  factory ResultSetFetchRequest({
    SessionInfo? session,
    $core.String? resultSetUUID,
    $core.int? size,
  }) {
    final result = ResultSetFetchRequest._();
    if (session != null) result.session = session;
    if (resultSetUUID != null) result.resultSetUUID = resultSetUUID;
    if (size != null) result.size = size;
    return result;
  }

  ResultSetFetchRequest._();

  factory ResultSetFetchRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ResultSetFetchRequest()..mergeFromBuffer(data, registry);
  factory ResultSetFetchRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ResultSetFetchRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'ResultSetFetchRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: ResultSetFetchRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOS(2, _omitFieldNames ? '' : 'resultSetUUID', protoName: 'resultSetUUID')
    ..aI(3, _omitFieldNames ? '' : 'size')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ResultSetFetchRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ResultSetFetchRequest copyWith(
          void Function(ResultSetFetchRequest) updates) =>
      super.copyWith((message) => updates(message as ResultSetFetchRequest))
          as ResultSetFetchRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated(
      'Use ResultSetFetchRequest() / ResultSetFetchRequest.new instead')
  static ResultSetFetchRequest create() => ResultSetFetchRequest._();
  static $pb.GeneratedMessage $_createMessage() => ResultSetFetchRequest._();
  @$core.override
  ResultSetFetchRequest createEmptyInstance() => ResultSetFetchRequest._();
  @$core.pragma('dart2js:noInline')
  static ResultSetFetchRequest getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<ResultSetFetchRequest>(
          ResultSetFetchRequest.$_createMessage);
  static ResultSetFetchRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.String get resultSetUUID => $_getSZ(1);
  @$pb.TagNumber(2)
  set resultSetUUID($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasResultSetUUID() => $_has(1);
  @$pb.TagNumber(2)
  void clearResultSetUUID() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.int get size => $_getIZ(2);
  @$pb.TagNumber(3)
  set size($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasSize() => $_has(2);
  @$pb.TagNumber(3)
  void clearSize() => $_clearField(3);
}

/// Represents a distributed transaction identifier (Xid)
class XidProto extends $pb.GeneratedMessage {
  factory XidProto({
    $core.int? formatId,
    $core.List<$core.int>? globalTransactionId,
    $core.List<$core.int>? branchQualifier,
  }) {
    final result = XidProto._();
    if (formatId != null) result.formatId = formatId;
    if (globalTransactionId != null)
      result.globalTransactionId = globalTransactionId;
    if (branchQualifier != null) result.branchQualifier = branchQualifier;
    return result;
  }

  XidProto._();

  factory XidProto.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XidProto()..mergeFromBuffer(data, registry);
  factory XidProto.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XidProto()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XidProto',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XidProto.$_createMessage)
    ..aI(1, _omitFieldNames ? '' : 'formatId', protoName: 'formatId')
    ..a<$core.List<$core.int>>(
        2, _omitFieldNames ? '' : 'globalTransactionId', $pb.PbFieldType.OY,
        protoName: 'globalTransactionId')
    ..a<$core.List<$core.int>>(
        3, _omitFieldNames ? '' : 'branchQualifier', $pb.PbFieldType.OY,
        protoName: 'branchQualifier')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XidProto clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XidProto copyWith(void Function(XidProto) updates) =>
      super.copyWith((message) => updates(message as XidProto)) as XidProto;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XidProto() / XidProto.new instead')
  static XidProto create() => XidProto._();
  static $pb.GeneratedMessage $_createMessage() => XidProto._();
  @$core.override
  XidProto createEmptyInstance() => XidProto._();
  @$core.pragma('dart2js:noInline')
  static XidProto getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<XidProto>(XidProto.$_createMessage);
  static XidProto? _defaultInstance;

  @$pb.TagNumber(1)
  $core.int get formatId => $_getIZ(0);
  @$pb.TagNumber(1)
  set formatId($core.int value) => $_setSignedInt32(0, value);
  @$pb.TagNumber(1)
  $core.bool hasFormatId() => $_has(0);
  @$pb.TagNumber(1)
  void clearFormatId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.List<$core.int> get globalTransactionId => $_getN(1);
  @$pb.TagNumber(2)
  set globalTransactionId($core.List<$core.int> value) => $_setBytes(1, value);
  @$pb.TagNumber(2)
  $core.bool hasGlobalTransactionId() => $_has(1);
  @$pb.TagNumber(2)
  void clearGlobalTransactionId() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.List<$core.int> get branchQualifier => $_getN(2);
  @$pb.TagNumber(3)
  set branchQualifier($core.List<$core.int> value) => $_setBytes(2, value);
  @$pb.TagNumber(3)
  $core.bool hasBranchQualifier() => $_has(2);
  @$pb.TagNumber(3)
  void clearBranchQualifier() => $_clearField(3);
}

/// Request to start an XA transaction
class XaStartRequest extends $pb.GeneratedMessage {
  factory XaStartRequest({
    SessionInfo? session,
    XidProto? xid,
    $core.int? flags,
  }) {
    final result = XaStartRequest._();
    if (session != null) result.session = session;
    if (xid != null) result.xid = xid;
    if (flags != null) result.flags = flags;
    return result;
  }

  XaStartRequest._();

  factory XaStartRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaStartRequest()..mergeFromBuffer(data, registry);
  factory XaStartRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaStartRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaStartRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaStartRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOM<XidProto>(2, _omitFieldNames ? '' : 'xid',
        subBuilder: XidProto.$_createMessage)
    ..aI(3, _omitFieldNames ? '' : 'flags')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaStartRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaStartRequest copyWith(void Function(XaStartRequest) updates) =>
      super.copyWith((message) => updates(message as XaStartRequest))
          as XaStartRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaStartRequest() / XaStartRequest.new instead')
  static XaStartRequest create() => XaStartRequest._();
  static $pb.GeneratedMessage $_createMessage() => XaStartRequest._();
  @$core.override
  XaStartRequest createEmptyInstance() => XaStartRequest._();
  @$core.pragma('dart2js:noInline')
  static XaStartRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<XaStartRequest>(
          XaStartRequest.$_createMessage);
  static XaStartRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  XidProto get xid => $_getN(1);
  @$pb.TagNumber(2)
  set xid(XidProto value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasXid() => $_has(1);
  @$pb.TagNumber(2)
  void clearXid() => $_clearField(2);
  @$pb.TagNumber(2)
  XidProto ensureXid() => $_ensure(1);

  @$pb.TagNumber(3)
  $core.int get flags => $_getIZ(2);
  @$pb.TagNumber(3)
  set flags($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasFlags() => $_has(2);
  @$pb.TagNumber(3)
  void clearFlags() => $_clearField(3);
}

/// Request to end an XA transaction
class XaEndRequest extends $pb.GeneratedMessage {
  factory XaEndRequest({
    SessionInfo? session,
    XidProto? xid,
    $core.int? flags,
  }) {
    final result = XaEndRequest._();
    if (session != null) result.session = session;
    if (xid != null) result.xid = xid;
    if (flags != null) result.flags = flags;
    return result;
  }

  XaEndRequest._();

  factory XaEndRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaEndRequest()..mergeFromBuffer(data, registry);
  factory XaEndRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaEndRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaEndRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaEndRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOM<XidProto>(2, _omitFieldNames ? '' : 'xid',
        subBuilder: XidProto.$_createMessage)
    ..aI(3, _omitFieldNames ? '' : 'flags')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaEndRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaEndRequest copyWith(void Function(XaEndRequest) updates) =>
      super.copyWith((message) => updates(message as XaEndRequest))
          as XaEndRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaEndRequest() / XaEndRequest.new instead')
  static XaEndRequest create() => XaEndRequest._();
  static $pb.GeneratedMessage $_createMessage() => XaEndRequest._();
  @$core.override
  XaEndRequest createEmptyInstance() => XaEndRequest._();
  @$core.pragma('dart2js:noInline')
  static XaEndRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<XaEndRequest>(
          XaEndRequest.$_createMessage);
  static XaEndRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  XidProto get xid => $_getN(1);
  @$pb.TagNumber(2)
  set xid(XidProto value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasXid() => $_has(1);
  @$pb.TagNumber(2)
  void clearXid() => $_clearField(2);
  @$pb.TagNumber(2)
  XidProto ensureXid() => $_ensure(1);

  @$pb.TagNumber(3)
  $core.int get flags => $_getIZ(2);
  @$pb.TagNumber(3)
  set flags($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasFlags() => $_has(2);
  @$pb.TagNumber(3)
  void clearFlags() => $_clearField(3);
}

/// Request to prepare an XA transaction
class XaPrepareRequest extends $pb.GeneratedMessage {
  factory XaPrepareRequest({
    SessionInfo? session,
    XidProto? xid,
  }) {
    final result = XaPrepareRequest._();
    if (session != null) result.session = session;
    if (xid != null) result.xid = xid;
    return result;
  }

  XaPrepareRequest._();

  factory XaPrepareRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaPrepareRequest()..mergeFromBuffer(data, registry);
  factory XaPrepareRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaPrepareRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaPrepareRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaPrepareRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOM<XidProto>(2, _omitFieldNames ? '' : 'xid',
        subBuilder: XidProto.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaPrepareRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaPrepareRequest copyWith(void Function(XaPrepareRequest) updates) =>
      super.copyWith((message) => updates(message as XaPrepareRequest))
          as XaPrepareRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaPrepareRequest() / XaPrepareRequest.new instead')
  static XaPrepareRequest create() => XaPrepareRequest._();
  static $pb.GeneratedMessage $_createMessage() => XaPrepareRequest._();
  @$core.override
  XaPrepareRequest createEmptyInstance() => XaPrepareRequest._();
  @$core.pragma('dart2js:noInline')
  static XaPrepareRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<XaPrepareRequest>(
          XaPrepareRequest.$_createMessage);
  static XaPrepareRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  XidProto get xid => $_getN(1);
  @$pb.TagNumber(2)
  set xid(XidProto value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasXid() => $_has(1);
  @$pb.TagNumber(2)
  void clearXid() => $_clearField(2);
  @$pb.TagNumber(2)
  XidProto ensureXid() => $_ensure(1);
}

/// Response for prepare operation
class XaPrepareResponse extends $pb.GeneratedMessage {
  factory XaPrepareResponse({
    SessionInfo? session,
    $core.int? result,
  }) {
    final result$ = XaPrepareResponse._();
    if (session != null) result$.session = session;
    if (result != null) result$.result = result;
    return result$;
  }

  XaPrepareResponse._();

  factory XaPrepareResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaPrepareResponse()..mergeFromBuffer(data, registry);
  factory XaPrepareResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaPrepareResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaPrepareResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaPrepareResponse.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aI(2, _omitFieldNames ? '' : 'result')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaPrepareResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaPrepareResponse copyWith(void Function(XaPrepareResponse) updates) =>
      super.copyWith((message) => updates(message as XaPrepareResponse))
          as XaPrepareResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaPrepareResponse() / XaPrepareResponse.new instead')
  static XaPrepareResponse create() => XaPrepareResponse._();
  static $pb.GeneratedMessage $_createMessage() => XaPrepareResponse._();
  @$core.override
  XaPrepareResponse createEmptyInstance() => XaPrepareResponse._();
  @$core.pragma('dart2js:noInline')
  static XaPrepareResponse getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<XaPrepareResponse>(
          XaPrepareResponse.$_createMessage);
  static XaPrepareResponse? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.int get result => $_getIZ(1);
  @$pb.TagNumber(2)
  set result($core.int value) => $_setSignedInt32(1, value);
  @$pb.TagNumber(2)
  $core.bool hasResult() => $_has(1);
  @$pb.TagNumber(2)
  void clearResult() => $_clearField(2);
}

/// Request to commit an XA transaction
class XaCommitRequest extends $pb.GeneratedMessage {
  factory XaCommitRequest({
    SessionInfo? session,
    XidProto? xid,
    $core.bool? onePhase,
  }) {
    final result = XaCommitRequest._();
    if (session != null) result.session = session;
    if (xid != null) result.xid = xid;
    if (onePhase != null) result.onePhase = onePhase;
    return result;
  }

  XaCommitRequest._();

  factory XaCommitRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaCommitRequest()..mergeFromBuffer(data, registry);
  factory XaCommitRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaCommitRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaCommitRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaCommitRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOM<XidProto>(2, _omitFieldNames ? '' : 'xid',
        subBuilder: XidProto.$_createMessage)
    ..aOB(3, _omitFieldNames ? '' : 'onePhase', protoName: 'onePhase')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaCommitRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaCommitRequest copyWith(void Function(XaCommitRequest) updates) =>
      super.copyWith((message) => updates(message as XaCommitRequest))
          as XaCommitRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaCommitRequest() / XaCommitRequest.new instead')
  static XaCommitRequest create() => XaCommitRequest._();
  static $pb.GeneratedMessage $_createMessage() => XaCommitRequest._();
  @$core.override
  XaCommitRequest createEmptyInstance() => XaCommitRequest._();
  @$core.pragma('dart2js:noInline')
  static XaCommitRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<XaCommitRequest>(
          XaCommitRequest.$_createMessage);
  static XaCommitRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  XidProto get xid => $_getN(1);
  @$pb.TagNumber(2)
  set xid(XidProto value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasXid() => $_has(1);
  @$pb.TagNumber(2)
  void clearXid() => $_clearField(2);
  @$pb.TagNumber(2)
  XidProto ensureXid() => $_ensure(1);

  @$pb.TagNumber(3)
  $core.bool get onePhase => $_getBF(2);
  @$pb.TagNumber(3)
  set onePhase($core.bool value) => $_setBool(2, value);
  @$pb.TagNumber(3)
  $core.bool hasOnePhase() => $_has(2);
  @$pb.TagNumber(3)
  void clearOnePhase() => $_clearField(3);
}

/// Request to rollback an XA transaction
class XaRollbackRequest extends $pb.GeneratedMessage {
  factory XaRollbackRequest({
    SessionInfo? session,
    XidProto? xid,
  }) {
    final result = XaRollbackRequest._();
    if (session != null) result.session = session;
    if (xid != null) result.xid = xid;
    return result;
  }

  XaRollbackRequest._();

  factory XaRollbackRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaRollbackRequest()..mergeFromBuffer(data, registry);
  factory XaRollbackRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaRollbackRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaRollbackRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaRollbackRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOM<XidProto>(2, _omitFieldNames ? '' : 'xid',
        subBuilder: XidProto.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaRollbackRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaRollbackRequest copyWith(void Function(XaRollbackRequest) updates) =>
      super.copyWith((message) => updates(message as XaRollbackRequest))
          as XaRollbackRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaRollbackRequest() / XaRollbackRequest.new instead')
  static XaRollbackRequest create() => XaRollbackRequest._();
  static $pb.GeneratedMessage $_createMessage() => XaRollbackRequest._();
  @$core.override
  XaRollbackRequest createEmptyInstance() => XaRollbackRequest._();
  @$core.pragma('dart2js:noInline')
  static XaRollbackRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<XaRollbackRequest>(
          XaRollbackRequest.$_createMessage);
  static XaRollbackRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  XidProto get xid => $_getN(1);
  @$pb.TagNumber(2)
  set xid(XidProto value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasXid() => $_has(1);
  @$pb.TagNumber(2)
  void clearXid() => $_clearField(2);
  @$pb.TagNumber(2)
  XidProto ensureXid() => $_ensure(1);
}

/// Request to recover XIDs
class XaRecoverRequest extends $pb.GeneratedMessage {
  factory XaRecoverRequest({
    SessionInfo? session,
    $core.int? flag,
  }) {
    final result = XaRecoverRequest._();
    if (session != null) result.session = session;
    if (flag != null) result.flag = flag;
    return result;
  }

  XaRecoverRequest._();

  factory XaRecoverRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaRecoverRequest()..mergeFromBuffer(data, registry);
  factory XaRecoverRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaRecoverRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaRecoverRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaRecoverRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aI(2, _omitFieldNames ? '' : 'flag')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaRecoverRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaRecoverRequest copyWith(void Function(XaRecoverRequest) updates) =>
      super.copyWith((message) => updates(message as XaRecoverRequest))
          as XaRecoverRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaRecoverRequest() / XaRecoverRequest.new instead')
  static XaRecoverRequest create() => XaRecoverRequest._();
  static $pb.GeneratedMessage $_createMessage() => XaRecoverRequest._();
  @$core.override
  XaRecoverRequest createEmptyInstance() => XaRecoverRequest._();
  @$core.pragma('dart2js:noInline')
  static XaRecoverRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<XaRecoverRequest>(
          XaRecoverRequest.$_createMessage);
  static XaRecoverRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.int get flag => $_getIZ(1);
  @$pb.TagNumber(2)
  set flag($core.int value) => $_setSignedInt32(1, value);
  @$pb.TagNumber(2)
  $core.bool hasFlag() => $_has(1);
  @$pb.TagNumber(2)
  void clearFlag() => $_clearField(2);
}

/// Response for recover operation
class XaRecoverResponse extends $pb.GeneratedMessage {
  factory XaRecoverResponse({
    SessionInfo? session,
    $core.Iterable<XidProto>? xids,
  }) {
    final result = XaRecoverResponse._();
    if (session != null) result.session = session;
    if (xids != null) result.xids.addAll(xids);
    return result;
  }

  XaRecoverResponse._();

  factory XaRecoverResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaRecoverResponse()..mergeFromBuffer(data, registry);
  factory XaRecoverResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaRecoverResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaRecoverResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaRecoverResponse.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..pPM<XidProto>(2, _omitFieldNames ? '' : 'xids',
        subBuilder: XidProto.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaRecoverResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaRecoverResponse copyWith(void Function(XaRecoverResponse) updates) =>
      super.copyWith((message) => updates(message as XaRecoverResponse))
          as XaRecoverResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaRecoverResponse() / XaRecoverResponse.new instead')
  static XaRecoverResponse create() => XaRecoverResponse._();
  static $pb.GeneratedMessage $_createMessage() => XaRecoverResponse._();
  @$core.override
  XaRecoverResponse createEmptyInstance() => XaRecoverResponse._();
  @$core.pragma('dart2js:noInline')
  static XaRecoverResponse getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<XaRecoverResponse>(
          XaRecoverResponse.$_createMessage);
  static XaRecoverResponse? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $pb.PbList<XidProto> get xids => $_getList(1);
}

/// Request to forget an XA transaction
class XaForgetRequest extends $pb.GeneratedMessage {
  factory XaForgetRequest({
    SessionInfo? session,
    XidProto? xid,
  }) {
    final result = XaForgetRequest._();
    if (session != null) result.session = session;
    if (xid != null) result.xid = xid;
    return result;
  }

  XaForgetRequest._();

  factory XaForgetRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaForgetRequest()..mergeFromBuffer(data, registry);
  factory XaForgetRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaForgetRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaForgetRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaForgetRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOM<XidProto>(2, _omitFieldNames ? '' : 'xid',
        subBuilder: XidProto.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaForgetRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaForgetRequest copyWith(void Function(XaForgetRequest) updates) =>
      super.copyWith((message) => updates(message as XaForgetRequest))
          as XaForgetRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaForgetRequest() / XaForgetRequest.new instead')
  static XaForgetRequest create() => XaForgetRequest._();
  static $pb.GeneratedMessage $_createMessage() => XaForgetRequest._();
  @$core.override
  XaForgetRequest createEmptyInstance() => XaForgetRequest._();
  @$core.pragma('dart2js:noInline')
  static XaForgetRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<XaForgetRequest>(
          XaForgetRequest.$_createMessage);
  static XaForgetRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  XidProto get xid => $_getN(1);
  @$pb.TagNumber(2)
  set xid(XidProto value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasXid() => $_has(1);
  @$pb.TagNumber(2)
  void clearXid() => $_clearField(2);
  @$pb.TagNumber(2)
  XidProto ensureXid() => $_ensure(1);
}

/// Request to set transaction timeout
class XaSetTransactionTimeoutRequest extends $pb.GeneratedMessage {
  factory XaSetTransactionTimeoutRequest({
    SessionInfo? session,
    $core.int? seconds,
  }) {
    final result = XaSetTransactionTimeoutRequest._();
    if (session != null) result.session = session;
    if (seconds != null) result.seconds = seconds;
    return result;
  }

  XaSetTransactionTimeoutRequest._();

  factory XaSetTransactionTimeoutRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaSetTransactionTimeoutRequest()..mergeFromBuffer(data, registry);
  factory XaSetTransactionTimeoutRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaSetTransactionTimeoutRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaSetTransactionTimeoutRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaSetTransactionTimeoutRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aI(2, _omitFieldNames ? '' : 'seconds')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaSetTransactionTimeoutRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaSetTransactionTimeoutRequest copyWith(
          void Function(XaSetTransactionTimeoutRequest) updates) =>
      super.copyWith(
              (message) => updates(message as XaSetTransactionTimeoutRequest))
          as XaSetTransactionTimeoutRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated(
      'Use XaSetTransactionTimeoutRequest() / XaSetTransactionTimeoutRequest.new instead')
  static XaSetTransactionTimeoutRequest create() =>
      XaSetTransactionTimeoutRequest._();
  static $pb.GeneratedMessage $_createMessage() =>
      XaSetTransactionTimeoutRequest._();
  @$core.override
  XaSetTransactionTimeoutRequest createEmptyInstance() =>
      XaSetTransactionTimeoutRequest._();
  @$core.pragma('dart2js:noInline')
  static XaSetTransactionTimeoutRequest getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<XaSetTransactionTimeoutRequest>(
          XaSetTransactionTimeoutRequest.$_createMessage);
  static XaSetTransactionTimeoutRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.int get seconds => $_getIZ(1);
  @$pb.TagNumber(2)
  set seconds($core.int value) => $_setSignedInt32(1, value);
  @$pb.TagNumber(2)
  $core.bool hasSeconds() => $_has(1);
  @$pb.TagNumber(2)
  void clearSeconds() => $_clearField(2);
}

/// Response for set transaction timeout
class XaSetTransactionTimeoutResponse extends $pb.GeneratedMessage {
  factory XaSetTransactionTimeoutResponse({
    SessionInfo? session,
    $core.bool? success,
  }) {
    final result = XaSetTransactionTimeoutResponse._();
    if (session != null) result.session = session;
    if (success != null) result.success = success;
    return result;
  }

  XaSetTransactionTimeoutResponse._();

  factory XaSetTransactionTimeoutResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaSetTransactionTimeoutResponse()..mergeFromBuffer(data, registry);
  factory XaSetTransactionTimeoutResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaSetTransactionTimeoutResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaSetTransactionTimeoutResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaSetTransactionTimeoutResponse.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOB(2, _omitFieldNames ? '' : 'success')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaSetTransactionTimeoutResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaSetTransactionTimeoutResponse copyWith(
          void Function(XaSetTransactionTimeoutResponse) updates) =>
      super.copyWith(
              (message) => updates(message as XaSetTransactionTimeoutResponse))
          as XaSetTransactionTimeoutResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated(
      'Use XaSetTransactionTimeoutResponse() / XaSetTransactionTimeoutResponse.new instead')
  static XaSetTransactionTimeoutResponse create() =>
      XaSetTransactionTimeoutResponse._();
  static $pb.GeneratedMessage $_createMessage() =>
      XaSetTransactionTimeoutResponse._();
  @$core.override
  XaSetTransactionTimeoutResponse createEmptyInstance() =>
      XaSetTransactionTimeoutResponse._();
  @$core.pragma('dart2js:noInline')
  static XaSetTransactionTimeoutResponse getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<XaSetTransactionTimeoutResponse>(
          XaSetTransactionTimeoutResponse.$_createMessage);
  static XaSetTransactionTimeoutResponse? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.bool get success => $_getBF(1);
  @$pb.TagNumber(2)
  set success($core.bool value) => $_setBool(1, value);
  @$pb.TagNumber(2)
  $core.bool hasSuccess() => $_has(1);
  @$pb.TagNumber(2)
  void clearSuccess() => $_clearField(2);
}

/// Request to get transaction timeout
class XaGetTransactionTimeoutRequest extends $pb.GeneratedMessage {
  factory XaGetTransactionTimeoutRequest({
    SessionInfo? session,
  }) {
    final result = XaGetTransactionTimeoutRequest._();
    if (session != null) result.session = session;
    return result;
  }

  XaGetTransactionTimeoutRequest._();

  factory XaGetTransactionTimeoutRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaGetTransactionTimeoutRequest()..mergeFromBuffer(data, registry);
  factory XaGetTransactionTimeoutRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaGetTransactionTimeoutRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaGetTransactionTimeoutRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaGetTransactionTimeoutRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaGetTransactionTimeoutRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaGetTransactionTimeoutRequest copyWith(
          void Function(XaGetTransactionTimeoutRequest) updates) =>
      super.copyWith(
              (message) => updates(message as XaGetTransactionTimeoutRequest))
          as XaGetTransactionTimeoutRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated(
      'Use XaGetTransactionTimeoutRequest() / XaGetTransactionTimeoutRequest.new instead')
  static XaGetTransactionTimeoutRequest create() =>
      XaGetTransactionTimeoutRequest._();
  static $pb.GeneratedMessage $_createMessage() =>
      XaGetTransactionTimeoutRequest._();
  @$core.override
  XaGetTransactionTimeoutRequest createEmptyInstance() =>
      XaGetTransactionTimeoutRequest._();
  @$core.pragma('dart2js:noInline')
  static XaGetTransactionTimeoutRequest getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<XaGetTransactionTimeoutRequest>(
          XaGetTransactionTimeoutRequest.$_createMessage);
  static XaGetTransactionTimeoutRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);
}

/// Response for get transaction timeout
class XaGetTransactionTimeoutResponse extends $pb.GeneratedMessage {
  factory XaGetTransactionTimeoutResponse({
    SessionInfo? session,
    $core.int? seconds,
  }) {
    final result = XaGetTransactionTimeoutResponse._();
    if (session != null) result.session = session;
    if (seconds != null) result.seconds = seconds;
    return result;
  }

  XaGetTransactionTimeoutResponse._();

  factory XaGetTransactionTimeoutResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaGetTransactionTimeoutResponse()..mergeFromBuffer(data, registry);
  factory XaGetTransactionTimeoutResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaGetTransactionTimeoutResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaGetTransactionTimeoutResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaGetTransactionTimeoutResponse.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aI(2, _omitFieldNames ? '' : 'seconds')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaGetTransactionTimeoutResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaGetTransactionTimeoutResponse copyWith(
          void Function(XaGetTransactionTimeoutResponse) updates) =>
      super.copyWith(
              (message) => updates(message as XaGetTransactionTimeoutResponse))
          as XaGetTransactionTimeoutResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated(
      'Use XaGetTransactionTimeoutResponse() / XaGetTransactionTimeoutResponse.new instead')
  static XaGetTransactionTimeoutResponse create() =>
      XaGetTransactionTimeoutResponse._();
  static $pb.GeneratedMessage $_createMessage() =>
      XaGetTransactionTimeoutResponse._();
  @$core.override
  XaGetTransactionTimeoutResponse createEmptyInstance() =>
      XaGetTransactionTimeoutResponse._();
  @$core.pragma('dart2js:noInline')
  static XaGetTransactionTimeoutResponse getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<XaGetTransactionTimeoutResponse>(
          XaGetTransactionTimeoutResponse.$_createMessage);
  static XaGetTransactionTimeoutResponse? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.int get seconds => $_getIZ(1);
  @$pb.TagNumber(2)
  set seconds($core.int value) => $_setSignedInt32(1, value);
  @$pb.TagNumber(2)
  $core.bool hasSeconds() => $_has(1);
  @$pb.TagNumber(2)
  void clearSeconds() => $_clearField(2);
}

/// Request to check if two sessions are from the same resource manager
class XaIsSameRMRequest extends $pb.GeneratedMessage {
  factory XaIsSameRMRequest({
    SessionInfo? session1,
    SessionInfo? session2,
  }) {
    final result = XaIsSameRMRequest._();
    if (session1 != null) result.session1 = session1;
    if (session2 != null) result.session2 = session2;
    return result;
  }

  XaIsSameRMRequest._();

  factory XaIsSameRMRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaIsSameRMRequest()..mergeFromBuffer(data, registry);
  factory XaIsSameRMRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaIsSameRMRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaIsSameRMRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaIsSameRMRequest.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session1',
        subBuilder: SessionInfo.$_createMessage)
    ..aOM<SessionInfo>(2, _omitFieldNames ? '' : 'session2',
        subBuilder: SessionInfo.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaIsSameRMRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaIsSameRMRequest copyWith(void Function(XaIsSameRMRequest) updates) =>
      super.copyWith((message) => updates(message as XaIsSameRMRequest))
          as XaIsSameRMRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaIsSameRMRequest() / XaIsSameRMRequest.new instead')
  static XaIsSameRMRequest create() => XaIsSameRMRequest._();
  static $pb.GeneratedMessage $_createMessage() => XaIsSameRMRequest._();
  @$core.override
  XaIsSameRMRequest createEmptyInstance() => XaIsSameRMRequest._();
  @$core.pragma('dart2js:noInline')
  static XaIsSameRMRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<XaIsSameRMRequest>(
          XaIsSameRMRequest.$_createMessage);
  static XaIsSameRMRequest? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session1 => $_getN(0);
  @$pb.TagNumber(1)
  set session1(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession1() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession1() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession1() => $_ensure(0);

  @$pb.TagNumber(2)
  SessionInfo get session2 => $_getN(1);
  @$pb.TagNumber(2)
  set session2(SessionInfo value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasSession2() => $_has(1);
  @$pb.TagNumber(2)
  void clearSession2() => $_clearField(2);
  @$pb.TagNumber(2)
  SessionInfo ensureSession2() => $_ensure(1);
}

/// Response for isSameRM check
class XaIsSameRMResponse extends $pb.GeneratedMessage {
  factory XaIsSameRMResponse({
    $core.bool? isSame,
  }) {
    final result = XaIsSameRMResponse._();
    if (isSame != null) result.isSame = isSame;
    return result;
  }

  XaIsSameRMResponse._();

  factory XaIsSameRMResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaIsSameRMResponse()..mergeFromBuffer(data, registry);
  factory XaIsSameRMResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaIsSameRMResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaIsSameRMResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaIsSameRMResponse.$_createMessage)
    ..aOB(1, _omitFieldNames ? '' : 'isSame', protoName: 'isSame')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaIsSameRMResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaIsSameRMResponse copyWith(void Function(XaIsSameRMResponse) updates) =>
      super.copyWith((message) => updates(message as XaIsSameRMResponse))
          as XaIsSameRMResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaIsSameRMResponse() / XaIsSameRMResponse.new instead')
  static XaIsSameRMResponse create() => XaIsSameRMResponse._();
  static $pb.GeneratedMessage $_createMessage() => XaIsSameRMResponse._();
  @$core.override
  XaIsSameRMResponse createEmptyInstance() => XaIsSameRMResponse._();
  @$core.pragma('dart2js:noInline')
  static XaIsSameRMResponse getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<XaIsSameRMResponse>(
          XaIsSameRMResponse.$_createMessage);
  static XaIsSameRMResponse? _defaultInstance;

  @$pb.TagNumber(1)
  $core.bool get isSame => $_getBF(0);
  @$pb.TagNumber(1)
  set isSame($core.bool value) => $_setBool(0, value);
  @$pb.TagNumber(1)
  $core.bool hasIsSame() => $_has(0);
  @$pb.TagNumber(1)
  void clearIsSame() => $_clearField(1);
}

/// Generic XA response for operations that don't return specific data
class XaResponse extends $pb.GeneratedMessage {
  factory XaResponse({
    SessionInfo? session,
    $core.bool? success,
    $core.String? message,
  }) {
    final result = XaResponse._();
    if (session != null) result.session = session;
    if (success != null) result.success = success;
    if (message != null) result.message = message;
    return result;
  }

  XaResponse._();

  factory XaResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaResponse()..mergeFromBuffer(data, registry);
  factory XaResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      XaResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'XaResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'com.openjproxy.grpc'),
      createEmptyInstance: XaResponse.$_createMessage)
    ..aOM<SessionInfo>(1, _omitFieldNames ? '' : 'session',
        subBuilder: SessionInfo.$_createMessage)
    ..aOB(2, _omitFieldNames ? '' : 'success')
    ..aOS(3, _omitFieldNames ? '' : 'message')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  XaResponse copyWith(void Function(XaResponse) updates) =>
      super.copyWith((message) => updates(message as XaResponse)) as XaResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use XaResponse() / XaResponse.new instead')
  static XaResponse create() => XaResponse._();
  static $pb.GeneratedMessage $_createMessage() => XaResponse._();
  @$core.override
  XaResponse createEmptyInstance() => XaResponse._();
  @$core.pragma('dart2js:noInline')
  static XaResponse getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<XaResponse>(XaResponse.$_createMessage);
  static XaResponse? _defaultInstance;

  @$pb.TagNumber(1)
  SessionInfo get session => $_getN(0);
  @$pb.TagNumber(1)
  set session(SessionInfo value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasSession() => $_has(0);
  @$pb.TagNumber(1)
  void clearSession() => $_clearField(1);
  @$pb.TagNumber(1)
  SessionInfo ensureSession() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.bool get success => $_getBF(1);
  @$pb.TagNumber(2)
  set success($core.bool value) => $_setBool(1, value);
  @$pb.TagNumber(2)
  $core.bool hasSuccess() => $_has(1);
  @$pb.TagNumber(2)
  void clearSuccess() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get message => $_getSZ(2);
  @$pb.TagNumber(3)
  set message($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasMessage() => $_has(2);
  @$pb.TagNumber(3)
  void clearMessage() => $_clearField(3);
}

const $core.bool _omitFieldNames =
    $core.bool.fromEnvironment('protobuf.omit_field_names');
const $core.bool _omitMessageNames =
    $core.bool.fromEnvironment('protobuf.omit_message_names');
