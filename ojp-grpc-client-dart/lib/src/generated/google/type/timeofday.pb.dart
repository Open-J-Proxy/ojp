// This is a generated file - do not edit.
//
// Generated from google/type/timeofday.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names, prefer_relative_imports

import 'dart:core' as $core;

import 'package:protobuf/protobuf.dart' as $pb;

export 'package:protobuf/protobuf.dart' show GeneratedMessageGenericExtensions;

/// Represents a time of day. The date and time zone are either not significant
/// or are specified elsewhere. An API may choose to allow leap seconds. Related
/// types are [google.type.Date][google.type.Date] and
/// `google.protobuf.Timestamp`.
class TimeOfDay extends $pb.GeneratedMessage {
  factory TimeOfDay({
    $core.int? hours,
    $core.int? minutes,
    $core.int? seconds,
    $core.int? nanos,
  }) {
    final result = TimeOfDay._();
    if (hours != null) result.hours = hours;
    if (minutes != null) result.minutes = minutes;
    if (seconds != null) result.seconds = seconds;
    if (nanos != null) result.nanos = nanos;
    return result;
  }

  TimeOfDay._();

  factory TimeOfDay.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      TimeOfDay()..mergeFromBuffer(data, registry);
  factory TimeOfDay.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      TimeOfDay()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'TimeOfDay',
      package: const $pb.PackageName(_omitMessageNames ? '' : 'google.type'),
      createEmptyInstance: TimeOfDay.$_createMessage)
    ..aI(1, _omitFieldNames ? '' : 'hours')
    ..aI(2, _omitFieldNames ? '' : 'minutes')
    ..aI(3, _omitFieldNames ? '' : 'seconds')
    ..aI(4, _omitFieldNames ? '' : 'nanos')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  TimeOfDay clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  TimeOfDay copyWith(void Function(TimeOfDay) updates) =>
      super.copyWith((message) => updates(message as TimeOfDay)) as TimeOfDay;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use TimeOfDay() / TimeOfDay.new instead')
  static TimeOfDay create() => TimeOfDay._();
  static $pb.GeneratedMessage $_createMessage() => TimeOfDay._();
  @$core.override
  TimeOfDay createEmptyInstance() => TimeOfDay._();
  @$core.pragma('dart2js:noInline')
  static TimeOfDay getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<TimeOfDay>(TimeOfDay.$_createMessage);
  static TimeOfDay? _defaultInstance;

  /// Hours of day in 24 hour format. Should be from 0 to 23. An API may choose
  /// to allow the value "24:00:00" for scenarios like business closing time.
  @$pb.TagNumber(1)
  $core.int get hours => $_getIZ(0);
  @$pb.TagNumber(1)
  set hours($core.int value) => $_setSignedInt32(0, value);
  @$pb.TagNumber(1)
  $core.bool hasHours() => $_has(0);
  @$pb.TagNumber(1)
  void clearHours() => $_clearField(1);

  /// Minutes of hour of day. Must be from 0 to 59.
  @$pb.TagNumber(2)
  $core.int get minutes => $_getIZ(1);
  @$pb.TagNumber(2)
  set minutes($core.int value) => $_setSignedInt32(1, value);
  @$pb.TagNumber(2)
  $core.bool hasMinutes() => $_has(1);
  @$pb.TagNumber(2)
  void clearMinutes() => $_clearField(2);

  /// Seconds of minutes of the time. Must normally be from 0 to 59. An API may
  /// allow the value 60 if it allows leap-seconds.
  @$pb.TagNumber(3)
  $core.int get seconds => $_getIZ(2);
  @$pb.TagNumber(3)
  set seconds($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasSeconds() => $_has(2);
  @$pb.TagNumber(3)
  void clearSeconds() => $_clearField(3);

  /// Fractions of seconds in nanoseconds. Must be from 0 to 999,999,999.
  @$pb.TagNumber(4)
  $core.int get nanos => $_getIZ(3);
  @$pb.TagNumber(4)
  set nanos($core.int value) => $_setSignedInt32(3, value);
  @$pb.TagNumber(4)
  $core.bool hasNanos() => $_has(3);
  @$pb.TagNumber(4)
  void clearNanos() => $_clearField(4);
}

const $core.bool _omitFieldNames =
    $core.bool.fromEnvironment('protobuf.omit_field_names');
const $core.bool _omitMessageNames =
    $core.bool.fromEnvironment('protobuf.omit_message_names');
